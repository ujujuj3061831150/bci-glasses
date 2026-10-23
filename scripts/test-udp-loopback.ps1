param(
    [int]$Port = 50000,
    [string]$Message = "target=灯光;action=开灯;status=开启 60%",
    [string]$SharedSecret = "0123456789abcdef"
)

. "$PSScriptRoot\udp-protocol.ps1"

$receiver = [System.Net.Sockets.UdpClient]::new($Port)
$sender = [System.Net.Sockets.UdpClient]::new()
try {
    $receiveTask = $receiver.ReceiveAsync()
    $frame = New-BciUdpFrame -Sequence 1 -Type 'COMMAND' -Payload $Message -SharedSecret $SharedSecret
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($frame)
    [void]$sender.Send($bytes, $bytes.Length, "127.0.0.1", $Port)

    if (-not $receiveTask.AsyncWaitHandle.WaitOne(3000)) {
        throw "Timed out waiting for UDP loopback message on port $Port"
    }

    $result = $receiveTask.GetAwaiter().GetResult()
    $received = [System.Text.Encoding]::UTF8.GetString($result.Buffer)
    $parsed = Read-BciUdpFrame -Frame $received -SharedSecret $SharedSecret
    if ($parsed.Payload -ne $Message -or $parsed.Type -ne 'COMMAND') {
        throw "Unexpected UDP frame. Expected COMMAND '$Message', got $($parsed.Type) '$($parsed.Payload)'"
    }

    $tampered = $received.Replace('COMMAND', 'COMMANDX')
    try {
        [void](Read-BciUdpFrame -Frame $tampered -SharedSecret $SharedSecret)
        throw "Tampered BCI2 frame was unexpectedly accepted"
    }
    catch {
        if ($_.Exception.Message -eq 'Tampered BCI2 frame was unexpectedly accepted') { throw }
    }

    Write-Host "UDP BCI2 HMAC loopback OK -> seq=$($parsed.Sequence) type=$($parsed.Type) payload=$($parsed.Payload)"
}
finally {
    $sender.Close()
    $receiver.Close()
}
