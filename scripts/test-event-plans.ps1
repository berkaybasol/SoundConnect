param(
    # Offline by default. Opt in only when the local Gradle dependency cache needs filling.
    [switch]$AllowDependencyDownloads,
    [switch]$IncludeConsumerRegression
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$verificationRoot = Join-Path $backendRoot 'build/event-plan-verification'
$initScript = Join-Path $PSScriptRoot 'gradle/event-plan-verification.gradle'
$logPath = Join-Path $verificationRoot 'test.log'
New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null

$gradleArguments = @(
    '--no-daemon', '--max-workers=2',
    '--project-cache-dir', (Join-Path $verificationRoot 'project-cache'),
    '-I', $initScript,
    'test'
)
$testPatterns = if ($IncludeConsumerRegression) {
    @('com.berkayb.soundconnect.modules.event.*', '*Calendar*', '*VenueWeeklyEventPosterTest',
      '*MediaAssetReference*', '*EventComment*', '*EventIntent*', '*EventPost*')
} else {
    @('*EventPlan*', '*EventCopySource*', '*MediaAssetReferenceGuardTest')
}
foreach ($testPattern in $testPatterns) { $gradleArguments += @('--tests', $testPattern) }
if (-not $AllowDependencyDownloads) { $gradleArguments = @('--offline') + $gradleArguments }

Write-Host 'Running event-plan tests with the test-only Spring configuration and disposable Testcontainers.'
Write-Host 'This script does not start, stop, reset, or migrate the application stack.'
Write-Host "Verification log: $logPath"

Push-Location -LiteralPath $backendRoot
try {
    $verificationStarted = [DateTime]::UtcNow
    & (Join-Path $backendRoot 'gradlew.bat') @gradleArguments *> $logPath
    $verificationExit = $LASTEXITCODE
    Get-Content -LiteralPath $logPath -Tail 100
    $xmlDirectory = Join-Path $verificationRoot 'output/test-results/test'
    if (Test-Path -LiteralPath $xmlDirectory) {
        $totals = @{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
        foreach ($reportFile in Get-ChildItem -LiteralPath $xmlDirectory -Filter 'TEST-*.xml' |
                Where-Object { $_.LastWriteTimeUtc -ge $verificationStarted }) {
            [xml]$report = Get-Content -LiteralPath $reportFile.FullName
            $totals.Tests += [int]$report.testsuite.tests
            $totals.Failures += [int]$report.testsuite.failures
            $totals.Errors += [int]$report.testsuite.errors
            $totals.Skipped += [int]$report.testsuite.skipped
        }
        Write-Host "JUnit: $($totals.Tests) tests, $($totals.Failures) failures, $($totals.Errors) errors, $($totals.Skipped) skipped."
    }
    exit $verificationExit
} finally {
    Pop-Location
}
