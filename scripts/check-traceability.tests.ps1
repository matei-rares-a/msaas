<#
.SYNOPSIS
  Self-test for check-traceability.ps1 on a sample plan and sample test files.
  Exit code 0 = all cases pass.
#>
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'check-traceability.ps1'

$samplePlan = @'
| ID | Rezumat | Pas |
|---|---|---|
| REQ-AAA-01 | covered, step checked | P1.1 |
| REQ-AAA-02 | not covered, step checked | P1.1 |
| REQ-BBB-01 | not covered, step not checked | P2.1 |
| REQ-CCC-01 | not covered, one of two steps checked | P2.1, P1.2 |
| REQ-DDD-01 | covered by frontend only | P1.2 (frontend) |

- [x] **P1.1 Checked step**
- [x] **P1.2 Another checked step**
- [ ] **P2.1 Open step**
'@

function New-Fixture([hashtable]$files) {
    $dir = Join-Path ([System.IO.Path]::GetTempPath()) ("trace-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $dir | Out-Null
    Set-Content -Path (Join-Path $dir 'plan.md') -Value $samplePlan -Encoding UTF8
    foreach ($rel in $files.Keys) {
        $path = Join-Path $dir $rel
        New-Item -ItemType Directory -Path (Split-Path $path) -Force | Out-Null
        Set-Content -Path $path -Value $files[$rel] -Encoding UTF8
    }
    return $dir
}

function Invoke-Check([string]$dir) {
    $output = & $script -PlanPath (Join-Path $dir 'plan.md') -Root $dir | Out-String
    return [pscustomobject]@{ Code = $LASTEXITCODE; Output = $output }
}

$allCovered = @{
    'svc/src/test/java/ATest.java'   = '@Tag("REQ-AAA-01") @Tag("REQ-AAA-02") class ATest {}'
    'svc/src/test/java/CTest.java'   = '@Tags({@Tag("REQ-CCC-01")}) class CTest {}'
    'web/src/app/d.component.spec.ts' = "describe('REQ-DDD-01 theme', () => {});"
}

$cases = @(
    @{
        Name   = 'passes when every due REQ has a test'
        Files  = $allCovered
        Code   = 0
        Expect = @('Traceability check OK')
        Reject = @('MISSING TEST', 'REQ-BBB-01')
    },
    @{
        Name   = 'reports a due REQ without a test'
        Files  = @{ 'svc/src/test/java/ATest.java' = '@Tag("REQ-AAA-01") class ATest {}'; 'web/src/x.spec.ts' = "describe('REQ-DDD-01', () => {});"; 'svc/src/test/java/C.java' = '@Tag("REQ-CCC-01") class C {}' }
        Code   = 1
        Expect = @('MISSING TEST: REQ-AAA-02')
        Reject = @('MISSING TEST: REQ-AAA-01', 'MISSING TEST: REQ-BBB-01')
    },
    @{
        Name   = 'a REQ is due when any of its steps is checked'
        Files  = @{ 'svc/src/test/java/ATest.java' = '@Tag("REQ-AAA-01") @Tag("REQ-AAA-02") class ATest {}'; 'web/src/x.spec.ts' = "describe('REQ-DDD-01', () => {});" }
        Code   = 1
        Expect = @('MISSING TEST: REQ-CCC-01')
        Reject = @()
    },
    @{
        Name   = 'reports a tag that is not in the catalog'
        Files  = $allCovered + @{ 'svc/src/test/java/Typo.java' = '@Tag("REQ-AAA-99") class Typo {}' }
        Code   = 1
        Expect = @('UNKNOWN TAG: REQ-AAA-99')
        Reject = @('MISSING TEST')
    },
    @{
        Name   = 'ignores tags outside test sources and build output'
        Files  = @{
            'svc/src/test/java/ATest.java'   = '@Tag("REQ-AAA-01") class ATest {}'
            'svc/src/main/java/Main.java'    = '@Tag("REQ-AAA-02") class Main {}'
            'svc/target/src/test/java/X.java' = '@Tag("REQ-AAA-02") class X {}'
            'web/node_modules/p/a.spec.ts'   = "describe('REQ-AAA-02', () => {});"
            'svc/src/test/java/C.java'       = '@Tag("REQ-CCC-01") class C {}'
            'web/src/x.spec.ts'              = "describe('REQ-DDD-01', () => {});"
        }
        Code   = 1
        Expect = @('MISSING TEST: REQ-AAA-02')
        Reject = @()
    }
)

$failures = 0
foreach ($case in $cases) {
    $dir = New-Fixture $case.Files
    try {
        $result = Invoke-Check $dir
        $problems = @()
        if ($result.Code -ne $case.Code) { $problems += "exit code $($result.Code), expected $($case.Code)" }
        foreach ($text in $case.Expect) { if (-not $result.Output.Contains($text)) { $problems += "missing output '$text'" } }
        foreach ($text in $case.Reject) { if ($result.Output.Contains($text)) { $problems += "unexpected output '$text'" } }
        if ($problems.Count -eq 0) {
            Write-Output "PASS  $($case.Name)"
        } else {
            $failures++
            Write-Output "FAIL  $($case.Name): $($problems -join '; ')"
            Write-Output $result.Output
        }
    } finally {
        Remove-Item -Recurse -Force $dir
    }
}

if ($failures -gt 0) {
    Write-Output "$failures case(s) failed"
    exit 1
}
Write-Output 'All traceability script tests passed'
exit 0
