<#
  「智能助手」本地停止脚本
  作用：停掉占用本服务端口的 Java 进程

  一般不需要直接运行本文件：
  双击项目根目录的「停止网站.bat」即可（本脚本由它调用）。

  实现说明：用 netstat -ano 找监听端口，兼容性比 Get-NetTCPConnection 更好
            （后者依赖 CIM/WMI，在部分受限环境不可用）。

  可选参数：
    -Port 8080    指定端口
#>
param(
    [int]$Port = 8080
)

$ErrorActionPreference = 'Continue'

Write-Host ''
Write-Host '=============================================='
Write-Host '   停止 Spring AI 智能助手 —— 本地服务'
Write-Host '=============================================='
Write-Host ''

# ---- 找出监听该端口的进程（只看 LISTENING 行；监听套接字的远端固定为 *:0，不会误判）----
$pids = @()
$pattern = ':' + $Port + '\s+.*LISTENING\s+(\d+)'
foreach ($line in (& netstat -ano 2>$null)) {
    if ($line -match $pattern) { $pids += [int]$Matches[1] }
}
$pids = @($pids | Sort-Object -Unique)

if ($pids.Count -eq 0) {
    Write-Host ('端口 ' + $Port + ' 当前没有服务在运行。') -ForegroundColor Yellow
    exit 0
}

$stopped = 0
foreach ($procId in $pids) {
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $p) { continue }

    # 只停止 Java 进程，避免误杀其它占用该端口的程序
    if ($p.ProcessName -notmatch '^java') {
        Write-Host ('端口 ' + $Port + ' 被非 Java 进程占用（' + $p.ProcessName + '  PID ' + $p.Id + '），已跳过。') -ForegroundColor Yellow
        continue
    }

    Write-Host ('正在停止服务进程  PID ' + $p.Id + ' …')
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    $stopped = $stopped + 1
}

Write-Host ''
if ($stopped -gt 0) {
    Write-Host '服务已停止。' -ForegroundColor Green
} else {
    Write-Host '没有可停止的服务进程。' -ForegroundColor Yellow
}
exit 0
