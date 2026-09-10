$ErrorActionPreference = "Stop"
# 路径占位符：<LAB_ROOT> = 本仓库根（task2-serialization），运行前替换为本机实际路径。
# 仅允许删除 task2-serialization/jtreg 内的临时运行目录
$dirs = @(
    "<LAB_ROOT>\jtreg\work-optimized-1",
    "<LAB_ROOT>\jtreg\results\optimized-1"
)
foreach ($d in $dirs) {
    $resolved = (Resolve-Path $d -ErrorAction SilentlyContinue)
    if ($resolved -and $resolved.Path.StartsWith("<LAB_ROOT>\jtreg")) {
        Remove-Item -LiteralPath $resolved.Path -Recurse -Force
        Write-Host "removed $($resolved.Path)"
    }
}
$junk = "<LAB_ROOT>\baseline\jfr-readCustomer.jfr"
if (Test-Path $junk) {
    Remove-Item -LiteralPath $junk -Force
    Write-Host "removed $junk"
}
