# 自动递增 deploy.xml 中的版本号
# 版本格式: YYYYMMDD.序号 (如 20260805.9)
# 规则: 同一天序号+1, 新的一天序号重置为1

$deployXml = Join-Path $PSScriptRoot "bigScreen-zip\deploy\deploy.xml"
$content = [System.IO.File]::ReadAllText($deployXml)

$today = Get-Date -Format 'yyyyMMdd'

# 匹配 <version ...>数字.数字</version>
if ($content -match '<version\s[^>]*>(\d+)\.(\d+)</version>') {
    $datePart = $Matches[1]
    $numPart = $Matches[2]
    
    if ($datePart -eq $today) {
        $newNum = [string]([int]$numPart + 1)
        $newVer = "$today.$newNum"
    } else {
        $newVer = "$today.1"
    }
    
    $content = $content -replace '(<version\s[^>]*>)\d+\.\d+(</version>)', "`${1}$newVer`${2}"
    [System.IO.File]::WriteAllText($deployXml, $content)
    Write-Host "Version updated to: $newVer"
} else {
    Write-Host "Error: version tag not found in deploy.xml"
}
