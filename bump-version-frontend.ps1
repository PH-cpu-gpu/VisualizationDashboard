# Auto-increment version in package.json
# Version format: YYYYMMDD.N.0 (e.g. 20260805.9.0)
# Rule: increment N on same day, reset to 1 on new day, last ".0" stays unchanged

$packageJson = Join-Path $PSScriptRoot "bigScreen-web\package.json"
$content = [System.IO.File]::ReadAllText($packageJson)

$today = Get-Date -Format 'yyyyMMdd'

# Match "version": "YYYYMMDD.N.0"
if ($content -match '"version"\s*:\s*"(\d+)\.(\d+)\.(\d+)"') {
    $datePart = $Matches[1]
    $numPart = $Matches[2]
    $suffix = $Matches[3]
    
    if ($datePart -eq $today) {
        $newNum = [string]([int]$numPart + 1)
    } else {
        $newNum = "1"
    }
    
    $newVer = "$today.$newNum.$suffix"
    $content = $content -replace '("version"\s*:\s*)"\d+\.\d+\.\d+"', "`${1}`"$newVer`""
    [System.IO.File]::WriteAllText($packageJson, $content)
    Write-Host "Frontend version updated to: $newVer"
} else {
    Write-Host "Error: version field not found in package.json"
}
