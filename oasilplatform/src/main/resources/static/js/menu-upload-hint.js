/*
  菜单页（menu.html）专用脚本

  作用：查询本地文件仓库
    - 仓库里【没有】任何文件 -> 在页面里添加一个「可上传相应文件」按钮，点击进入上传页
    - 仓库里【已有】文件     -> 什么都不做

  本脚本只做“新增”，不会改动 menu.html 里已有的任何内容。
*/
import { listFiles } from './api.js'

/** 文件上传页 */
const UPLOAD_PAGE = 'repository.html'

async function main() {
  let files = []

  try {
    const data = await listFiles()
    files = (data && data.files) || []
  } catch (e) {
    // 接口不可用（例如服务没启动）时不打扰用户，api.js 已经处理了 file:// 的提示
    return
  }

  // 已经上传过文件：按需求不加任何按钮
  if (files.length > 0) {
    return
  }

  // 没有文件：添加提示按钮
  const host = document.querySelector('.menu-page') || document.body

  const box = document.createElement('div')
  box.className = 'upload-hint'

  const text = document.createElement('div')
  text.className = 'upload-hint-text'
  text.textContent = '本地仓库中还没有文件，可上传相应文件后再开始使用。'

  const btn = document.createElement('a')
  btn.className = 'upload-hint-btn'
  btn.href = UPLOAD_PAGE
  btn.textContent = '可上传相应文件'

  box.appendChild(text)
  box.appendChild(btn)
  host.appendChild(box)
}

main()
