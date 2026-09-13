<#
.SYNOPSIS
Runs BetterCloudDrive verification modules on Windows or Linux.

.DESCRIPTION
PowerShell counterpart of verify-all.sh. Module selection, Docker lifecycle,
rate-limit overrides, reports, and cleanup follow the same acceptance semantics.

.PARAMETER Module
Module to run. Pass a PowerShell array or comma-separated value to select multiple
modules. Defaults to all.

.PARAMETER SkipDockerBuild
Starts Compose without rebuilding images.

.PARAMETER SkipTauri
Skips Tauri. Not allowed with the all module.

.PARAMETER SkipAndroid
Skips KMP Android device tests. Not allowed with the all module.

.PARAMETER SkipOnlyOffice
Skips OnlyOffice. Not allowed with the all module.

.PARAMETER SkipE2ECleanup
Does not remove E2E data created by the verification run.

.PARAMETER AndroidSerial
adb device serial for KMP tests. Defaults to emulator-5556.

.PARAMETER KeepGoing
Continues with later verification steps after a failure.

.EXAMPLE
./scripts/verify-all.ps1 -Module backend

.EXAMPLE
./scripts/verify-all.ps1 -Module backend,integration -KeepGoing
#>
[CmdletBinding()]
param(
    [string[]]$Module = @("all"),
    [switch]$SkipDockerBuild,
    [switch]$SkipTauri,
    [switch]$SkipAndroid,
    [switch]$SkipOnlyOffice,
    [switch]$SkipE2ECleanup,
    [ValidateNotNullOrEmpty()]
    [string]$AndroidSerial = "emulator-5556",
    [switch]$KeepGoing
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 3.0

$AllowedModules = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]@("all", "backend", "integration", "blackbox", "security", "web", "tauri", "mobile-unit", "mobile-android", "office", "webdav", "observability", "resilience"),
    [System.StringComparer]::OrdinalIgnoreCase
)
$SelectedModules = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($value in $Module) {
    foreach ($candidate in $value.Split(",", [System.StringSplitOptions]::RemoveEmptyEntries)) {
        $name = $candidate.Trim()
        if (-not $AllowedModules.Contains($name)) {
            throw "Unknown module '$name'. Allowed values: $($AllowedModules -join ', ')."
        }
        [void]$SelectedModules.Add($name)
    }
}
if ($SelectedModules.Count -eq 0) {
    throw "At least one module must be selected."
}

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptRoot "..")).Path
$OriginalLocation = Get-Location
$OriginalRunId = [Environment]::GetEnvironmentVariable("BCD_TEST_RUN_ID", "Process")
$RunningOnWindows = $env:OS -eq "Windows_NT"
Set-Location $RepoRoot

$script:Results = New-Object System.Collections.Generic.List[object]
$script:HadFailure = $false
$script:StackStarted = $false
$script:RateLimitsOverridden = $false

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
$env:BCD_TEST_RUN_ID = $RunId
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

