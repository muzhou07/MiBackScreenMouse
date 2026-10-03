# 包名重命名：mz.backscreen.house -> mz.mibackscreen.mouse
# 同时修掉拼写错误：BackScreenHouse -> BackScreenMouse，backscreen_house -> backscreen_mouse
$ErrorActionPreference = 'Stop'
$root = 'd:\Code\VSCode\Android\MiBackScreenMouse'
Set-Location $root

# 1) 目录迁移：app/src/main/java/mz/backscreen/house -> app/src/main/java/mz/mibackscreen/mouse
$oldDir = Join-Path $root 'app\src\main\java\mz\backscreen\house'
$newParent = Join-Path $root 'app\src\main\java\mz\mibackscreen'
$newDir = Join-Path $newParent 'mouse'
New-Item -ItemType Directory -Force -Path $newParent | Out-Null
if (Test-Path $oldDir) {
    if (Test-Path $newDir) { Remove-Item -Recurse -Force $newDir }
    Move-Item -Path $oldDir -Destination $newDir
    Write-Output 'moved: java/mz/backscreen/house -> java/mz/mibackscreen/mouse'
}
Remove-Item -Recurse -Force (Join-Path $root 'app\src\main\java\mz\backscreen') -ErrorAction SilentlyContinue

# 2) 文本替换（kt/xml/c/gradle/shell 脚本/文档）
$files = @()
$files += Get-ChildItem -Recurse -Path (Join-Path $root 'app\src') -File -Include *.kt, *.xml, *.c
$files += Get-ChildItem -Path (Join-Path $root 'app\build.gradle.kts') -File
$files += Get-ChildItem -Path (Join-Path $root 'artifacts') -File -Include *.sh
$files += Get-ChildItem -Path (Join-Path $root '_refs') -File -Include *.md

foreach ($f in $files) {
    $t = [IO.File]::ReadAllText($f.FullName)
    $t2 = $t.Replace('mz.backscreen.house', 'mz.mibackscreen.mouse').
             Replace('BackScreenHouse', 'BackScreenMouse').
             Replace('backscreen_house', 'backscreen_mouse')
    if ($t2 -ne $t) {
        [IO.File]::WriteAllText($f.FullName, $t2)
        Write-Output ('updated: ' + $f.FullName.Replace($root + '\', ''))
    }
}
Write-Output '=== 剩余 house/House 命中（应为空）==='
Get-ChildItem -Recurse -Path (Join-Path $root 'app\src') -File -Include *.kt, *.xml, *.c |
    Select-String -Pattern 'house|House' |
    ForEach-Object { $_.Path.Replace($root + '\', '') + ':' + $_.LineNumber + ': ' + $_.Line.Trim() }
