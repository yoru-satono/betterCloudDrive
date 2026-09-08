[CmdletBinding()]
param(
    [ValidateSet("all", "backend", "integration", "blackbox", "security", "web", "tauri", "mobile-unit", "mobile-android", "office", "webdav", "observability", "resilience")]
    [string[]]$Module = @("all"),
    [switch]$SkipDockerBuild,
    [switch]$SkipTauri,
    [switch]$SkipAndroid,
    [switch]$SkipOnlyOffice,
    [switch]$SkipE2ECleanup,
    [string]$AndroidSerial = "emulator-5556",
    [switch]$KeepGoing
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptRoot "..")
Set-Location $RepoRoot

$script:Results = New-Object System.Collections.Generic.List[object]
$script:HadFailure = $false
$script:StackStarted = $false

$SelectedModules = New-Object "System.Collections.Generic.HashSet[string]" ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($name in $Module) {
    [void]$SelectedModules.Add($name)
}
$RunAll = $SelectedModules.Contains("all")
$RunBackend = $RunAll -or $SelectedModules.Contains("backend")
$RunIntegration = $RunAll -or $SelectedModules.Contains("integration")
$RunBlackbox = $RunAll -or $SelectedModules.Contains("blackbox")
$RunSecurity = $RunAll -or $SelectedModules.Contains("security")
$RunWeb = $RunAll -or $SelectedModules.Contains("web")
$RunTauri = ($RunAll -or $SelectedModules.Contains("tauri")) -and -not $SkipTauri
$RunMobileUnit = $RunAll -or $SelectedModules.Contains("mobile-unit")
$RunMobileAndroid = ($RunAll -or $SelectedModules.Contains("mobile-android")) -and -not $SkipAndroid
$RunOffice = ($RunAll -or $SelectedModules.Contains("office")) -and -not $SkipOnlyOffice
$RunWebDav = $RunAll -or $SelectedModules.Contains("webdav")
$RunObservability = $RunAll -or $SelectedModules.Contains("observability")
$RunResilience = $RunAll -or $SelectedModules.Contains("resilience")
$NeedsBackendHttp = $RunBlackbox -or $RunSecurity -or $RunWeb -or $RunTauri -or $RunOffice -or $RunWebDav -or $RunObservability -or $RunResilience
$NeedsFrontendHttp = $RunWeb -or $RunTauri -or $RunOffice
$NeedsMailpit = $RunBlackbox -or $RunWeb -or $RunTauri -or $RunOffice
$NeedsOnlyOffice = $RunOffice
$NeedsDocker = $NeedsBackendHttp -or $NeedsOnlyOffice
$ShouldCleanupE2E = -not $SkipE2ECleanup -and $NeedsDocker
$RunId = if ($env:BCD_TEST_RUN_ID) { $env:BCD_TEST_RUN_ID } else { "bcd_$(Get-Date -Format 'yyyyMMdd_HHmmss')_$PID" }
$ResultRoot = Join-Path $RepoRoot "test-results"

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

