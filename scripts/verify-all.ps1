[CmdletBinding()]
param(
    [switch]$SkipDockerBuild,
    [switch]$SkipTauri,
    [switch]$SkipAndroid,
    [switch]$SkipOnlyOffice,
    [string]$AndroidSerial = "localhost:16416",
    [switch]$KeepGoing
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptRoot "..")
Set-Location $RepoRoot

$script:Results = New-Object System.Collections.Generic.List[object]
$script:HadFailure = $false

function Add-Result {
    param(
        [string]$Name,
        [string]$Status,
        [timespan]$Elapsed,
        [string]$Message = ""
    )
    $script:Results.Add([pscustomobject]@{
        Name = $Name
        Status = $Status
        Seconds = [math]::Round($Elapsed.TotalSeconds, 1)
        Message = $Message
    })
}

function Invoke-External {
    param(
        [string]$FilePath,
        [string[]]$Arguments = @()
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')"
    }
}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    Write-Host ""
    Write-Host "==> $Name"
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Action
        $timer.Stop()
        Add-Result -Name $Name -Status "PASS" -Elapsed $timer.Elapsed
        Write-Host "<== PASS $Name ($([math]::Round($timer.Elapsed.TotalSeconds, 1))s)"
    } catch {
        $timer.Stop()
        $script:HadFailure = $true
        Add-Result -Name $Name -Status "FAIL" -Elapsed $timer.Elapsed -Message $_.Exception.Message
        Write-Host "<== FAIL $Name ($([math]::Round($timer.Elapsed.TotalSeconds, 1))s)"
        Write-Host $_.Exception.Message
        if (-not $KeepGoing) {
            throw
        }
    }
}

function Copy-IfMissing {
    param(
        [string]$Source,
        [string]$Target
    )
    if ((Test-Path $Source) -and -not (Test-Path $Target)) {
        Copy-Item $Source $Target
        Write-Host "Created missing env file: $Target"
    }
}

function Initialize-EnvFiles {
    Copy-IfMissing ".env.example" ".env"
    $envFiles = @(
        "database",
        "storage",
        "security",
        "backend",
        "frontend",
        "observability",
        "mail"
    )
    foreach ($name in $envFiles) {
        Copy-IfMissing "config/env-files/$name.env.example" "config/env-files/$name.env"
    }
}

function Wait-Http {
    param(
        [string]$Name,
        [string]$Url,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                Write-Host "$Name is reachable at $Url"
                return
            }
            $lastError = "HTTP $($response.StatusCode)"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not become reachable at $Url within ${TimeoutSeconds}s. Last error: $lastError"
}

function Invoke-InDirectory {
    param(
        [string]$Path,
        [scriptblock]$Action
    )
    Push-Location $Path
    try {
        & $Action
    } finally {
        Pop-Location
    }
}

function Invoke-WithEnv {
    param(
        [hashtable]$Values,
        [scriptblock]$Action
    )
    $oldValues = @{}
    foreach ($key in $Values.Keys) {
        $oldValues[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
        [Environment]::SetEnvironmentVariable($key, [string]$Values[$key], "Process")
    }
    try {
        & $Action
    } finally {
        foreach ($key in $Values.Keys) {
            [Environment]::SetEnvironmentVariable($key, $oldValues[$key], "Process")
        }
    }
}

try {
    Invoke-Step "Prepare env files" {
        Initialize-EnvFiles
    }

    Invoke-Step "Start Docker stack" {
        if ($SkipDockerBuild) {
            Invoke-External "docker" @("compose", "up", "-d")
        } else {
            Invoke-External "docker" @("compose", "up", "-d", "--build")
        }
    }

    Invoke-Step "Wait for services" {
        Wait-Http "Backend" "http://127.0.0.1:8080/actuator/health"
        Wait-Http "Frontend" "http://127.0.0.1:3000"
        Wait-Http "Mailpit" "http://127.0.0.1:8025/api/v1/messages"
        if (-not $SkipOnlyOffice) {
            Wait-Http "OnlyOffice" "http://127.0.0.1:8082/healthcheck"
        }
    }

    Invoke-Step "Backend unit tests" {
        Invoke-InDirectory "backend" {
            Invoke-External "mvn" @("test")
        }
    }

    Invoke-Step "Backend black-box tests" {
        Invoke-InDirectory "backend-test" {
            $pytest = Join-Path (Get-Location) ".venv/Scripts/pytest.exe"
            if (Test-Path $pytest) {
                Invoke-External $pytest @("-v")
            } elseif (Get-Command "uv" -ErrorAction SilentlyContinue) {
                Invoke-WithEnv @{ UV_CACHE_DIR = "../.uv-cache" } {
                    Invoke-External "uv" @("run", "pytest", "-v")
                }
            } else {
                Invoke-External "python" @("-m", "pytest", "-v")
            }
        }
    }

    Invoke-Step "Frontend unit tests" {
        Invoke-InDirectory "frontend" {
            Invoke-External "npm" @("run", "test")
        }
    }

    Invoke-Step "Web E2E tests" {
        Invoke-InDirectory "frontend" {
            $webEnv = @{
                E2E_USE_DOCKER = "1"
                E2E_BASE_URL = "http://127.0.0.1:3000"
                E2E_BACKEND_URL = "http://127.0.0.1:8080"
            }
            if (-not $SkipOnlyOffice) {
                $webEnv["E2E_ONLYOFFICE"] = "1"
            }
            Invoke-WithEnv $webEnv {
                Invoke-External "npm" @("run", "test:e2e:docker")
            }
        }
    }

    if (-not $SkipTauri) {
        Invoke-Step "Tauri E2E build" {
            Invoke-InDirectory "frontend" {
                Invoke-External "npm" @("run", "tauri:e2e:build")
            }
        }

        Invoke-Step "Tauri E2E tests" {
            Invoke-InDirectory "frontend" {
                Invoke-External "npm" @("run", "test:e2e:tauri")
            }
        }
    }

    if (-not $SkipAndroid) {
        Invoke-Step "Android unit tests" {
            Invoke-InDirectory "android" {
                Invoke-External ".\gradlew.bat" @("testDebugUnitTest")
            }
        }

        Invoke-Step "Android E2E tests" {
            Invoke-InDirectory "android" {
                if ($AndroidSerial -match ":") {
                    Invoke-External "adb" @("connect", $AndroidSerial)
                }
                Invoke-WithEnv @{ ANDROID_SERIAL = $AndroidSerial } {
                    Invoke-External ".\gradlew.bat" @("connectedDebugAndroidTest")
                }
            }
        }
    }
} finally {
    Write-Host ""
    Write-Host "Validation summary"
    $script:Results | Format-Table -AutoSize
}

if ($script:HadFailure) {
    exit 1
}
