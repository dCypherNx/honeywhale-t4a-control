param(
  [Parameter(Mandatory = $true, ParameterSetName = 'Encrypt')]
  [switch]$Encrypt,

  [Parameter(Mandatory = $true, ParameterSetName = 'Decrypt')]
  [switch]$Decrypt,

  [string]$Password = $env:TUYA_BUILD_FILES_PASSWORD,
  [string]$BundlePath = 'ci/tuya-build-inputs.enc'
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Password)) {
  throw 'TUYA_BUILD_FILES_PASSWORD is required.'
}

$magic = [Text.Encoding]::ASCII.GetBytes('T4ACI01')
$iterations = 200000

function New-Key([string]$Secret, [byte[]]$Salt) {
  $kdf = New-Object Security.Cryptography.Rfc2898DeriveBytes(
    $Secret,
    $Salt,
    $iterations,
    [Security.Cryptography.HashAlgorithmName]::SHA256
  )
  try {
    return $kdf.GetBytes(32)
  }
  finally {
    $kdf.Dispose()
  }
}

function Protect-Bytes([byte[]]$Plain, [string]$Secret) {
  $salt = New-Object byte[] 16
  $iv = New-Object byte[] 16
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($salt)
    $rng.GetBytes($iv)
  }
  finally {
    $rng.Dispose()
  }

  $key = New-Key $Secret $salt
  $aes = [Security.Cryptography.Aes]::Create()
  try {
    $aes.KeySize = 256
    $aes.BlockSize = 128
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $aes.Key = $key
    $aes.IV = $iv
    $encryptor = $aes.CreateEncryptor()
    try {
      $cipher = $encryptor.TransformFinalBlock($Plain, 0, $Plain.Length)
    }
    finally {
      $encryptor.Dispose()
    }
  }
  finally {
    $aes.Dispose()
  }

  $output = New-Object byte[] ($magic.Length + $salt.Length + $iv.Length + $cipher.Length)
  [Array]::Copy($magic, 0, $output, 0, $magic.Length)
  [Array]::Copy($salt, 0, $output, $magic.Length, $salt.Length)
  [Array]::Copy($iv, 0, $output, $magic.Length + $salt.Length, $iv.Length)
  [Array]::Copy($cipher, 0, $output, $magic.Length + $salt.Length + $iv.Length, $cipher.Length)
  return $output
}

function Unprotect-Bytes([byte[]]$Payload, [string]$Secret) {
  $headerLength = $magic.Length + 16 + 16
  if ($Payload.Length -le $headerLength) {
    throw 'Invalid Tuya build bundle.'
  }

  for ($i = 0; $i -lt $magic.Length; $i++) {
    if ($Payload[$i] -ne $magic[$i]) {
      throw 'Invalid Tuya build bundle header.'
    }
  }

  $salt = New-Object byte[] 16
  $iv = New-Object byte[] 16
  [Array]::Copy($Payload, $magic.Length, $salt, 0, 16)
  [Array]::Copy($Payload, $magic.Length + 16, $iv, 0, 16)

  $cipherLength = $Payload.Length - $headerLength
  $cipher = New-Object byte[] $cipherLength
  [Array]::Copy($Payload, $headerLength, $cipher, 0, $cipherLength)

  $key = New-Key $Secret $salt
  $aes = [Security.Cryptography.Aes]::Create()
  try {
    $aes.KeySize = 256
    $aes.BlockSize = 128
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $aes.Key = $key
    $aes.IV = $iv
    $decryptor = $aes.CreateDecryptor()
    try {
      return $decryptor.TransformFinalBlock($cipher, 0, $cipher.Length)
    }
    catch {
      throw 'Unable to decrypt Tuya build bundle. Check TUYA_BUILD_FILES_PASSWORD.'
    }
    finally {
      $decryptor.Dispose()
    }
  }
  finally {
    $aes.Dispose()
  }
}

if ($Encrypt) {
  $aar = 'app/libs/security-algorithm-1.0.0-beta.aar'
  $bmp = 'app/src/main/assets/t_s.bmp'
  if (-not (Test-Path -LiteralPath $aar -PathType Leaf)) { throw "Missing $aar" }
  if (-not (Test-Path -LiteralPath $bmp -PathType Leaf)) { throw "Missing $bmp" }

  $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('t4a-tuya-' + [Guid]::NewGuid().ToString('N'))
  $zipPath = "$tempRoot.zip"
  New-Item -ItemType Directory -Path $tempRoot | Out-Null
  try {
    Copy-Item -LiteralPath $aar -Destination (Join-Path $tempRoot 'security-algorithm-1.0.0-beta.aar')
    Copy-Item -LiteralPath $bmp -Destination (Join-Path $tempRoot 't_s.bmp')
    Compress-Archive -Path (Join-Path $tempRoot '*') -DestinationPath $zipPath -Force
    $plain = [IO.File]::ReadAllBytes($zipPath)
    $encrypted = Protect-Bytes $plain $Password
    $bundleDirectory = Split-Path -Parent $BundlePath
    if ($bundleDirectory) { New-Item -ItemType Directory -Force -Path $bundleDirectory | Out-Null }
    [IO.File]::WriteAllBytes($BundlePath, $encrypted)
    Write-Host "Created encrypted bundle: $BundlePath"
  }
  finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue
  }
  exit 0
}

if ($Decrypt) {
  if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) {
    throw "Missing encrypted bundle: $BundlePath"
  }

  $payload = [IO.File]::ReadAllBytes($BundlePath)
  $plain = Unprotect-Bytes $payload $Password
  $tempZip = Join-Path ([IO.Path]::GetTempPath()) ('t4a-tuya-' + [Guid]::NewGuid().ToString('N') + '.zip')
  $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('t4a-tuya-' + [Guid]::NewGuid().ToString('N'))
  try {
    [IO.File]::WriteAllBytes($tempZip, $plain)
    Expand-Archive -LiteralPath $tempZip -DestinationPath $tempRoot -Force
    New-Item -ItemType Directory -Force -Path 'app/libs' | Out-Null
    New-Item -ItemType Directory -Force -Path 'app/src/main/assets' | Out-Null
    Copy-Item -LiteralPath (Join-Path $tempRoot 'security-algorithm-1.0.0-beta.aar') -Destination 'app/libs/security-algorithm-1.0.0-beta.aar' -Force
    Copy-Item -LiteralPath (Join-Path $tempRoot 't_s.bmp') -Destination 'app/src/main/assets/t_s.bmp' -Force
    Write-Host 'Restored private Tuya build inputs.'
  }
  finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempZip -Force -ErrorAction SilentlyContinue
  }
}
