param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk",

    [switch]$AllowUnsigned
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$sourceApk = Join-Path $root $ApkPath
if (-not (Test-Path -LiteralPath $sourceApk)) {
    throw "Release APK not found: $sourceApk. Run .\gradlew.bat :app:assembleRelease first."
}
if ((Split-Path -Leaf $sourceApk) -like "*unsigned*" -and -not $AllowUnsigned) {
    throw "APK appears to be unsigned: $sourceApk. Configure release signing or pass -AllowUnsigned only for local preview packages."
}

$releaseDir = Join-Path $root "release\v$Version"
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

$targetApkName = "Nazhi-v$Version.apk"
$targetApk = Join-Path $releaseDir $targetApkName
Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force

$hash = Get-FileHash -LiteralPath $targetApk -Algorithm SHA256
$shaFile = Join-Path $releaseDir "$targetApkName.sha256"
"$($hash.Hash.ToLower())  $targetApkName" | Set-Content -Path $shaFile -Encoding ASCII

$notesSource = Join-Path $root "docs\发布说明模板-v$Version.md"
if (Test-Path -LiteralPath $notesSource) {
    Copy-Item -LiteralPath $notesSource -Destination (Join-Path $releaseDir "RELEASE_NOTES.md") -Force
}

$readme = Join-Path $releaseDir "README.txt"
if ((Split-Path -Leaf $sourceApk) -like "*unsigned*") {
    @(
        "Nazhi v$Version release preview package",
        "",
        "WARNING: This APK was built from an unsigned release artifact.",
        "Do not upload it as the public GitHub Release APK.",
        "Configure NAZHI_RELEASE_STORE_FILE / NAZHI_RELEASE_STORE_PASSWORD / NAZHI_RELEASE_KEY_ALIAS / NAZHI_RELEASE_KEY_PASSWORD and rebuild assembleRelease first."
    ) | Set-Content -Path $readme -Encoding UTF8
} else {
    @(
        "Nazhi v$Version release package",
        "",
        "Upload the APK, SHA256 file, and RELEASE_NOTES.md content to GitHub Releases."
    ) | Set-Content -Path $readme -Encoding UTF8
}

Write-Host "Release package prepared:"
Write-Host "  $targetApk"
Write-Host "  $shaFile"
