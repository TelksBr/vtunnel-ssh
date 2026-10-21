param(
    [ValidateRange(1, 6)][int]$Repetitions = 3,
    [ValidateRange(1, 30)][int]$Seconds = 8,
    [System.Net.IPAddress]$WifiHost,
    [ValidatePattern('^/data/local/tmp/[a-zA-Z0-9_-]+$')]
    [string]$DeviceDirectory = '/data/local/tmp/vtunnel-stream',
    [string]$OutputDirectory = 'local-validation/stream-network'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteOutput = "$DeviceDirectory/stream-$runId.jsonl"
$localOutput = Join-Path $OutputDirectory "stream-$runId.jsonl"
$transcript = Join-Path $OutputDirectory "phases-$runId.log"
$mobile = @(
    @{ Label='one_adb'; Channels=1; Wifi=$false; Track=$true },
    @{ Label='four_adb'; Channels=4; Wifi=$false; Track=$true }
)
if ($WifiHost) { $mobile += @{ Label='four_wifi'; Channels=4; Wifi=$true; Track=$true } }
$overhead = @(
    @{ Label='trace_off'; Channels=4; Wifi=$false; Track=$false },
    @{ Label='trace_on'; Channels=4; Wifi=$false; Track=$true }
)
try {
    foreach ($profile in @('mobile-jitter','unshaped')) {
        $cases = if ($profile -eq 'unshaped') { $overhead } else { $mobile }
        for ($repetition = 0; $repetition -lt $Repetitions; $repetition++) {
            $seed = $(if ($profile -eq 'unshaped') { 1020 } else { 1000 }) + $repetition
            $order = @(0..($cases.Count-1))
            if ($repetition % 2 -eq 1) { [array]::Reverse($order) }
            foreach ($index in $order) {
                $case = $cases[$index]
                $targetAddress = if ($case.Wifi) { [string]$WifiHost } else { '127.0.0.1' }
                $sshPort = if ($case.Wifi) { 20080 } else { 19080 }
                $controlPort = if ($case.Wifi) { 20084 } else { 19084 }
                $track = $case.Track.ToString().ToLowerInvariant()
                "$profile seed=$seed case=$($case.Label)" | Tee-Object -FilePath $transcript -Append
                $command = "CLASSPATH=$DeviceDirectory/probe.zip app_process -Dvtunnel.probe.offsets=$track -Dvtunnel.probe.controlPort=$controlPort /system/bin com.jcraft.jsch.TunnelNetworkProbe queue $($case.Label) $profile $seed upload $Seconds $targetAddress $sshPort $DeviceDirectory/client_key $($case.Channels) 0 0 $remoteOutput"
                & adb shell $command 2>&1 | Tee-Object -FilePath $transcript -Append
                if ($LASTEXITCODE -ne 0) { throw "Stream probe failed: $($case.Label) seed=$seed" }
                Start-Sleep -Seconds 2
            }
        }
    }
} finally {
    & adb pull $remoteOutput $localOutput
    "Raw results: $localOutput"
}
$rows = @(Get-Content -LiteralPath $localOutput | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($rows.Count -ne ($mobile.Count + 2) * $Repetitions) { throw 'Incomplete stream comparison' }
$keys = @($rows | ForEach-Object { "$($_.profile)/$($_.label)/$($_.seed)" } | Sort-Object -Unique)
if ($keys.Count -ne $rows.Count) { throw 'Duplicate stream records' }
