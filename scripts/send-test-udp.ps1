param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 50000,
    [string]$Message = "target=灯光;action=开灯;status=开启 60%",
    [string]$SharedSecret = $env:BCI_UDP_SHARED_SECRET
)

. "$PSScriptRoot\udp-protocol.ps1"

$client = [System.Net.Sockets.UdpClient]::new()
try {
    $frame = New-BciUdpFrame -Sequence 1 -Type 'COMMAND' -Payload $Message -SharedSecret $SharedSecret
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($frame)
    [void]$client.Send($bytes, $bytes.Length, $HostName, $Port)
    Write-Host "Sent authenticated BCI2 UDP test command to ${HostName}:$Port -> $Message"
}
finally {
    $client.Close()
}
