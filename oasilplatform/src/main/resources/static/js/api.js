/* 后端接口封装（原生 fetch / XMLHttpRequest，无框架、无构建步骤） */

// 直接双击 HTML 文件打开时是 file:// 协议，浏览器出于安全策略不允许该页面访问本地服务。
// 这里给出明确提示，引导使用项目根目录的「启动网站.bat」。
if (location.protocol === 'file:') {
  const tip = document.createElement('div')
  tip.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:9999;background:#7f1d1d;color:#fee2e2;padding:12px 18px;font-size:14px;line-height:1.7'
  tip.textContent = '⚠ 当前是以「直接双击 HTML 文件」（file://）方式打开的，浏览器不允许这种页面访问本地后端服务，页面功能无法使用。'
    + '请改为双击项目根目录的「启动网站.bat」，它会自动启动服务并打开 http://localhost:8080/ 。'
  if (document.body) {
    document.body.appendChild(tip)
  } else {
    document.addEventListener('DOMContentLoaded', function () { document.body.appendChild(tip) })
  }
}

const BASE = '/api'

/** 统一请求：非 2xx 时抛出带后端 message 的错误 */
async function request(path, options) {
  const res = await fetch(BASE + path, options)
  if (!res.ok) {
    let msg = 'HTTP ' + res.status
    try {
      const data = await res.json()
      if (data && data.message) msg = data.message
    } catch (e) {
      /* 响应不是 JSON，沿用 HTTP 状态码 */
    }
    throw new Error(msg)
  }
  return res
}

function getJSON(path) {
  return request(path).then((res) => res.json())
}

/* ---------------- 智能问答 ---------------- */

/** 发送一条聊天消息，返回 AI 回答文本（可能耗时较长） */
export async function sendChat(message) {
  const res = await request('/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message })
  })
  return res.text()
}

/* ---------------- 文件仓库 ---------------- */

/** 文件列表 */
export function listFiles() {
  return getJSON('/files')
}

/**
 * 上传文件（用 XHR 以便获取上传进度）
 * onProgress 形如 (percent) => void
 */
export function uploadFile(file, onProgress) {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', file)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE + '/files/upload')
    xhr.upload.onprogress = (e) => {
      if (onProgress && e.lengthComputable) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    }
    xhr.onload = () => {
      let data = {}
      try {
        data = JSON.parse(xhr.responseText)
      } catch (e) {
        /* 后端异常时可能不是 JSON */
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(data)
      } else {
        reject(new Error(data.message || 'HTTP ' + xhr.status))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误，上传失败'))
    xhr.send(form)
  })
}

/** 下载文件，按原始文件名保存 */
export async function downloadFile(id, name) {
  const res = await request('/files/' + id)
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name || 'download'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** 删除文件 */
export function removeFile(id) {
  return request('/files/' + id, { method: 'DELETE' }).then((res) => res.json())
}

/* ---------------- 答题：读取文件内容并提取题目 ---------------- */

/** 读取某个文件，返回解析出的题目列表 */
export function loadQuestions(fileId) {
  return getJSON('/questions/' + fileId)
}

/* ---------------- 聊天日志 ---------------- */

export function logsStats() {
  return getJSON('/logs/stats')
}

export function logsRecent(limit = 50) {
  return getJSON('/logs/recent?limit=' + limit)
}
