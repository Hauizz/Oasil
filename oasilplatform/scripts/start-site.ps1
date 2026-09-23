<#
  「智能助手」本地启动脚本
  作用：自动找 Java 17+ -> （必要时）打包 -> 启动服务 -> 服务就绪后自动打开浏览器

  一般不需要直接运行本文件：
  双击项目根目录的「启动网站.bat」即可（本脚本由它调用）。

  服务在前台运行，本窗口即服务控制台：
    - 可以直接看到启动日志，出问题一眼能看出原因
    - 关闭本窗口 / 按 Ctrl+C / 双击「停止网站.bat」 都能停止服务

  可选参数：
    -Port 8080    指定端口
    -NoBrowser    只启动服务，不打开浏览器
#>
param(
    [int]$Port = 8080,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Continue'

# 项目根目录（本脚本位于 <项目>/scripts/ 下）
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Info($m) { Write-Host $m }
function Ok($m)   { Write-Host $m -ForegroundColor Green }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Bad($m)  { Write-Host $m -ForegroundColor Red }

$siteUrl = 'http://localhost:' + $Port + '/'

Info ''
Info '=============================================='
Info '             Oasil —— loading                 '
Info '=============================================='
Info ''

# ---------------- 1. 查找 Java 17+ ----------------
function Find-JavaExe {
    $cands = New-Object System.Collections.ArrayList
    if ($env:JAVA_HOME) { [void]$cands.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }

    $bases = @(
        'C:\Program Files\Microsoft',
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Amazon Corretto',
        'C:\Program Files\Zulu',
        'C:\Program Files\BellSoft'
    )
    foreach ($b in $bases) {
        foreach ($d in (Get-ChildItem $b -Directory -ErrorAction SilentlyContinue)) {
            if ($d.Name -match 'jdk') { [void]$cands.Add((Join-Path $d.FullName 'bin\java.exe')) }
        }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { [void]$cands.Add($cmd.Source) }

    foreach ($exe in $cands) {
        if (-not (Test-Path $exe)) { continue }
        $v = (& $exe -version 2>&1 | Select-Object -First 1)
        if ($v -match 'version "(\d+)') {
            if ([int]$Matches[1] -ge 17) { return $exe }
        }
    }
    return $null
}

$javaExe = Find-JavaExe
if (-not $javaExe) {
    Bad '未找到 Java 17 或更高版本。'
    Bad '请安装 JDK 17+（例如 Microsoft Build of OpenJDK 17）后重试。'
    exit 1
}
# 有些机器 JAVA_HOME 指向低版本（本项目需要 17+），这里强制指向可用版本，Maven 打包也一并受益
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
Ok ('[1/4] Java: ' + $javaExe)

# ---------------- 2. 是否已经在运行 ----------------
function Test-Site {
    try {
        $r = Invoke-WebRequest -Uri $siteUrl -UseBasicParsing -TimeoutSec 2
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

if (Test-Site) {
    Warn ('[2/4] 网站已经在运行（' + $siteUrl + '），不再重复启动，直接打开浏览器。')
    if (-not $NoBrowser) { Start-Process $siteUrl }
    exit 0
}
Ok '[2/4] 端口空闲，准备启动服务'

# ---------------- 3. 找程序包，必要时重新打包 ----------------
function Get-BootJar {
    $t = Join-Path $Root 'target'
    if (-not (Test-Path $t)) { return $null }
    $jars = @(Get-ChildItem $t -Filter 'spring-ai-chat-demo-*.jar' -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -notlike '*.original' } |
              Sort-Object LastWriteTime -Descending)
    if ($jars.Count -gt 0) { return $jars[0] }
    return $null
}

# 取 src/ 与 pom.xml 中最新的修改时间，用于判断程序包是否过期
function Get-NewestSourceTime {
    $newest = $null
    $srcDir = Join-Path $Root 'src'
    if (Test-Path $srcDir) {
        $f = Get-ChildItem $srcDir -Recurse -File -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($f) { $newest = $f.LastWriteTime }
    }
    $pom = Join-Path $Root 'pom.xml'
    if (Test-Path $pom) {
        $t = (Get-Item $pom).LastWriteTime
        if ((-not $newest) -or ($t -gt $newest)) { $newest = $t }
    }
    return $newest
}

function Invoke-Package {
    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mvn) { $mvn = Get-Command mvn -ErrorAction SilentlyContinue }
    if ($mvn) {
        & $mvn.Source -q -DskipTests package
        return $LASTEXITCODE
    }
    $mvnw = Join-Path $Root 'mvnw.cmd'
    if (Test-Path $mvnw) {
        & $mvnw -q -DskipTests package
        return $LASTEXITCODE
    }
    return -1
}

$jar = Get-BootJar
$needBuild = $false

if (-not $jar) {
    $needBuild = $true
    Warn '[3/4] 未找到可执行程序包，需要先打包…'
} else {
    $newest = Get-NewestSourceTime
    if ($newest -and ($newest -gt $jar.LastWriteTime)) {
        $needBuild = $true
        Warn '[3/4] 检测到源码比程序包新，重新打包…'
    } else {
        Ok ('[3/4] 使用已有程序包: ' + $jar.Name)
    }
}

if ($needBuild) {
    Info '      正在打包（首次执行需要联网下载依赖，请稍候）…'
    $rc = Invoke-Package
    $jar = Get-BootJar
    if (($rc -ne 0) -or (-not $jar)) {
        Bad '打包失败。请手动在项目目录执行：mvn -DskipTests package'
        exit 1
    }
    Ok ('      打包完成: ' + $jar.Name)
}

# ---------------- 4. 启动服务并自动打开浏览器 ----------------
# 可选：把 DeepSeek API Key 写进项目根目录的 deepseek-key.txt，AI 问答即可用（不配也能用文件仓库）
$keyFile = Join-Path $Root 'deepseek-key.txt'
if ((-not $env:deepseek_api_key) -and (Test-Path $keyFile)) {
    $k = Get-Content $keyFile -Raw -ErrorAction SilentlyContinue
    if ($k) { $env:deepseek_api_key = $k.Trim() }
    if ($env:deepseek_api_key) { Ok '      已从 deepseek-key.txt 读取 DeepSeek API Key' }
}

Ok '[4/4] 服务启动中，就绪后会自动打开浏览器…'
Info ''
Info '---------------------------------------------------------------'
Info ('   服务地址：' + $siteUrl)
Info '   页    面：智能问答（首页）/ 文件仓库 / 日志统计'
Info '   停止服务：关闭本窗口，或按 Ctrl+C，或双击「停止网站.bat」'
Info '---------------------------------------------------------------'
Info ''

try { $Host.UI.RawUI.WindowTitle = '智能助手 - 服务运行中（关闭本窗口即停止）' } catch { }

# 就绪标记文件：用来区分「启动失败」和「正常运行后被你停掉」，避免误报失败
$readyFile = Join-Path $env:TEMP ('spring-ai-chat-demo-ready-' + $Port + '.txt')
Remove-Item $readyFile -Force -ErrorAction SilentlyContinue

$openBrowser = '0'
if (-not $NoBrowser) { $openBrowser = '1' }

# 就绪助手：在隐藏窗口里等待服务就绪，写标记文件，并按需自动打开默认浏览器
$helper = "for(`$i=0;`$i -lt 120;`$i++){ try{ `$r=Invoke-WebRequest '$siteUrl' -UseBasicParsing -TimeoutSec 2; if(`$r.StatusCode -eq 200){ Set-Content -Path '$readyFile' -Value 'ok' -ErrorAction SilentlyContinue; if('$openBrowser' -eq '1'){ Start-Process '$siteUrl' }; break } }catch{}; Start-Sleep -Seconds 1 }"
Start-Process -FilePath 'powershell.exe' `
    -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-Command', ('"' + $helper + '"')) | Out-Null

# 前台运行服务：本窗口即服务控制台
& $javaExe -jar $jar.FullName ('--server.port=' + $Port)

if (Test-Path $readyFile) {
    Remove-Item $readyFile -Force -ErrorAction SilentlyContinue
    Info ''
    Ok '服务已停止，本窗口可以直接关闭。'
    exit 0
}

Bad ''
Bad '服务未能正常启动，请查看上方日志定位原因。'
Bad '也可以在本目录执行  mvn spring-boot:run  查看完整错误信息。'
exit 1