function Assert-CommandAvailable {
    param(
        [string]$Name,
        [string]$Message
    )
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw $Message
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

function Invoke-RateLimitRestoreStep {
    if (-not $script:RateLimitsOverridden) {
        return
    }
    Write-Host ""
    Write-Host "==> Restore backend default rate limits"
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        Restore-BackendDefaultRateLimits
        $timer.Stop()
        Add-Result -Name "Restore backend default rate limits" -Status "PASS" -Elapsed $timer.Elapsed
        Write-Host "<== PASS Restore backend default rate limits ($([math]::Round($timer.Elapsed.TotalSeconds, 1))s)"
    } catch {
        $timer.Stop()
        $script:HadFailure = $true
        Add-Result -Name "Restore backend default rate limits" -Status "FAIL" -Elapsed $timer.Elapsed -Message $_.Exception.Message
        Write-Host "<== FAIL Restore backend default rate limits ($([math]::Round($timer.Elapsed.TotalSeconds, 1))s)"
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
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
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
    $script:RateLimitsOverridden = $true
    try {
        Wait-DockerServiceHealthy "backend"
        Invoke-External "docker" @("compose", "restart", "frontend")
        Wait-DockerServiceHealthy "frontend"
    } catch {
        $setupError = $_
        try {
            Restore-BackendDefaultRateLimits
        } catch {
            Write-Warning "Failed to restore backend rate limits after setup failure: $($_.Exception.Message)"
        }
        throw $setupError
    }
}

function Restore-BackendDefaultRateLimits {
    Invoke-External "docker" @("compose", "up", "-d", "--no-deps", "--force-recreate", "backend")
    Wait-DockerServiceHealthy "backend"
    Invoke-External "docker" @("compose", "restart", "frontend")
    Wait-DockerServiceHealthy "frontend"
    $script:RateLimitsOverridden = $false
}

function Invoke-WithFunctionalTestRateLimits {
    param([scriptblock]$Action)

    Set-BackendFunctionalTestRateLimits
    $actionError = $null
    try {
        & $Action
    } catch {
        $actionError = $_
    }

    $restoreError = $null
    try {
        Restore-BackendDefaultRateLimits
    } catch {
        $restoreError = $_
    }

    if ($null -ne $actionError) {
        throw $actionError
    }
    if ($null -ne $restoreError) {
        throw $restoreError
    }
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

function Invoke-Pytest {
    param(
        [string]$Marker,
        [string]$Report,
        [string[]]$AdditionalArguments = @()
    )

    Invoke-InDirectory "backend-test" {
        $pytest = if ($RunningOnWindows) {
            Join-Path (Get-Location) ".venv/Scripts/pytest.exe"
        } else {
            Join-Path (Get-Location) ".venv/bin/pytest"
        }
        $arguments = @("-v", "-m", $Marker, "--junitxml=$Report") + $AdditionalArguments
        if (Test-Path $pytest -PathType Leaf) {
            Invoke-External $pytest $arguments
        } elseif (Get-Command "uv" -ErrorAction SilentlyContinue) {
            Invoke-WithEnv @{ UV_CACHE_DIR = "../.uv-cache" } {
                Invoke-External "uv" (@("run", "pytest") + $arguments)
            }
        } else {
            $python = if (Get-Command "python3" -ErrorAction SilentlyContinue) { "python3" } else { "python" }
            Invoke-External $python (@("-m", "pytest") + $arguments)
        }
    }
}

function Invoke-MobileGradle {
    param([string[]]$Arguments)

    Invoke-InDirectory "mobile" {
        if ($RunningOnWindows) {
            Invoke-External ".\gradlew.bat" $Arguments
        } else {
            Invoke-External "bash" (@("./gradlew") + $Arguments)
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

    if ($NeedsDocker) {
        Assert-CommandAvailable "docker" "docker was not found on PATH."
    }
    if ($RunWebDav -and -not (Get-Command "rclone" -ErrorAction SilentlyContinue)) {
        throw "rclone is required by the webdav module. Install it from https://rclone.org/install/ and ensure it is on PATH."
    }
    if ($RunMobileAndroid -and -not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
        throw "adb is required by the KMP Android device module and was not found on PATH."
    }
    if (-not $RunningOnWindows -and ($RunMobileUnit -or $RunMobileAndroid)) {
        Assert-CommandAvailable "bash" "bash is required by KMP modules on non-Windows hosts."
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
            Invoke-WithFunctionalTestRateLimits {
                Invoke-Pytest `
                    -Marker "not webdav and not office and not observability and not resilience and not security" `
                    -Report "../test-results/blackbox/junit.xml"
            }
        }
    }

    if ($RunSecurity) {
        Invoke-Step "Security acceptance tests" {
            Invoke-Pytest -Marker "security" -Report "../test-results/security/junit.xml"
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
            Invoke-WithFunctionalTestRateLimits {
                Invoke-InDirectory "frontend" {
                    Invoke-WithEnv @{ E2E_ONLYOFFICE = "0" } {
                        Invoke-External "node" @("scripts/run-playwright-docker.mjs", "--project=core")
                    }
                }
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
            Invoke-WithFunctionalTestRateLimits {
                Invoke-InDirectory "frontend" {
                    Invoke-External "pnpm" @("run", "test:e2e:tauri")
                }
            }
        }
    }

    if ($RunWebDav) {
        Invoke-Step "WebDAV protocol and rclone acceptance" {
            Invoke-Pytest -Marker "webdav" -Report "../test-results/webdav/junit.xml"
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
            Invoke-Pytest -Marker "observability" -Report "../test-results/observability/junit.xml"
        }
    }

    if ($RunResilience) {
        Invoke-Step "Resilience acceptance" {
            Invoke-Pytest -Marker "resilience" -Report "../test-results/resilience/junit.xml"
        }
    }

    if ($RunMobileUnit) {
        Invoke-Step "KMP host tests, Android builds, and iOS metadata" {
            Invoke-MobileGradle -Arguments @(
                ":shared:testAndroidHostTest",
                ":androidApp:assembleDebug",
                ":androidApp:assembleRelease",
                "-PenableIos=true",
                ":shared:compileIosMainKotlinMetadata"
            )
        }
    }
    if ($RunMobileAndroid) {
        Invoke-Step "KMP mobile Android device tests" {
            if ($AndroidSerial -match ":") {
                Invoke-External "adb" @("connect", $AndroidSerial)
            }
            Invoke-WithEnv @{ ANDROID_SERIAL = $AndroidSerial } {
                Invoke-MobileGradle -Arguments @(
                    ":androidApp:connectedDebugAndroidTest"
                )
            }
        }
    }
} finally {
    Invoke-RateLimitRestoreStep
    Invoke-CleanupStep
    Write-Host ""
    Write-Host "Validation summary"
    $script:Results | Format-Table -AutoSize
    [Environment]::SetEnvironmentVariable("BCD_TEST_RUN_ID", $OriginalRunId, "Process")
    Set-Location $OriginalLocation
}

if ($script:HadFailure) {
    exit 1
}
