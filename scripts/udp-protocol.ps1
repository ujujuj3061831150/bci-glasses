function Assert-BciSharedSecret {
    param([Parameter(Mandatory)][string]$SharedSecret)

    $length = [System.Text.Encoding]::UTF8.GetByteCount($SharedSecret)
    if ($length -lt 16 -or $length -gt 128) {
        throw "BCI2 shared secret must contain 16-128 UTF-8 bytes"
    }
}

function Get-BciHmacSha256Hex {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$SharedSecret
    )

    Assert-BciSharedSecret -SharedSecret $SharedSecret
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($SharedSecret)
    )
    try {
        $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))
        return ($hash | ForEach-Object { $_.ToString('X2') }) -join ''
    }
    finally {
        $hmac.Dispose()
    }
}

function Test-BciConstantTimeTextEquals {
    param(
        [Parameter(Mandatory)][string]$Left,
        [Parameter(Mandatory)][string]$Right
    )

    $leftBytes = [System.Text.Encoding]::ASCII.GetBytes($Left.ToUpperInvariant())
    $rightBytes = [System.Text.Encoding]::ASCII.GetBytes($Right.ToUpperInvariant())
    if ($leftBytes.Length -ne $rightBytes.Length) { return $false }
    $difference = 0
    for ($index = 0; $index -lt $leftBytes.Length; $index++) {
        $difference = $difference -bor ($leftBytes[$index] -bxor $rightBytes[$index])
    }
    return $difference -eq 0
}

function New-BciUdpFrame {
    param(
        [Parameter(Mandatory)][long]$Sequence,
        [Parameter(Mandatory)][string]$Type,
        [Parameter(Mandatory)][string]$Payload,
        [Parameter(Mandatory)][string]$SharedSecret,
        [long]$Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    )

    if ($Sequence -lt 0) { throw "BCI2 sequence must be non-negative" }
    if ($Type -notmatch '^[A-Z][A-Z0-9_]{0,31}$') { throw "Invalid BCI2 frame type" }
    $encoded = ([Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Payload))).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $body = "BCI2|$Sequence|$Timestamp|$Type|$encoded"
    return "$body|$(Get-BciHmacSha256Hex -Text $body -SharedSecret $SharedSecret)"
}

function Read-BciUdpFrame {
    param(
        [Parameter(Mandatory)][string]$Frame,
        [Parameter(Mandatory)][string]$SharedSecret
    )

    $parts = $Frame.Split('|')
    if ($parts.Count -ne 6 -or $parts[0] -ne 'BCI2') {
        throw "Invalid BCI2 UDP frame structure"
    }
    $body = ($parts[0..4] -join '|')
    $expected = Get-BciHmacSha256Hex -Text $body -SharedSecret $SharedSecret
    if (-not (Test-BciConstantTimeTextEquals -Left $parts[5] -Right $expected)) {
        throw "HMAC authentication failed"
    }
    $base64 = $parts[4].Replace('-', '+').Replace('_', '/')
    while (($base64.Length % 4) -ne 0) { $base64 += '=' }
    return [PSCustomObject]@{
        Version = $parts[0]
        Sequence = [long]$parts[1]
        Timestamp = [long]$parts[2]
        Type = $parts[3]
        Payload = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($base64))
        HmacSha256 = $parts[5]
    }
}
