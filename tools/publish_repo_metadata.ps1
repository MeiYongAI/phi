<#
.SYNOPSIS
    将元数据推送到 Xposed-Modules-Repo 仓库。

.DESCRIPTION
    将 tools/repo-metadata 下的 SUMMARY、README.md、SCOPE、SOURCE_URL
    推送到 Xposed-Modules-Repo/io.github.meiyongai.phi 仓库的 main 分支。

.PARAMETER RepoUrl
    Xposed-Modules-Repo 目标仓库的 Git 地址。

.EXAMPLE
    .\tools\publish_repo_metadata.ps1 -RepoUrl "git@github.com:Xposed-Modules-Repo/io.github.meiyongai.phi.git"
#>
param (
    [string]$RepoUrl = "git@github.com:Xposed-Modules-Repo/io.github.meiyongai.phi.git"
)

function Sync-ModuleMetadata {
    <#
    .SYNOPSIS
        克隆现有元数据分支并推送到目标仓库。

    .DESCRIPTION
        创建临时目录，将 tools/repo-metadata 下的文件复制进去并执行 Git 提交与推送。

    Args:
        TargetUrl (string): 远程 Git 仓库地址。
        SourceDir (string): 包含元数据文件的本地目录路径。

    Returns:
        bool: 推送成功返回 $true，否则返回 $false。

    Callers:
        - `tools/publish_repo_metadata.ps1`: 顶层执行入口。
    #>
    param (
        [string]$TargetUrl,
        [string]$SourceDir
    )

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lsposed_repo_" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    try {
        Write-Host "[*] 正在克隆元数据仓库..." -ForegroundColor Cyan
        git clone --single-branch --branch main -- $TargetUrl $tempDir
        if ($LASTEXITCODE -ne 0) { throw "git clone 执行失败" }

        git -C $tempDir config user.name "MeiYongAI"
        git -C $tempDir config user.email "lernsc@gmail.com"

        $metadataFiles = @("SUMMARY", "README.md", "SCOPE", "SOURCE_URL")
        foreach ($name in $metadataFiles) {
            Copy-Item -LiteralPath (Join-Path $SourceDir $name) -Destination $tempDir -Force
        }

        git -C $tempDir add -- $metadataFiles
        if ($LASTEXITCODE -ne 0) { throw "git add 执行失败" }
        git -C $tempDir diff --cached --quiet
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[+] 远端元数据已是最新。" -ForegroundColor Green
            return $true
        }
        if ($LASTEXITCODE -ne 1) { throw "git diff 执行失败" }
        git -C $tempDir commit -m "docs: sync module metadata and scope"
        if ($LASTEXITCODE -ne 0) { throw "git commit 执行失败" }

        Write-Host "[*] 正在推送到远程仓库: $TargetUrl ..." -ForegroundColor Cyan
        git -C $tempDir push origin HEAD:main
        if ($LASTEXITCODE -ne 0) { throw "git push 执行失败" }

        Write-Host "[+] 元数据推送成功！" -ForegroundColor Green
        return $true
    }
    finally {
        if (Test-Path -LiteralPath $tempDir) {
            $resolvedTempDir = (Resolve-Path -LiteralPath $tempDir).ProviderPath
            $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
            if ([System.IO.Directory]::GetParent($resolvedTempDir).FullName -ne $tempRoot) {
                throw "临时目录不在预期的临时根目录内: $resolvedTempDir"
            }
            Remove-Item -LiteralPath $resolvedTempDir -Recurse -Force
        }
    }
}

$metadataDir = Join-Path $PSScriptRoot "repo-metadata"
if (-not (Test-Path $metadataDir)) {
    Write-Error "未找到元数据目录: $metadataDir"
    exit 1
}

Sync-ModuleMetadata -TargetUrl $RepoUrl -SourceDir $metadataDir
