#Requires -Version 5.1
<#
.SYNOPSIS
    Run the full mainline pipeline for a single prefecture (activity + trip + validation).
.DESCRIPTION
    Runs activity generation, WebAPI trip+trajectory generation, and validation
    sequentially for one prefecture. Writes output and logs to a staging directory.
.PARAMETER PrefCode
    Prefecture code (1-47).
.PARAMETER MFactor
    Sampling factor. Default: 200.
.PARAMETER OutputRoot
    Base output directory. Default: C:\Pseudo-PFLOW\output\pref_<N>
.PARAMETER ConfigFile
    Optional external config file path.
.PARAMETER NumThreads
    Max concurrent threads. Default: 4.
.PARAMETER BatchSize
    Persons per processing batch. Default: 5000.
    Lower values reduce memory; higher values improve throughput.
.PARAMETER RouteCacheMaxEntries
    Max route cache entries. Default: 5000. Set 0 to disable cache.
.EXAMPLE
    .\run_pref.ps1 13 -MFactor 1 -OutputRoot C:\Pseudo-PFLOW\output\pref_13
    .\run_pref.ps1 22
    .\run_pref.ps1 13 -NumThreads 2 -BatchSize 5000 -RouteCacheMaxEntries 0
#>
param(
    [Parameter(Mandatory=$true, Position=0)]
    [ValidateRange(1, 47)]
    [int]$PrefCode,

    [Parameter(Position=1)]
    [int]$MFactor = 200,

    [string]$OutputRoot,

    [string]$ConfigFile,

    [int]$NumThreads = 4,

    [int]$BatchSize = 5000,

    [int]$RouteCacheMaxEntries = 5000
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectDir = (Resolve-Path "$ScriptDir\..\..").Path

if (-not $OutputRoot) {
    $OutputRoot = "C:\Pseudo-PFLOW\output\pref_$PrefCode"
}

# Check API credentials
if (-not $env:PFLOW_API_USER -or -not $env:PFLOW_API_PASS) {
    Write-Error @"
PFLOW_API_USER and PFLOW_API_PASS environment variables must be set.

Set them for the current session:
  `$env:PFLOW_API_USER = 'your_user'
  `$env:PFLOW_API_PASS = 'your_pass'

Or set them permanently via System Properties > Environment Variables.
"@
    exit 1
}

$LogDir = Join-Path $OutputRoot "logs"
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

# Create staging config override pointing output to our staging dir
$StagingConfig = Join-Path $env:TEMP "staging_config_$PrefCode.properties"
"outputDir=$($OutputRoot.Replace('\','/'))/`n" | Set-Content -Path $StagingConfig -NoNewline

# If user passed a ConfigFile, chain it: staging config first, then user config overlays
$EffectiveConfig = $StagingConfig
if ($ConfigFile) {
    if (-not (Test-Path $ConfigFile)) {
        Write-Error "Config file not found: $ConfigFile"
        exit 1
    }
    # Merge: staging + user config into a temp file
    $MergedConfig = Join-Path $env:TEMP "staging_merged_$PrefCode.properties"
    $Content = (Get-Content $StagingConfig -Raw) + "`n" + (Get-Content $ConfigFile -Raw)
    $Content | Set-Content -Path $MergedConfig -NoNewline
    $EffectiveConfig = $MergedConfig
}

# JVM heap defaults — set MAVEN_OPTS if not already configured
if (-not $env:MAVEN_OPTS) {
    $env:MAVEN_OPTS = "-Xms2g -Xmx20g -XX:+UseG1GC"
}

# Build JVM flags (always passed — defaults are production-safe)
$JvmFlags = @(
    "-DnumThreads=$NumThreads",
    "-DbatchSize=$BatchSize",
    "-DrouteCache.maxEntries=$RouteCacheMaxEntries"
)

$SamplePct = [math]::Round(100 / $MFactor, 1)
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " Staging run: prefecture $PrefCode" -ForegroundColor Cyan
Write-Host " mfactor=$MFactor ($SamplePct% sample)" -ForegroundColor Cyan
Write-Host " numThreads=$NumThreads  batchSize=$BatchSize  cacheMax=$RouteCacheMaxEntries" -ForegroundColor Cyan
Write-Host " MAVEN_OPTS=$env:MAVEN_OPTS" -ForegroundColor DarkGray
Write-Host " Output: $OutputRoot" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""

$TotalStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

# Step 1: Activity generation
Write-Host "[$PrefCode] Step 1/3: Activity generation..." -ForegroundColor Yellow
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Push-Location $ProjectDir
try {
    $ErrorActionPreference = "Continue"
    & mvn -q exec:java @JvmFlags `
        "-Dexec.mainClass=pseudo.gen.ActivityGenerator" `
        "-Dexec.args=$PrefCode $MFactor" `
        "-Dconfig.file=$EffectiveConfig" 2>&1 | Tee-Object -FilePath (Join-Path $LogDir "activity.log")
    $ErrorActionPreference = "Stop"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "[$PrefCode] Activity generation failed (exit code $LASTEXITCODE)"
        exit $LASTEXITCODE
    }
} finally {
    $ErrorActionPreference = "Stop"
    Pop-Location
}

$Stopwatch.Stop()
$ActFiles = (Get-ChildItem -Path (Join-Path $OutputRoot "activity\$PrefCode") -Filter "*.csv" -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Host "[$PrefCode] Activity done: $ActFiles files, $([int]$Stopwatch.Elapsed.TotalSeconds)s" -ForegroundColor Green
Write-Host ""

# Step 2: Trip + trajectory generation (WebAPI)
Write-Host "[$PrefCode] Step 2/3: Trip + trajectory generation (WebAPI)..." -ForegroundColor Yellow
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Push-Location $ProjectDir
try {
    $ErrorActionPreference = "Continue"
    & mvn -q exec:java @JvmFlags `
        "-Dexec.mainClass=pseudo.gen.TripGenerator_WebAPI_refactor" `
        "-Dexec.args=$PrefCode $MFactor" `
        "-Dconfig.file=$EffectiveConfig" 2>&1 | Tee-Object -FilePath (Join-Path $LogDir "trip.log")
    $ErrorActionPreference = "Stop"

    if ($LASTEXITCODE -ne 0) {
        Write-Error "[$PrefCode] Trip generation failed (exit code $LASTEXITCODE)"
        exit $LASTEXITCODE
    }
} finally {
    $ErrorActionPreference = "Stop"
    Pop-Location
}

$Stopwatch.Stop()
$TripFiles = (Get-ChildItem -Path (Join-Path $OutputRoot "trip\$PrefCode") -Filter "*.csv" -ErrorAction SilentlyContinue | Measure-Object).Count
$TrajFiles = (Get-ChildItem -Path (Join-Path $OutputRoot "trajectory\$PrefCode") -Filter "*.csv" -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Host "[$PrefCode] Trip+trajectory done: $TripFiles trip files, $TrajFiles trajectory files, $([int]$Stopwatch.Elapsed.TotalSeconds)s" -ForegroundColor Green
Write-Host ""

# Step 3: Validation
Write-Host "[$PrefCode] Step 3/3: Validation..." -ForegroundColor Yellow
& "$ScriptDir\run_validate.ps1" $PrefCode $OutputRoot
Write-Host ""

$TotalStopwatch.Stop()
Write-Host "=============================================" -ForegroundColor Green
Write-Host " Prefecture $PrefCode complete" -ForegroundColor Green
Write-Host " Total time: $([int]$TotalStopwatch.Elapsed.TotalMinutes)m $($TotalStopwatch.Elapsed.Seconds)s" -ForegroundColor Green
Write-Host " Output: $OutputRoot" -ForegroundColor Green
Write-Host " Logs: $LogDir" -ForegroundColor Green
Write-Host " Summary: $(Join-Path $OutputRoot "validation\$PrefCode\summary.md")" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
