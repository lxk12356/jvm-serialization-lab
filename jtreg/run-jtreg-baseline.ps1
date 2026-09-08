param(
    [string]$JdkImage = "D:\kona\TencentKona-25\build\release\images\jdk",
    [string]$KonaRoot  = "D:\kona\TencentKona-25",
    [string]$JtregHome = "D:\kona\jtreg",
    [string]$OutRoot   = "D:\kona\task2-serialization\jtreg",
    [int]$Concurrency  = 8,
    [string]$ReportName = "baseline-jtreg"
)
$ErrorActionPreference = "Stop"

$java   = Join-Path $JdkImage "bin\java.exe"
$jtreg  = Join-Path $JtregHome "lib\jtreg.jar"
$work   = Join-Path $OutRoot ("work-" + $ReportName)
$report = Join-Path $OutRoot (Join-Path "results" $ReportName)

# jtreg 在探测 WSL bash 时会解析 PATH；清理 PATH，避免继承条目中的
# 不可见字符导致 Paths.get() 抛异常。
$env:Path = "C:\Windows\System32;C:\Windows;$JdkImage\bin"

# 与 java.io 序列化实现相关的测试根目录
$tests = @(
    (Join-Path $KonaRoot "test\jdk\java\io\Serializable"),
    (Join-Path $KonaRoot "test\jdk\java\io\ObjectInputStream"),
    (Join-Path $KonaRoot "test\jdk\java\io\ObjectStreamClass")
)

Write-Host "JDK under test : $JdkImage"
& $java -version 2>&1 | Select-Object -First 1
Write-Host "jtreg          : $jtreg"
Write-Host "work           : $work"
Write-Host "report         : $report"
Write-Host "tests          : $($tests.Count) roots, concurrency $Concurrency"

if (Test-Path $work)   { Remove-Item -Recurse -Force $work }
if (Test-Path $report) { Remove-Item -Recurse -Force $report }
New-Item -ItemType Directory -Force -Path (Split-Path $report) | Out-Null

& $java -jar $jtreg `
    "-jdk:$JdkImage" `
    "-w:$work" `
    "-r:$report" `
    "-conc:$Concurrency" `
    "-timeoutFactor:2" `
    "-ignore:quiet" `
    "-v:fail,error" `
    $tests

if ($LASTEXITCODE -ne 0) {
    Write-Warning "jtreg exited with code $LASTEXITCODE"
    exit $LASTEXITCODE
}
Write-Host "jtreg baseline report: $report"
