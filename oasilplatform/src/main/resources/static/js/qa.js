/*
  答题练习页（qa.html）
  流程：列出文件仓库里的文件 -> 选中一个 -> 后端读取内容并提取题目
        -> 按题型渲染答题区 -> 底部题号导航跳转
  作答内容按文件 id 存在浏览器本地（localStorage），刷新不丢。
*/
import { listFiles, loadQuestions } from './api.js'
import { formatSize, typeLabel, iconOf } from './util.js'

const el = (id) => document.getElementById(id)

const picker = el('picker')
const paper = el('paper')
const fileList = el('fileList')
const pickerMsg = el('pickerMsg')
const fileNameEl = el('fileName')
const fileMetaEl = el('fileMeta')
const progressEl = el('progress')
const warningEl = el('warning')
const questionsEl = el('questions')
const previewEl = el('preview')
const navigator = el('navigator')
const navNumbers = el('navNumbers')
const navProgress = el('navProgress')

const TYPE_TEXT = {
  choice: '选择题',
  multi: '多选题',
  judge: '判断题',
  blank: '填空题',
  short: '简答题'
}

let currentFile = null
let questions = []
let answers = {}
let currentNo = 1
let observer = null

/* ==================== 作答记录（本地保存） ==================== */

const storageKey = (id) => 'oasil-qa-answers-' + id

function loadAnswers(id) {
  try {
    return JSON.parse(localStorage.getItem(storageKey(id)) || '{}') || {}
  } catch (e) {
    return {}
  }
}

function saveAnswers() {
  if (!currentFile) return
  try {
    localStorage.setItem(storageKey(currentFile.id), JSON.stringify(answers))
  } catch (e) {
    /* 隐私模式下可能写入失败，忽略 */
  }
}

/** 这道题是否已经作答 */
function isAnswered(q) {
  const v = answers[q.number]
  if (v == null) return false
  if (Array.isArray(v)) return v.some((x) => String(x == null ? '' : x).trim() !== '')
  return String(v).trim() !== ''
}

/* ==================== 文件列表 ==================== */

function showMsg(text) {
  pickerMsg.textContent = text
  pickerMsg.hidden = false
}

function showWarning(text) {
  warningEl.textContent = '⚠ ' + text
  warningEl.hidden = false
}

async function initPicker() {
  let files = []
  try {
    const data = await listFiles()
    files = (data && data.files) || []
  } catch (e) {
    showMsg('读取文件仓库失败：' + e.message)
    return
  }

  if (files.length === 0) {
    showMsg('文件仓库里还没有文件。请先到「文件仓库」上传一份试卷（Word / PDF / 文本），再回来出题。')
    return
  }

  fileList.textContent = ''
  files.forEach((f) => fileList.appendChild(fileCard(f)))
}

function fileCard(f) {
  const card = document.createElement('button')
  card.type = 'button'
  card.className = 'qa-filecard'

  const icon = document.createElement('span')
  icon.className = 'qa-filecard-icon'
  icon.textContent = iconOf(f.originalName)

  const body = document.createElement('span')
  body.className = 'qa-filecard-body'
  const name = document.createElement('span')
  name.className = 'qa-filecard-name'
  name.textContent = f.originalName
  const meta = document.createElement('span')
  meta.className = 'qa-filecard-meta'
  meta.textContent = [typeLabel(f.originalName), formatSize(f.size), f.createdAt].join(' · ')
  body.append(name, meta)

  const arrow = document.createElement('span')
  arrow.className = 'qa-filecard-arrow'
  arrow.textContent = '答题 →'

  card.append(icon, body, arrow)
  card.addEventListener('click', () => openFile(f))
  return card
}

/* ==================== 打开文件并出题 ==================== */

