/* 公共小工具：文件类型判定、大小格式化等（原生 JS，无需构建） */

// 取小写扩展名
export function extOf(name) {
  const i = (name || '').lastIndexOf('.')
  return i < 0 ? '' : name.slice(i + 1).toLowerCase()
}

const TYPE_MAP = {
  doc: 'Word', docx: 'Word',
  xls: 'Excel', xlsx: 'Excel',
  ppt: 'PPT', pptx: 'PPT',
  pdf: 'PDF',
  txt: '文本', md: '文本', csv: '文本', json: '文本', xml: '文本',
  png: '图片', jpg: '图片', jpeg: '图片', gif: '图片', webp: '图片'
}

const ICON_MAP = {
  Word: '📄', Excel: '📊', PPT: '📽️', PDF: '📕', '文本': '📃', '图片': '🖼️', '其他': '📦'
}

/** 文件类型中文名：Word / Excel / PPT / PDF / 文本 / 图片 / 其他 */
export function typeLabel(name) {
  return TYPE_MAP[extOf(name)] || '其他'
}

/** 类型对应的图标 */
export function iconOf(name) {
  return ICON_MAP[typeLabel(name)]
}

/** 类型对应的 CSS 类名（用于标签配色，对应 style.css 里的 .tag.*） */
export function typeClass(name) {
  return {
    Word: 'word',
    Excel: 'excel',
    PPT: 'ppt',
    PDF: 'pdf',
    '文本': 'text',
    '图片': 'img'
  }[typeLabel(name)] || 'other'
}

/** 字节数转可读大小 */
export function formatSize(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  return (n / 1024 / 1024 / 1024).toFixed(1) + ' GB'
}
