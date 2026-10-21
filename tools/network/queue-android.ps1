param(
    [ValidateRange(1, 6)][int]$Repetitions = 3,
    [ValidateRange(1, 30)][int]$Seconds = 8,
    [ValidateRange(0, 2147483600)][int]$SeedStart = 860,
    [ValidateSet('unshaped', 'mobile-jitter')][string]$Profile = 'mobile-jitter',
    [ValidatePattern('^/data/local/tmp/[a-zA-Z0-9_-]+$')]
    [string]$DeviceDirectory = '/data/local/tmp/vtunnel-queue',
    [string]$OutputDirectory = 'local-validation/queue-network'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteOutput = "$DeviceDirectory/queue-$runId.jsonl"
$localOutput = Join-Path $OutputDirectory "queue-$runId.jsonl"
$transcript = Join-Path $OutputDirectory "phases-$runId.log"
$cases = @(
    @{ Label='one_free'; Channels=1; Rate=0 },
    @{ Label='four_free'; Channels=4; Rate=0 },
    @{ Label='one_capped'; Channels=1; Rate=1500 },
    @{ Label='four_capped'; Channels=4; Rate=1500 }
)
try {
    for ($repetition = 0; $repetition -lt $Repetitions; $repetition++) {
        $seed = $SeedStart + $repetition
        $order = if ($repetition % 2 -eq 0) { @(0,1,2,3) } else { @(3,2,1,0) }
        foreach ($index in $order) {
            $case = $cases[$index]
            "$Profile seed=$seed case=$($case.Label)" | Tee-Object -FilePath $transcript -Append
            $command = "CLASSPATH=$DeviceDirectory/probe.zip app_process /system/bin com.jcraft.jsch.TunnelNetworkProbe queue $($case.Label) $Profile $seed upload $Seconds 127.0.0.1 19080 $DeviceDirectory/client_key $($case.Channels) $($case.Rate) $remoteOutput"
            & adb shell $command 2>&1 | Tee-Object -FilePath $transcript -Append
            if ($LASTEXITCODE -ne 0) { throw "Queue probe failed: $($case.Label) seed=$seed" }
            Start-Sleep -Seconds 2
        }
    }
} finally {
    & adb pull $remoteOutput $localOutput
    "Raw results: $localOutput"
}
$rows = @(Get-Content -LiteralPath $localOutput | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($rows.Count -ne 4 * $Repetitions) { throw 'Incomplete queue comparison' }
$keys = @($rows | ForEach-Object { "$($_.label)/$($_.seed)" } | Sort-Object -Unique)
if ($keys.Count -ne $rows.Count) { throw 'Duplicate queue records' }