async function openFile(f) {
  currentFile = f
  questions = []
  answers = {}
  currentNo = 1

  picker.hidden = true
  paper.hidden = false
  navigator.hidden = true
  warningEl.hidden = true
  questionsEl.textContent = ''
  fileNameEl.textContent = f.originalName
  fileMetaEl.textContent = [typeLabel(f.originalName), formatSize(f.size), f.createdAt].join(' · ')
  progressEl.textContent = '正在读取内容并提取题目…'
  previewEl.textContent = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })

  let data
  try {
    data = await loadQuestions(f.id)
  } catch (e) {
    progressEl.textContent = ''
    showWarning('读取失败：' + e.message)
    return
  }

  if (!data || !data.ok) {
    progressEl.textContent = ''
    showWarning((data && data.message) || '读取失败')
    return
  }

  questions = data.questions || []
  answers = loadAnswers(f.id)
  previewEl.textContent = data.preview || '（无）'
  if (data.warning) {
    showWarning(data.warning)
  }

  if (questions.length === 0) {
    progressEl.textContent = '没有识别出题目'
    return
  }

  renderPaper()
}

/* ==================== 渲染题目 ==================== */

function renderPaper() {
  questionsEl.textContent = ''
  questions.forEach((q) => questionsEl.appendChild(questionCard(q)))
  buildNavigator()
  updateProgress()
  watchScroll()
}

function questionCard(q) {
  const card = document.createElement('article')
  card.className = 'qa-q'
  card.id = 'q-' + q.number
  card.dataset.no = String(q.number)

  const head = document.createElement('div')
  head.className = 'qa-q-head'
  const no = document.createElement('span')
  no.className = 'qa-q-no'
  no.textContent = q.number
  const tag = document.createElement('span')
  tag.className = 'qa-tag qa-tag-' + q.type
  tag.textContent = TYPE_TEXT[q.type] || '题目'
  head.append(no, tag)

  const stem = document.createElement('div')
  stem.className = 'qa-q-stem'
  stem.textContent = q.stem

  const answer = document.createElement('div')
  answer.className = 'qa-q-answer'

  switch (q.type) {
    case 'choice':
    case 'multi':
      answer.appendChild(buildOptions(q, q.type === 'multi'))
      break
    case 'judge':
      answer.appendChild(buildOptions(q, false, true))
      break
    case 'blank':
      answer.appendChild(buildBlanks(q))
      break
    default:
      answer.appendChild(buildText(q))
  }

  card.append(head, stem, answer)
  return card
}

/** 选择题 / 多选题 / 判断题 的选项区 */
function buildOptions(q, multi, judge) {
  const wrap = document.createElement('div')
  wrap.className = 'qa-opts' + (judge ? ' qa-opts-judge' : '') + (multi ? ' qa-opts-multi' : '')

  const options = (q.options && q.options.length)
    ? q.options
    : [{ label: 'A', text: '√' }, { label: 'B', text: '×' }]

  const refresh = () => {
    const cur = answers[q.number]
    Array.from(wrap.children).forEach((btn) => {
      const on = multi
        ? Array.isArray(cur) && cur.includes(btn.dataset.label)
        : cur === btn.dataset.label
      btn.classList.toggle('selected', !!on)
    })
  }

  options.forEach((op) => {
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'qa-opt'
    btn.dataset.label = op.label

    const lab = document.createElement('span')
    lab.className = 'qa-opt-label'
    lab.textContent = op.label
    const txt = document.createElement('span')
    txt.className = 'qa-opt-text'
    txt.textContent = op.text
    btn.append(lab, txt)

    btn.addEventListener('click', () => {
      if (multi) {
        const cur = Array.isArray(answers[q.number]) ? answers[q.number].slice() : []
        const i = cur.indexOf(op.label)
        if (i >= 0) cur.splice(i, 1)
        else cur.push(op.label)
        cur.sort()
        answers[q.number] = cur
      } else {
        answers[q.number] = op.label
      }
      refresh()
      onAnswerChanged()
    })

    wrap.appendChild(btn)
  })

  refresh()
  return wrap
}