function Invoke-CleanupStep {
    if (-not $ShouldCleanupE2E -or -not $script:StackStarted) {
        return
    }
    Write-Host ""
    Write-Host "==> E2E data cleanup"
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & (Join-Path $ScriptRoot "cleanup-e2e-data.ps1")
        if ($LASTEXITCODE -ne 0) {
            throw "E2E cleanup failed with exit code $LASTEXITCODE"
        }
        $timer.Stop()
        Add-Result -Name "E2E data cleanup" -Status "PASS" -Elapsed $timer.Elapsed
        Write-Host "<== PASS E2E data cleanup ($([math]::Round($timer.Elapsed.TotalSeconds, 1))s)"
    } catch {
        $timer.Stop()
        $script:HadFailure = $true
        Add-Result -Name "E2E data cleanup" -Status "FAIL" -Elapsed $timer.Elapsed -Message $_.Exception.Message
        Write-Host "<== FAIL E2E data cleanup ($([math]::Round($timer.Elapsed.TotalSeconds, 1))s)"
        Write-Host $_.Exception.Message
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
        "rabbitmq",
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

function Wait-DockerServiceHealthy {
    param(
        [string]$Service,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = "container not found"
    while ((Get-Date) -lt $deadline) {
        $containerIds = @(& docker compose ps -q $Service 2>$null)
        $composeExitCode = $LASTEXITCODE
        $containerId = $containerIds | Select-Object -First 1
        if ($composeExitCode -eq 0 -and $containerId) {
            $lastStatus = (& docker inspect --format "{{.State.Health.Status}}" $containerId 2>$null)
            if ($LASTEXITCODE -eq 0 -and $lastStatus -eq "healthy") {
                Write-Host "$Service container is healthy"
                return
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "$Service did not become healthy within ${TimeoutSeconds}s. Last status: $lastStatus"
}

function Set-BackendFunctionalTestRateLimits {
    Invoke-WithEnv @{
        DRIVE_RATE_LIMIT_DEFAULT_PERMITS = "10000"
        DRIVE_RATE_LIMIT_DEFAULT_PERIOD_SECONDS = "1"
        DRIVE_RATE_LIMIT_AUTH_PERMITS = "10000"
        DRIVE_RATE_LIMIT_AUTH_PERIOD_SECONDS = "1"
        DRIVE_RATE_LIMIT_UPLOAD_PERMITS = "10000"
        DRIVE_RATE_LIMIT_UPLOAD_PERIOD_SECONDS = "1"
        DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERMITS = "10000"
        DRIVE_RATE_LIMIT_UPLOAD_CHUNK_PERIOD_SECONDS = "1"
    } {
        Invoke-External "docker" @("compose", "up", "-d", "--no-deps", "--force-recreate", "backend")
    }
    Wait-DockerServiceHealthy "backend"
    Invoke-External "docker" @("compose", "restart", "frontend")
    Wait-DockerServiceHealthy "frontend"
}

function Restore-BackendDefaultRateLimits {
    Invoke-External "docker" @("compose", "up", "-d", "--no-deps", "--force-recreate", "backend")
    Wait-DockerServiceHealthy "backend"
    Invoke-External "docker" @("compose", "restart", "frontend")
    Wait-DockerServiceHealthy "frontend"
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
    Write-Host "Selected modules: $($SelectedModules -join ', ')"
    Write-Host "Test run ID: $RunId"
    New-Item -ItemType Directory -Force -Path $ResultRoot | Out-Null
    @("backend", "integration", "blackbox", "security", "web", "tauri", "office", "webdav", "observability", "resilience", "coverage") |
        ForEach-Object { New-Item -ItemType Directory -Force -Path (Join-Path $ResultRoot $_) | Out-Null }

    if ($RunAll -and ($SkipTauri -or $SkipAndroid -or $SkipOnlyOffice)) {
        throw "Module 'all' is a complete acceptance run; SkipTauri, SkipAndroid (KMP), and SkipOnlyOffice are not allowed."
    }

    if ($RunWebDav -and -not (Get-Command "rclone" -ErrorAction SilentlyContinue)) {
        throw "rclone is required by the webdav module. Install it from https://rclone.org/install/ and ensure it is on PATH."
    }
    if ($RunMobileAndroid -and -not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
        throw "adb is required by the KMP Android device module and was not found on PATH."
    }

    Invoke-Step "Prepare env files" {
        Initialize-EnvFiles
    }

    if ($NeedsDocker) { Invoke-Step "Start Docker stack" {
        Invoke-WithEnv @{ TRACING_SAMPLING_PROBABILITY = "1.0" } {
            if ($SkipDockerBuild) {
                Invoke-External "docker" @("compose", "-f", "docker-compose.yml", "-f", "docker-compose.diagnostics.yml", "up", "-d")
            } else {
                Invoke-External "docker" @("compose", "-f", "docker-compose.yml", "-f", "docker-compose.diagnostics.yml", "up", "-d", "--build")
            }
        }
        $script:StackStarted = $true
    } }

    if ($NeedsDocker) { Invoke-Step "Wait for services" {
        if ($NeedsBackendHttp) {
            Wait-Http "Backend management" "http://127.0.0.1:8081/actuator/health"
        }
        if ($NeedsFrontendHttp) {
            Wait-Http "Frontend" "http://127.0.0.1:3000"
        }
        if ($NeedsMailpit) {
            Wait-Http "Mailpit" "http://127.0.0.1:8025/api/v1/messages"
        }
        if ($NeedsOnlyOffice) {
            Wait-Http "OnlyOffice" "http://127.0.0.1:8082/healthcheck"
        }
    } }

    if ($RunBackend) {
        Invoke-Step "Backend unit tests" {
            Invoke-InDirectory "backend" {
                Invoke-External "mvn" @("test", "-Dsurefire.reportNameSuffix=backend")
            }
        }
    }

    if ($RunIntegration) {
        Invoke-Step "Backend Testcontainers integration tests" {
            Invoke-InDirectory "backend" {
                Invoke-External "mvn" @("verify", "-Pintegration", "-DskipUnitTests", "-pl", "betterclouddrive-dal,betterclouddrive-storage,betterclouddrive-service,betterclouddrive-web", "-am")
            }
        }
    }

    if ($RunBlackbox) {
        Invoke-Step "Backend black-box tests" {
            Set-BackendFunctionalTestRateLimits
            try {
                Invoke-InDirectory "backend-test" {
                    $pytest = Join-Path (Get-Location) ".venv/Scripts/pytest.exe"
                    if (Test-Path $pytest) {
                            Invoke-External $pytest @("-v", "-m", "not webdav and not office and not observability and not resilience and not security", "--junitxml=../test-results/blackbox/junit.xml")
                    } elseif (Get-Command "uv" -ErrorAction SilentlyContinue) {
                        Invoke-WithEnv @{ UV_CACHE_DIR = "../.uv-cache" } {
                            Invoke-External "uv" @("run", "pytest", "-v", "-m", "not webdav and not office and not observability and not resilience and not security", "--junitxml=../test-results/blackbox/junit.xml")
                        }
                    } else {
                        Invoke-External "python" @("-m", "pytest", "-v", "-m", "not webdav and not office and not observability and not resilience and not security", "--junitxml=../test-results/blackbox/junit.xml")
                    }
                }
            } finally {
                Restore-BackendDefaultRateLimits
            }
        }
    }

    if ($RunSecurity) {
        Invoke-Step "Security acceptance tests" {
            Invoke-InDirectory "backend-test" {
                Invoke-WithEnv @{ BCD_TEST_RUN_ID = $RunId; UV_CACHE_DIR = "../.uv-cache" } {
                    Invoke-External "uv" @("run", "pytest", "-v", "-m", "security", "--junitxml=../test-results/security/junit.xml")
                }
            }
        }
    }

    if ($RunWeb) {
        Invoke-Step "Frontend unit tests and coverage" {
            Invoke-InDirectory "frontend" {
                Invoke-External "pnpm" @("run", "test:coverage")
            }
        }
    }

    if ($RunWeb) {
        Invoke-Step "Web E2E tests" {
            Set-BackendFunctionalTestRateLimits
            try {
                Invoke-InDirectory "frontend" {
                    Invoke-WithEnv @{ E2E_ONLYOFFICE = "0" } {
                        Invoke-External "node" @("scripts/run-playwright-docker.mjs", "--project=core")
                    }
                }
            } finally {
                Restore-BackendDefaultRateLimits
            }
        }
    }

    if ($RunOffice) {
        Invoke-Step "OnlyOffice E2E tests" {
            Invoke-InDirectory "frontend" {
                Invoke-WithEnv @{ E2E_ONLYOFFICE = "1" } {
                    Invoke-External "node" @("scripts/run-playwright-docker.mjs", "--project=office")
                }
            }
        }
    }

    if ($RunTauri) {
        Invoke-Step "Tauri Rust unit tests" {
            Invoke-InDirectory "frontend/src-tauri" {
                Invoke-External "cargo" @("test", "--locked")
            }
        }
        Invoke-Step "Tauri E2E build" {
            Invoke-InDirectory "frontend" {
                Invoke-External "pnpm" @("run", "tauri:e2e:build")
            }
        }

        Invoke-Step "Tauri E2E tests" {
            Set-BackendFunctionalTestRateLimits
            try {
                Invoke-InDirectory "frontend" {
                    Invoke-External "pnpm" @("run", "test:e2e:tauri")
                }
            } finally {
                Restore-BackendDefaultRateLimits
            }
        }
    }

    if ($RunWebDav) {
        Invoke-Step "WebDAV protocol and rclone acceptance" {
            Invoke-InDirectory "backend-test" {
                Invoke-WithEnv @{ BCD_TEST_RUN_ID = $RunId; UV_CACHE_DIR = "../.uv-cache" } {
                    Invoke-External "uv" @("run", "pytest", "-v", "-m", "webdav", "--junitxml=../test-results/webdav/junit.xml")
                }
            }
        }
    }

    if ($RunObservability) {
        Invoke-Step "Reload Grafana provisioning and gateway" {
            Invoke-External "docker" @("compose", "restart", "frontend", "grafana")
            Wait-DockerServiceHealthy "frontend"
            Wait-DockerServiceHealthy "grafana"
        }
        Invoke-Step "Prometheus rule tests" {
            Invoke-External "docker" @("compose", "exec", "-T", "prometheus", "promtool", "check", "config", "/etc/prometheus/prometheus.yml")
            Invoke-External "docker" @("compose", "exec", "-T", "prometheus", "promtool", "check", "rules", "/etc/prometheus/rules/better-cloud-drive.yml")
            Invoke-External "docker" @("compose", "exec", "-T", "prometheus", "promtool", "test", "rules", "/etc/prometheus/tests/better-cloud-drive.test.yml")
        }
        Invoke-Step "Observability acceptance" {
            Invoke-InDirectory "backend-test" {
                Invoke-WithEnv @{ BCD_TEST_RUN_ID = $RunId; UV_CACHE_DIR = "../.uv-cache" } {
                    Invoke-External "uv" @("run", "pytest", "-v", "-m", "observability", "--junitxml=../test-results/observability/junit.xml")
                }
            }
        }
    }

    if ($RunResilience) {
        Invoke-Step "Resilience acceptance" {
            Invoke-InDirectory "backend-test" {
                Invoke-WithEnv @{ BCD_TEST_RUN_ID = $RunId; UV_CACHE_DIR = "../.uv-cache" } {
                    Invoke-External "uv" @("run", "pytest", "-v", "-m", "resilience", "--junitxml=../test-results/resilience/junit.xml")
                }
            }
        }
    }

    if ($RunMobileUnit) {
        Invoke-Step "KMP host tests, Android builds, and iOS metadata" {
            Invoke-InDirectory "mobile" {
                Invoke-External ".\gradlew.bat" @(
                    ":shared:testAndroidHostTest",
                    ":androidApp:assembleDebug",
                    ":androidApp:assembleRelease",
                    "-PenableIos=true",
                    ":shared:compileIosMainKotlinMetadata"
                )
            }
        }
    }
    if ($RunMobileAndroid) {
        Invoke-Step "KMP mobile Android device tests" {
            Invoke-InDirectory "mobile" {
                if ($AndroidSerial -match ":") {
                    Invoke-External "adb" @("connect", $AndroidSerial)
                }
                Invoke-WithEnv @{ ANDROID_SERIAL = $AndroidSerial } {
                    Invoke-External ".\gradlew.bat" @(":androidApp:connectedDebugAndroidTest")
                }
            }
        }
    }
} finally {
    Invoke-CleanupStep
    Write-Host ""
    Write-Host "Validation summary"
    $script:Results | Format-Table -AutoSize
}

if ($script:HadFailure) {
    exit 1
}
