# ITHomeAdKill 发布脚本
# 用法: .\release.ps1 -Version "v1.1" -Notes "更新说明"
# 前提: 已在项目根目录完成代码修改
param(
    [Parameter(Mandatory = $true)][string]$Version,   # 例如 v1.1
    [Parameter(Mandatory = $true)][string]$Notes      # 更新说明
)

$ErrorActionPreference = "Stop"
$proj    = "C:\Users\姚在宇\Desktop\ITHomeAdKill"        # 源码目录(git 仓库)
$build   = "C:\ITHomeAdKill"                              # 构建目录(纯英文路径)
$gradle  = "C:\Users\姚在宇\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat"
$gh      = "C:\Program Files\GitHub CLI\gh.exe"
$adb     = "C:\Users\姚在宇\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$repoMod = "Xposed-Modules-Repo/io.github.ghsssss.ithomeadkill"
$env:HTTPS_PROXY = "http://127.0.0.1:57122"
$env:HTTP_PROXY   = "http://127.0.0.1:57122"

Write-Host "==> [1/7] 同步源码到构建目录" -ForegroundColor Cyan
robocopy $proj $build /E /NFL /NDL /NJH /NJS | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy 失败" }

Write-Host "==> [2/7] 编译 APK" -ForegroundColor Cyan
& $gradle assembleDebug --console=plain -q -p $build
if ($LASTEXITCODE -ne 0) { throw "编译失败" }
$apk = "$build\app\build\outputs\apk\debug\app-debug.apk"
$apkNamed = "$proj\ITHomeAdKill-$Version.apk"
Copy-Item $apk $apkNamed -Force

Write-Host "==> [3/7] 安装到手机" -ForegroundColor Cyan
& $adb install -r $apk

Write-Host "==> [4/7] 提交并推送源码" -ForegroundColor Cyan
git -C $proj add -A
git -C $proj commit -m "release $Version" --allow-empty
git -C $proj tag $Version
git -C $proj push
git -C $proj push origin $Version

Write-Host "==> [5/7] 发布 GitHub Release (ghsssss/ITHomeAdKill)" -ForegroundColor Cyan
& $gh release create $Version $apkNamed --repo ghsssss/ITHomeAdKill --title $Version --notes $Notes

Write-Host "==> [6/7] 更新 LSPosed 仓库 updates.json" -ForegroundColor Cyan
$modRepo = "C:\Users\姚在宇\Desktop\ITHomeAdKill-repo"
git -C $modRepo pull
# versionCode 取 tag 数字部分递增
$vcode = [int]($Version -replace '\D', '')
if ($vcode -eq 0) { throw "版本号需含数字，如 v1.1" }
$zipUrl = "https://github.com/$repoMod/releases/download/$Version/ITHomeAdKill-$Version.apk"
@{
    updates = @(
        @{ version = $Version; versionCode = $vcode; zipUrl = $zipUrl; changelog = $Notes }
    )
} | ConvertTo-Json -Depth 4 | Set-Content "$modRepo\updates.json" -Encoding UTF8
git -C $modRepo add -A
git -C $modRepo commit -m "release $Version"
git -C $modRepo push

Write-Host "==> [7/7] 发布 LSPosed 仓库 Release" -ForegroundColor Cyan
& $gh release create $Version $apkNamed --repo $repoMod --title $Version --notes $Notes

Write-Host "==> 发布完成: $Version" -ForegroundColor Green
Write-Host "  - 源码:   https://github.com/ghsssss/ITHomeAdKill"
Write-Host "  - Release: https://github.com/ghsssss/ITHomeAdKill/releases/tag/$Version"
Write-Host "  - LSPosed 更新源已同步, 客户端会提示更新"
