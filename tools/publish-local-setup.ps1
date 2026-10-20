# One-time helper: writes the Maven Central publishing credentials into
# %USERPROFILE%\.gradle\gradle.properties so local publishing works.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\publish-local-setup.ps1
#
# It asks for (and never echoes):
#   - Central Portal user-token username/password  (https://central.sonatype.com/account)
#   - the GPG key passphrase
# and exports the signing key from the local GPG keyring itself.
#
# Afterwards you can run:
#   .\gradlew publishToMavenLocal      # full local dry-run including signatures
#   .\gradlew publishToMavenCentral    # real upload (needs verified namespace)
#   .\gradlew publishAndReleaseToMavenCentral   # upload + auto-release
#
# The GPG key used is the one already generated for this project
# ("My maven central key"). Override with -KeyId <40-char key id> if needed.

param(
    [string]$KeyId = "D1BA3084B75225EFD65C2924807BFE4AC9B34D99"
)

$ErrorActionPreference = "Stop"

$propsPath = Join-Path $env:USERPROFILE ".gradle\gradle.properties"

# --- collect secrets (never printed) ---------------------------------------
$tokenUser     = Read-Host "Central Portal token USERNAME (from central.sonatype.com/account)"
$tokenPassword = Read-Host "Central Portal token PASSWORD" -AsSecureString
$gpgPassphrase = Read-Host "GPG key passphrase (empty if the key has none)" -AsSecureString

# --- export the armored private key ----------------------------------------
Write-Host "Exporting GPG key $KeyId ..."
$armorLines = & gpg --batch --status-fd 2 --export-secret-keys --armor $KeyId 2>$null
if ($LASTEXITCODE -ne 0 -or -not $armorLines) {
    throw "gpg export failed (exit $LASTEXITCODE). Check that key id $KeyId exists: gpg --list-secret-keys"
}
$inMemoryKey = ($armorLines -join "`n") -replace "`n", "\n"

# --- build the property values ---------------------------------------------
$plain = {
    param($secureString)
    [System.Net.NetworkCredential]::new("", $secureString).Password
}

$newValues = [ordered]@{
    "mavenCentralUsername"      = $tokenUser
    "mavenCentralPassword"      = & $plain $tokenPassword
    "signingInMemoryKeyId"      = $KeyId
    "signingInMemoryKey"        = $inMemoryKey
    "signingInMemoryKeyPassword" = & $plain $gpgPassphrase
}

# --- merge into gradle.properties (replace existing keys, keep the rest) ---
Write-Host "Writing $propsPath ..."
if (-not (Test-Path $propsPath)) {
    New-Item -ItemType File -Path $propsPath -Force | Out-Null
}

$lines = [System.IO.File]::ReadAllLines($propsPath)
$kept  = $lines | Where-Object { $_ -notmatch '^\s*(mavenCentralUsername|mavenCentralPassword|signingInMemoryKeyId|signingInMemoryKey|signingInMemoryKeyPassword)\s*=' }
$newlineCount = 0
$final  = New-Object System.Collections.Generic.List[string]
$final.AddRange([string[]]$kept)
foreach ($name in $newValues.Keys) {
    $final.Add("$name=$($newValues[$name])")
    $newlineCount++
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($propsPath, $final, $utf8NoBom)

Write-Host ""
Write-Host "Done. Updated $newlineCount properties in $propsPath (other properties were preserved)."
Write-Host "Verify with:  .\gradlew publishToMavenLocal"
Write-Host "Then upload:  .\gradlew publishToMavenCentral"
