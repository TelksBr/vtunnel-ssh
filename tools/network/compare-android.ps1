param(
    [ValidateRange(1, 10)][int]$Repetitions = 3,
    [ValidateRange(1, 60)][int]$Seconds = 6,
    [ValidateRange(0, 30)][int]$CooldownSeconds = 0,
    [ValidateRange(0, 2147483600)][int]$SeedStart = 526,
    [ValidateSet('unshaped', 'mobile-jitter', 'shallow', 'deep')]
    [string[]]$Profiles = @('unshaped', 'mobile-jitter'),
    [ValidateSet('idle', 'download', 'upload')]
    [string[]]$Directions = @('idle', 'download', 'upload'),
    [ValidatePattern('^/data/local/tmp/[a-zA-Z0-9_-]+$')]
    [string]$DeviceDirectory = '/data/local/tmp/vtunnel-lib-vpn2',
    [string]$OutputDirectory = 'local-validation/network',
    [ValidateSet('network', 'network-diag')][string]$BaselineMode = 'network',
    [ValidateSet('network', 'network-diag')][string]$CandidateMode = 'network'
)

# Prerequisites: lab.py up + start-control, ADB reverse 19080/19084, and
# baseline.zip/candidate.zip/client_key in DeviceDirectory. See README.md.
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteOutput = "$DeviceDirectory/network-$runId.jsonl"
$localOutput = Join-Path $OutputDirectory "network-$runId.jsonl"
$transcript = Join-Path $OutputDirectory "phases-$runId.log"
try {
    foreach ($profile in $Profiles) {
        for ($repetition = 0; $repetition -lt $Repetitions; $repetition++) {
            $seed = $SeedStart + $repetition
            $order = if ($repetition % 2 -eq 0) { @('vpn1', 'vpn2') } else { @('vpn2', 'vpn1') }
            foreach ($label in $order) {
                $dex = if ($label -eq 'vpn1') { 'baseline.zip' } else { 'candidate.zip' }
                $mode = if ($label -eq 'vpn1') { $BaselineMode } else { $CandidateMode }
                foreach ($direction in $Directions) {
                    $duration = if ($direction -eq 'idle') { 2 } else { $Seconds }
                    "${profile}: seed=$seed library=$label direction=$direction" | Tee-Object -FilePath $transcript -Append
                    $command = "CLASSPATH=$DeviceDirectory/$dex app_process /system/bin com.jcraft.jsch.TunnelNetworkProbe $mode $label $profile $seed $direction $duration 127.0.0.1 19080 $DeviceDirectory/client_key $remoteOutput"
                    & adb shell $command 2>&1 | Tee-Object -FilePath $transcript -Append
                    if ($LASTEXITCODE -ne 0) { throw "Probe failed: $label $profile $direction seed=$seed" }
                    if ($CooldownSeconds -gt 0) { Start-Sleep -Seconds $CooldownSeconds }
                }
            }
        }
    }
} finally {
    & adb pull $remoteOutput $localOutput
    "Raw results: $localOutput"
}

$rows = @(Get-Content -LiteralPath $localOutput | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
$expected = $Profiles.Count * $Directions.Count * $Repetitions * 2
if ($rows.Count -ne $expected) {
    throw "Incomplete comparison: expected $expected executions, found $($rows.Count)"
}
$keys = @($rows | ForEach-Object { "$($_.profile)/$($_.label)/$($_.seed)/$($_.direction)" } | Sort-Object -Unique)
if ($keys.Count -ne $expected) { throw 'Duplicate phase records in comparison' }
