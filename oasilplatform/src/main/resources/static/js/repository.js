/* 文件仓库页：上传（读取本地文件）-> 存入项目本地目录 -> 列表查看 / 下载 / 删除 */
import { listFiles, uploadFile, downloadFile, removeFile } from './api.js'
import { formatSize, typeLabel, iconOf, typeClass } from './util.js'

const dropzone = document.getElementById('dropzone')
const fileInput = document.getElementById('fileInput')
const uploadingList = document.getElementById('uploadingList')
const fileBody = document.getElementById('fileBody')
const countEl = document.getElementById('count')
const searchEl = document.getElementById('search')
const typeFilterEl = document.getElementById('typeFilter')

const TYPE_OPTIONS = ['Word', 'Excel', 'PPT', 'PDF', '文本', '图片', '其他']

let files = []

/* ---------------- 列表渲染 ---------------- */

async function load() {
  try {
    const data = await listFiles()
    files = (data && data.files) || []
  } catch (e) {
    files = []
    console.error('加载文件列表失败:', e)
  }
  render()
}

function render() {
  const kw = searchEl.value.trim().toLowerCase()
  const tf = typeFilterEl.value

  const list = files.filter((f) => {
    if (tf && typeLabel(f.originalName) !== tf) return false
    if (kw && !(f.originalName || '').toLowerCase().includes(kw)) return false
    return true
  })

  countEl.textContent = '共 ' + list.length + ' 个文件'
  fileBody.textContent = ''

  if (list.length === 0) {
    const empty = document.createElement('div')
    empty.className = 'empty'
    empty.textContent = files.length === 0 ? '📭 仓库为空，上传一个文件试试吧' : '没有匹配的文件'
    fileBody.appendChild(empty)
    return
  }

  list.forEach((f) => fileBody.appendChild(buildRow(f)))
}

function buildRow(f) {
  const row = document.createElement('div')
  row.className = 'trow'

  // 文件名（图标 + 名称）
  const nameCell = document.createElement('span')
  nameCell.className = 'fname'
  const icon = document.createElement('span')
  icon.className = 'f-icon'
  icon.textContent = iconOf(f.originalName)
  const nameText = document.createElement('span')
  nameText.className = 'f-text'
  nameText.textContent = f.originalName
  nameText.title = f.originalName
  nameCell.append(icon, nameText)

  // 类型
  const typeCell = document.createElement('span')
  typeCell.className = 'col-type'
  const tag = document.createElement('span')
  tag.className = 'tag ' + typeClass(f.originalName)
  tag.textContent = typeLabel(f.originalName)
  typeCell.appendChild(tag)

  // 大小 / 上传者 / 时间
  const sizeCell = document.createElement('span')
  sizeCell.className = 'col-size'
  sizeCell.textContent = formatSize(f.size)

  const ownerCell = document.createElement('span')
  ownerCell.className = 'col-owner'
  ownerCell.textContent = f.owner || '-'

  const timeCell = document.createElement('span')
  timeCell.className = 'col-time'
  timeCell.textContent = f.createdAt || '-'

  // 操作
  const actions = document.createElement('span')
  actions.className = 'col-actions'
  const dlBtn = document.createElement('button')
  dlBtn.className = 'act-btn'
  dlBtn.textContent = '下载'
  dlBtn.addEventListener('click', () => onDownload(f))
  const delBtn = document.createElement('button')
  delBtn.className = 'act-btn danger'
  delBtn.textContent = '删除'
  delBtn.addEventListener('click', () => onDelete(f))
  actions.append(dlBtn, delBtn)

  row.append(nameCell, typeCell, sizeCell, ownerCell, timeCell, actions)
  return row
}

/* ---------------- 上传 ---------------- */

/** 创建一条上传进度行 */
function buildUploadItem(name) {
  const root = document.createElement('div')
  root.className = 'uploading-item'

  const nameEl = document.createElement('span')
  nameEl.className = 'u-name'
  nameEl.textContent = name
  nameEl.title = name

  const bar = document.createElement('div')
  bar.className = 'u-bar'
  const fill = document.createElement('div')
  fill.className = 'u-fill'
  bar.appendChild(fill)

  const status = document.createElement('span')
  status.className = 'u-status'
  status.textContent = '0%'

  root.append(nameEl, bar, status)
  return { root, fill, status }
}

async function uploadFiles(fileList) {
  const arr = Array.from(fileList || [])
  if (arr.length === 0) return

  const items = arr.map((file) => ({ file, ui: buildUploadItem(file.name) }))
  items.forEach((it) => uploadingList.appendChild(it.ui.root))

  const failed = []

  // 逐个上传，便于逐条显示进度
  for (const it of items) {
    try {
      const data = await uploadFile(it.file, (p) => {
        it.ui.fill.style.width = p + '%'
        it.ui.status.textContent = p + '%'
      })
      if (!data || !data.ok) throw new Error((data && data.message) || '上传失败')
      it.ui.fill.style.width = '100%'
      it.ui.status.textContent = '完成'
    } catch (e) {
      it.ui.fill.classList.add('error')
      it.ui.fill.style.width = '100%'
      it.ui.status.classList.add('error')
      it.ui.status.textContent = '失败'
      it.ui.root.title = e.message
      failed.push(it.file.name + '：' + e.message)
    }
  }

  await load()

  if (failed.length) {
    alert('以下文件上传失败：\n' + failed.join('\n'))
  }

  // 进度行短暂保留后清空
  setTimeout(() => { uploadingList.textContent = '' }, 2000)
}

/* ---------------- 下载 / 删除 ---------------- */

async function onDownload(f) {
  try {
    await downloadFile(f.id, f.originalName)
  } catch (e) {
    alert('下载失败：' + e.message)
  }
}

async function onDelete(f) {
  if (!window.confirm('确定删除文件「' + f.originalName + '」吗？')) return
  try {
    await removeFile(f.id)
    await load()
  } catch (e) {
    alert('删除失败：' + e.message)
  }
}

/* ---------------- 事件绑定 ---------------- */

// 类型下拉选项
TYPE_OPTIONS.forEach((t) => {
  const opt = document.createElement('option')
  opt.value = t
  opt.textContent = t
  typeFilterEl.appendChild(opt)
})

dropzone.addEventListener('click', () => fileInput.click())

fileInput.addEventListener('change', () => {
  uploadFiles(fileInput.files)
  fileInput.value = '' // 允许重复选择同一文件
})

dropzone.addEventListener('dragover', (e) => {
  e.preventDefault()
  dropzone.classList.add('dragging')
})

dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragging'))

dropzone.addEventListener('drop', (e) => {
  e.preventDefault()
  dropzone.classList.remove('dragging')
  uploadFiles(e.dataTransfer.files)
})

searchEl.addEventListener('input', render)
typeFilterEl.addEventListener('change', render)

load()
