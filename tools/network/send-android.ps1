param(
    [ValidateRange(1, 6)][int]$Repetitions = 3,
    [ValidateSet('upload','download')][string]$Direction = 'upload',
    [ValidateRange(0, 2147483600)][int]$SeedStart = 1140,
    [System.Net.IPAddress]$WifiHost,
    [switch]$AdbRoute,
    [ValidatePattern('^/data/local/tmp/[a-zA-Z0-9_-]+$')]
    [string]$DeviceDirectory = '/data/local/tmp/vtunnel-stream',
    [string]$OutputDirectory = 'local-validation/send-android'
)
$ErrorActionPreference = 'Stop'
if (!$AdbRoute -and !$WifiHost) { throw 'Specify WifiHost or AdbRoute' }
$targetAddress = if ($AdbRoute) { '127.0.0.1' } else { [string]$WifiHost }
$sshPort = if ($AdbRoute) { 19080 } else { 20080 }
$controlPort = if ($AdbRoute) { 19084 } else { 20084 }
$route = if ($AdbRoute) { 'ADB' } else { 'WiFi' }
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteOutput = "$DeviceDirectory/send-$runId.jsonl"
$localOutput = Join-Path $OutputDirectory "send-$runId.jsonl"
$transcript = Join-Path $OutputDirectory "phases-$runId.log"
try {
    for ($repetition = 0; $repetition -lt $Repetitions; $repetition++) {
        $seed = $SeedStart + $repetition
        $order = if ($repetition % 2 -eq 0) { @('before','after') } else { @('after','before') }
        foreach ($label in $order) {
            $dex = if ($label -eq 'before') { 'baseline.zip' } else { 'candidate.zip' }
            "unshaped $route direction=$Direction seed=$seed library=$label" | Tee-Object -FilePath $transcript -Append
            $command = "CLASSPATH=$DeviceDirectory/$dex app_process -Dvtunnel.probe.offsets=false -Dvtunnel.probe.controlPort=$controlPort /system/bin com.jcraft.jsch.TunnelNetworkProbe queue $label unshaped $seed $Direction 8 $targetAddress $sshPort $DeviceDirectory/client_key 4 0 0 $remoteOutput"
            & adb shell $command 2>&1 | Tee-Object -FilePath $transcript -Append
            if ($LASTEXITCODE -ne 0) { throw "Android library comparison failed: $label seed=$seed" }
            Start-Sleep -Seconds 2
        }
    }
} finally {
    & adb pull $remoteOutput $localOutput
    "Raw results: $localOutput"
}
$rows = @(Get-Content -LiteralPath $localOutput | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($rows.Count -ne 2*$Repetitions) { throw 'Incomplete Android comparison' }
$keys = @($rows | ForEach-Object { "$($_.label)/$($_.seed)" } | Sort-Object -Unique)
if ($keys.Count -ne $rows.Count) { throw 'Duplicate Android comparison rows' }
