param(
    [ValidateRange(1, 6)][int]$Repetitions = 3,
    [ValidateRange(1, 30)][int]$Seconds = 8,
    [ValidateRange(0, 2147483600)][int]$SeedStart = 900,
    [ValidateSet('unshaped', 'mobile-jitter')][string[]]$Profiles = @('mobile-jitter','unshaped'),
    [ValidatePattern('^/data/local/tmp/[a-zA-Z0-9_-]+$')]
    [string]$DeviceDirectory = '/data/local/tmp/vtunnel-queue',
    [string]$OutputDirectory = 'local-validation/relay-buffer-network'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteOutput = "$DeviceDirectory/relay-$runId.jsonl"
$localOutput = Join-Path $OutputDirectory "relay-$runId.jsonl"
$transcript = Join-Path $OutputDirectory "phases-$runId.log"
try {
    foreach ($profile in $Profiles) {
        for ($repetition = 0; $repetition -lt $Repetitions; $repetition++) {
            $seed = $SeedStart + $repetition
            $order = if ($repetition % 2 -eq 0) { @(0,32768) } else { @(32768,0) }
            foreach ($buffer in $order) {
                $label = if ($buffer -eq 0) { 'relay_default' } else { 'relay_32k' }
                "$profile seed=$seed case=$label" | Tee-Object -FilePath $transcript -Append
                $command = "CLASSPATH=$DeviceDirectory/buffer-probe.zip app_process /system/bin com.jcraft.jsch.TunnelNetworkProbe queue $label $profile $seed upload $Seconds 127.0.0.1 19080 $DeviceDirectory/client_key 4 0 $buffer $remoteOutput"
                & adb shell $command 2>&1 | Tee-Object -FilePath $transcript -Append
                if ($LASTEXITCODE -ne 0) { throw "Relay probe failed: $label seed=$seed" }
                Start-Sleep -Seconds 2
            }
        }
    }
} finally {
    & adb pull $remoteOutput $localOutput
    "Raw results: $localOutput"
}
$rows = @(Get-Content -LiteralPath $localOutput | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($rows.Count -ne 2 * $Repetitions * $Profiles.Count) { throw 'Incomplete relay comparison' }
$keys = @($rows | ForEach-Object { "$($_.profile)/$($_.label)/$($_.seed)" } | Sort-Object -Unique)
if ($keys.Count -ne $rows.Count) { throw 'Duplicate relay records' }
