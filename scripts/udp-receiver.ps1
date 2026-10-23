param(
    [int]$Port = 50000,
    [string]$SharedSecret = $env:BCI_UDP_SHARED_SECRET,
    [int]$MaxClockSkewMillis = 10000
)

. "$PSScriptRoot\udp-protocol.ps1"

$client = [System.Net.Sockets.UdpClient]::new($Port)
$lastFrameBySender = @{}
try {
    Write-Host "Listening for UDP commands on 0.0.0.0:$Port"
    Write-Host "Press Ctrl+C to stop."
    while ($true) {
        $remote = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
        $bytes = $client.Receive([ref]$remote)
        $message = [System.Text.Encoding]::UTF8.GetString($bytes)
        $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"
        try {
            $frame = Read-BciUdpFrame -Frame $message -SharedSecret $SharedSecret
            $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            if ([Math]::Abs($now - $frame.Timestamp) -gt $MaxClockSkewMillis) {
                throw "Frame timestamp is outside the allowed clock-skew window"
            }
            $senderKey = $remote.Address.ToString()
            $previous = $lastFrameBySender[$senderKey]
            if ($null -ne $previous -and (
                $frame.Timestamp -lt $previous.Timestamp -or
                ($frame.Timestamp -eq $previous.Timestamp -and $frame.Sequence -le $previous.Sequence)
            )) {
                throw "Replay or out-of-order frame rejected"
            }
            $lastFrameBySender[$senderKey] = [PSCustomObject]@{
                Timestamp = $frame.Timestamp
                Sequence = $frame.Sequence
            }
            Write-Host "[$timestamp] $($remote.Address):$($remote.Port) seq=$($frame.Sequence) type=$($frame.Type) payload=$($frame.Payload)"
        }
        catch {
            Write-Warning "Rejected UDP frame from $($remote.Address):$($remote.Port): $($_.Exception.Message)"
        }
    }
}
finally {
    $client.Close()
}
