<#
.SYNOPSIS
  Traceability check: every REQ mapped to a checked step in IMPLEMENTATION_PLAN.md must be covered
  by at least one test tagged with its ID, and every REQ tag used in tests must exist in the catalog.

  Java tests:     @Tag("REQ-XXX-NN")
  Frontend tests: describe('REQ-XXX-NN ...')

.EXAMPLE
  pwsh scripts/check-traceability.ps1
#>
param(
    [string]$PlanPath = (Join-Path $PSScriptRoot '..' 'IMPLEMENTATION_PLAN.md'),
    [string]$Root = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $PlanPath)) {
    Write-Output "ERROR: plan not found: $PlanPath"
    exit 2
}
$planLines = Get-Content -Path $PlanPath -Encoding UTF8

# Catalog rows: | REQ-AUTH-01 | summary | P5.2, P11.1 |
$catalog = [ordered]@{}
foreach ($line in $planLines) {
    if ($line -match '^\|\s*(REQ-[A-Z]+-\d+)\s*\|.*\|\s*([^|]*)\|\s*$') {
        $steps = [regex]::Matches($Matches[2], 'P\d+\.\d+') | ForEach-Object { $_.Value }
        $catalog[$Matches[1]] = @($steps)
    }
}
if ($catalog.Count -eq 0) {
    Write-Output "ERROR: no REQ catalog rows found in $PlanPath"
    exit 2
}

# Checked steps: - [x] **P0.1 ...
$checkedSteps = @($planLines | ForEach-Object {
    if ($_ -match '^\s*-\s*\[[xX]\]\s*\*\*(P\d+\.\d+)') { $Matches[1] }
})

$excluded = '[\\/](node_modules|target|dist|\.git|\.angular)[\\/]'
$testFiles = Get-ChildItem -Path $Root -Recurse -File -Include '*.java', '*.spec.ts' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch $excluded -and ($_.Extension -ne '.java' -or $_.FullName -match '[\\/]src[\\/]test[\\/]') }

$tagPatterns = @(
    '@Tag\(\s*"(REQ-[A-Z]+-\d+)"\s*\)',
    'describe\(\s*[''"`](REQ-[A-Z]+-\d+)'
)
$tagged = @{}
foreach ($file in $testFiles) {
    $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    if ([string]::IsNullOrEmpty($content)) { continue }
    foreach ($pattern in $tagPatterns) {
        foreach ($m in [regex]::Matches($content, $pattern)) {
            $id = $m.Groups[1].Value
            if (-not $tagged.ContainsKey($id)) { $tagged[$id] = New-Object System.Collections.Generic.List[string] }
            $tagged[$id].Add($file.FullName)
        }
    }
}

$missing = @()
$due = 0
foreach ($id in $catalog.Keys) {
    $isDue = @($catalog[$id] | Where-Object { $checkedSteps -contains $_ }).Count -gt 0
    if ($isDue) {
        $due++
        if (-not $tagged.ContainsKey($id)) { $missing += $id }
    }
}
$unknown = @($tagged.Keys | Where-Object { -not $catalog.Contains($_) } | Sort-Object)

Write-Output ("Catalog: {0} REQ | checked steps: {1} | REQ due: {2} | REQ with tests: {3}" -f `
    $catalog.Count, $checkedSteps.Count, $due, @($tagged.Keys | Where-Object { $catalog.Contains($_) }).Count)

foreach ($id in $missing) {
    Write-Output ("MISSING TEST: {0} (steps: {1})" -f $id, ($catalog[$id] -join ', '))
}
foreach ($id in $unknown) {
    $files = ($tagged[$id] | Select-Object -Unique | ForEach-Object { Split-Path $_ -Leaf }) -join ', '
    Write-Output ("UNKNOWN TAG: {0} used in {1}" -f $id, $files)
}

if ($missing.Count -gt 0 -or $unknown.Count -gt 0) {
    Write-Output 'Traceability check FAILED'
    exit 1
}
Write-Output 'Traceability check OK'
exit 0
