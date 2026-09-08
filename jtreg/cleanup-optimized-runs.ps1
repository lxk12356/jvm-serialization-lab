$ErrorActionPreference = "Stop"
# 仅允许删除 task2-serialization/jtreg 内的临时运行目录
$dirs = @(
    "D:\kona\task2-serialization\jtreg\work-optimized-1",
    "D:\kona\task2-serialization\jtreg\results\optimized-1"
)
foreach ($d in $dirs) {
    $resolved = (Resolve-Path $d -ErrorAction SilentlyContinue)
    if ($resolved -and $resolved.Path.StartsWith("D:\kona\task2-serialization\jtreg")) {
        Remove-Item -LiteralPath $resolved.Path -Recurse -Force
        Write-Host "removed $($resolved.Path)"
    }
}
$junk = "D:\kona\task2-serialization\baseline\jfr-readCustomer.jfr"
if (Test-Path $junk) {
    Remove-Item -LiteralPath $junk -Force
    Write-Host "removed $junk"
}
