[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$KeyDirectory = (Join-Path $env:USERPROFILE ".luma-signing")
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($File)) failed with exit code $LASTEXITCODE" }
}
function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$javaHome = $env:JAVA_HOME
$androidHome = $env:ANDROID_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin/keytool.exe"))) {
    throw "Set JAVA_HOME to a JDK 21 installation."
}
if (-not $androidHome) { throw "Set ANDROID_HOME to your Android SDK." }
$keytool = Join-Path $javaHome "bin/keytool.exe"
$buildTools = Join-Path $androidHome "build-tools/36.0.0"
$apksigner = Join-Path $buildTools "apksigner.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
foreach ($tool in @($apksigner, $zipalign)) {
    if (-not (Test-Path $tool)) { throw "Missing Android build tool: $tool" }
}
$input = (Resolve-Path -LiteralPath $InputDirectory).Path
$keyDir = [IO.Path]::GetFullPath($KeyDirectory)
$outDir = [IO.Path]::GetFullPath($OutputDirectory)
if ($outDir.StartsWith($input, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Output directory must not be inside the unsigned input directory."
}
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
$keyStore = Join-Path $keyDir "luma-family.p12"
$passwordFile = Join-Path $keyDir "password"
$pinFile = Join-Path $keyDir "certificate.sha256"

if (-not (Test-Path $keyStore) -or -not (Test-Path $passwordFile)) {
    if ((Get-ChildItem -Force $keyDir | Measure-Object).Count -ne 0) {
        throw "Signing directory is incomplete. Refusing to replace or regenerate an existing key."
    }
    $rng = New-Object Security.Cryptography.RNGCryptoServiceProvider
    $bytes = New-Object byte[] 48
    $rng.GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes)
    $rng.Dispose()
    [IO.File]::WriteAllText($passwordFile, $password, [Text.Encoding]::ASCII)
    Invoke-Checked $keytool @(
        "-genkeypair", "-keystore", $keyStore, "-storetype", "PKCS12",
        "-alias", "luma-family", "-keyalg", "RSA", "-keysize", "3072",
        "-validity", "10000", "-dname", "CN=Luma Music Family",
        "-storepass:file", $passwordFile, "-keypass:file", $passwordFile
    )
    Write-Host "Created a new persistent signing key in $keyDir"
}
if (-not (Test-Path $keyStore) -or -not (Test-Path $passwordFile)) {
    throw "Keystore/password missing."
}
$files = @(Get-ChildItem -LiteralPath $input -Filter "Luma-Music-2.0.0-*.apk" -File)
$expected = @("Luma-Music-2.0.0-armeabi-v7a.apk","Luma-Music-2.0.0-arm64-v8a.apk","Luma-Music-2.0.0-x86.apk","Luma-Music-2.0.0-x86_64.apk","Luma-Music-2.0.0-universal.apk")
if ($files.Count -ne 5 -or (@($files.Name | Sort-Object) -join "|") -ne (@($expected | Sort-Object) -join "|")) {
    throw "Expected exactly five unsigned Luma Music 2.0.0 APKs."
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$fingerprints = @()
$sumLines = @()
foreach ($file in $files) {
    $dest = Join-Path $outDir $file.Name
    Invoke-Checked $apksigner @(
        "sign", "--ks", $keyStore, "--ks-key-alias", "luma-family",
        "--ks-pass", "file:$passwordFile", "--out", $dest, $file.FullName
    )
    $verify = & $apksigner verify --verbose --print-certs $dest 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $($file.Name)" }
    $match = [regex]::Match($verify, "Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})")
    if (-not $match.Success) { throw "Unable to read signer fingerprint: $($file.Name)" }
    $fingerprints += $match.Groups[1].Value.ToLowerInvariant()
    Invoke-Checked $zipalign @("-c", "-P", "16", "4", $dest)
    $sumLines += "$(Get-Sha256 $dest)  $($file.Name)"
}
$unique = @($fingerprints | Select-Object -Unique)
if ($unique.Count -ne 1) { throw "APK files do not share one signing certificate." }
$fingerprint = $unique[0]
if ((Test-Path $pinFile) -and ((Get-Content -Raw $pinFile).Trim() -ne $fingerprint)) {
    throw "The existing key fingerprint differs. Refusing to rotate the signing identity."
}
[IO.File]::WriteAllText((Join-Path $outDir "certificate.sha256"), "$fingerprint`n", [Text.Encoding]::ASCII)
[IO.File]::WriteAllText((Join-Path $outDir "SHA256SUMS.txt"), (($sumLines -join "`n") + "`n"), [Text.Encoding]::ASCII)
if (-not (Test-Path $pinFile)) {
    [IO.File]::WriteAllText($pinFile, "$fingerprint`n", [Text.Encoding]::ASCII)
}
Write-Host ""
Write-Host "SIGNED APKs: $outDir"
Write-Host "CERTIFICATE SHA-256: $fingerprint"
Write-Host "Back up the entire key directory securely. Never upload luma-family.p12 or password."