/** 填空题：每题一个或多个输入框 */
function buildBlanks(q) {
  const wrap = document.createElement('div')
  wrap.className = 'qa-blanks'
  const n = Math.max(1, Math.min(q.blanks || 1, 10))
  const stored = Array.isArray(answers[q.number]) ? answers[q.number].slice() : []

  for (let i = 0; i < n; i++) {
    const row = document.createElement('div')
    row.className = 'qa-blank-row'

    const tag = document.createElement('span')
    tag.className = 'qa-blank-tag'
    tag.textContent = n > 1 ? '第 ' + (i + 1) + ' 空' : '答案'

    const input = document.createElement('input')
    input.type = 'text'
    input.className = 'qa-input'
    input.placeholder = '在此填写'
    input.value = stored[i] || ''
    input.addEventListener('input', () => {
      const arr = Array.isArray(answers[q.number]) ? answers[q.number].slice() : []
      while (arr.length < n) arr.push('')
      arr[i] = input.value
      answers[q.number] = arr
      onAnswerChanged()
    })

    row.append(tag, input)
    wrap.appendChild(row)
  }
  return wrap
}

/** 简答题：多行输入 */
function buildText(q) {
  const wrap = document.createElement('div')
  wrap.className = 'qa-blanks'
  const area = document.createElement('textarea')
  area.className = 'qa-textarea'
  area.rows = 4
  area.placeholder = '在此作答'
  area.value = typeof answers[q.number] === 'string' ? answers[q.number] : ''
  area.addEventListener('input', () => {
    answers[q.number] = area.value
    onAnswerChanged()
  })
  wrap.appendChild(area)
  return wrap
}

function onAnswerChanged() {
  saveAnswers()
  updateProgress()
  updateNavState()
}

/* ==================== 进度与题号导航 ==================== */

function buildNavigator() {
  navNumbers.textContent = ''
  questions.forEach((q) => {
    const b = document.createElement('button')
    b.type = 'button'
    b.className = 'qa-nav-no'
    b.textContent = q.number
    b.dataset.no = String(q.number)
    b.addEventListener('click', () => gotoQuestion(q.number))
    navNumbers.appendChild(b)
  })
  navigator.hidden = false
  updateNavState()
}

function updateProgress() {
  const done = questions.filter(isAnswered).length
  progressEl.textContent = '共 ' + questions.length + ' 题 · 已答 ' + done + ' 题'
  navProgress.textContent = done + '/' + questions.length
}

function updateNavState() {
  Array.from(navNumbers.children).forEach((b) => {
    const q = questions.find((x) => String(x.number) === b.dataset.no)
    b.classList.toggle('answered', !!q && isAnswered(q))
    b.classList.toggle('current', String(q && q.number) === String(currentNo))
  })
}

function gotoQuestion(no) {
  currentNo = no
  const card = document.getElementById('q-' + no)
  if (card) {
    card.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
  updateNavState()
}

function step(delta) {
  const idx = questions.findIndex((q) => q.number === currentNo)
  const next = questions[Math.max(0, Math.min(questions.length - 1, (idx < 0 ? 0 : idx) + delta))]
  if (next) {
    gotoQuestion(next.number)
  }
}

/** 滚动时把当前正在看的题号同步到底部导航 */
function watchScroll() {
  if (observer) {
    observer.disconnect()
    observer = null
  }
  if (!('IntersectionObserver' in window)) return
  observer = new IntersectionObserver((entries) => {
    const visible = entries
      .filter((e) => e.isIntersecting)
      .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]
    if (visible) {
      const no = Number(visible.target.dataset.no)
      if (no && no !== currentNo) {
        currentNo = no
        updateNavState()
      }
    }
  }, { rootMargin: '-80px 0px -60% 0px', threshold: 0 })
  questionsEl.querySelectorAll('.qa-q').forEach((c) => observer.observe(c))
}

/* ==================== 事件 ==================== */

el('btnSwitch').addEventListener('click', () => {
  currentFile = null
  questions = []
  answers = {}
  paper.hidden = true
  navigator.hidden = true
  picker.hidden = false
  window.scrollTo({ top: 0 })
  initPicker()
})

el('btnClear').addEventListener('click', () => {
  if (!currentFile || questions.length === 0) return
  if (!window.confirm('确定清空这份试卷的全部作答吗？')) return
  answers = {}
  saveAnswers()
  renderPaper()
  window.scrollTo({ top: 0 })
})

el('btnPrev').addEventListener('click', () => step(-1))
el('btnNext').addEventListener('click', () => step(1))

initPicker()
