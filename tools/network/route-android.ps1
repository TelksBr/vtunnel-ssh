param(
    [ValidateRange(1, 6)][int]$Repetitions = 3,
    [ValidateRange(1, 30)][int]$Seconds = 8,
    [ValidateRange(0, 2147483600)][int]$SeedStart = 940,
    [Parameter(Mandatory=$true)][System.Net.IPAddress]$WifiHost,
    [ValidatePattern('^/data/local/tmp/[a-zA-Z0-9_-]+$')]
    [string]$DeviceDirectory = '/data/local/tmp/vtunnel-queue',
    [string]$OutputDirectory = 'local-validation/queue-route-network'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteOutput = "$DeviceDirectory/route-$runId.jsonl"
$localOutput = Join-Path $OutputDirectory "route-$runId.jsonl"
$transcript = Join-Path $OutputDirectory "phases-$runId.log"
try {
    for ($repetition = 0; $repetition -lt $Repetitions; $repetition++) {
        $seed = $SeedStart + $repetition
        $order = if ($repetition % 2 -eq 0) { @('adb','wifi') } else { @('wifi','adb') }
        foreach ($route in $order) {
            $targetAddress = if ($route -eq 'wifi') { [string]$WifiHost } else { '127.0.0.1' }
            $sshPort = if ($route -eq 'wifi') { 20080 } else { 19080 }
            $controlPort = if ($route -eq 'wifi') { 20084 } else { 19084 }
            "mobile-jitter seed=$seed route=$route" | Tee-Object -FilePath $transcript -Append
            $command = "CLASSPATH=$DeviceDirectory/route-probe.zip app_process -Dvtunnel.probe.controlPort=$controlPort /system/bin com.jcraft.jsch.TunnelNetworkProbe queue $route mobile-jitter $seed upload $Seconds $targetAddress $sshPort $DeviceDirectory/client_key 4 0 0 $remoteOutput"
            & adb shell $command 2>&1 | Tee-Object -FilePath $transcript -Append
            if ($LASTEXITCODE -ne 0) { throw "Route probe failed: $route seed=$seed" }
            Start-Sleep -Seconds 2
        }
    }
} finally {
    & adb pull $remoteOutput $localOutput
    "Raw results: $localOutput"
}
$rows = @(Get-Content -LiteralPath $localOutput | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($rows.Count -ne 2 * $Repetitions) { throw 'Incomplete route comparison' }
$keys = @($rows | ForEach-Object { "$($_.label)/$($_.seed)" } | Sort-Object -Unique)
if ($keys.Count -ne $rows.Count) { throw 'Duplicate route records' }
