/*
  四六级模拟考试页（test.html）

  流程：
    选级别（四级/六级） -> 选年份/月份/套 -> 读取试卷内容 -> 开考
  考试分三阶段，顶部倒计时：
    写作 30 分钟（只看得到作文题目，写作文）
      -> 自动进入听力（播放 MP3，整页列出 Section A/B/C 的题目+选项，底部切换）
      -> 听力结束（音频播完或手动）后作文与听力锁定
      -> 阅读 + 翻译 70 分钟（阅读左文右答，底部 4 个跳转按钮；翻译左题右写）
      -> 时间到自动收卷

  试卷内容优先用 Word 版真题解析成结构化题面（后端 /api/exam/content）；
  没有可解析的 Word 版时，自动切换成「原卷 PDF + 标准答题卡」兜底模式。
*/

const API = '/api/exam'

import { mountReview } from './exam-review.js'

/* ---------- 常量 ---------- */
const WRITING_SECONDS = 30 * 60
const READING_SECONDS = 70 * 60
const CHOICES = ['A', 'B', 'C', 'D']
/** 兜底模式下按四六级标准题号生成答题卡 */
const STD_LISTENING = [1, 25]
const STD_READING_A = [26, 35]
const STD_READING_B = [36, 45]
const STD_READING_C = [46, 55]

const el = (id) => document.getElementById(id)
const CN_NUM = ['一', '二', '三', '四', '五']

/* ---------- 状态 ---------- */
let levels = []
let papers = []
let currentPaper = null
let content = null
let repoDir = ''
let mode = 'structured'          // 'structured' | 'pdf'
let phase = null                 // 'writing' | 'listening' | 'reading' | 'done'
let endAt = null
let timerId = null
let activeListenSection = 0
/** 阅读阶段当前显示的模块（0..n-1 为阅读小节，n 为翻译） */
let activeReadTab = 0
let answers = emptyAnswers()
/** 本场考试实际要走的阶段序列：某部分与其它套重复（试卷里没给）就整段跳过 */
let seq = []
/** 开考时间（用于统计用时） */
let examStartedAt = Date.now()

function emptyAnswers() {
  return { items: {}, writing: '', translation: '' }
}

/* ---------- DOM ---------- */
const stagePicker = el('stagePicker')
const stageExam = el('stageExam')
const stageReview = el('stageReview')
const levelStep = el('levelStep')
const paperStep = el('paperStep')
const levelList = el('levelList')
const paperList = el('paperList')
const pickerMsg = el('pickerMsg')

const examTitle = el('examTitle')
const phaseChip = el('phaseChip')
const timerEl = el('timer')
const phaseBanner = el('phaseBanner')
const qualityNotice = el('qualityNotice')
const loadingTip = el('loadingTip')
const pdfOpen = el('pdfOpen')

const writingPanel = el('writingPanel')
const writingPrompt = el('writingPrompt')
const writingFallback = el('writingFallback')
const writingAnswer = el('writingAnswer')
const listeningPanel = el('listeningPanel')
const listeningBody = el('listeningBody')
const readingPanel = el('readingPanel')
const readingBody = el('readingBody')
const translationPrompt = el('translationPrompt')
const translationFallback = el('translationFallback')
const translationAnswer = el('translationAnswer')
const pdfPanel = el('pdfPanel')
const pdfFrame = el('pdfFrame')
const pdfAnswerBody = el('pdfAnswerBody')
const audioBar = el('audioBar')
const audioEl = el('audioPlayer')
const audioHint = el('audioHint')
const bottomNav = el('bottomNav')
const submitModal = el('submitModal')
const submitText = el('submitText')

/* ==================== 通用小工具 ==================== */
function node(tag, cls, text) {
  const n = document.createElement(tag)
  if (cls) {
    n.className = cls
  }
  if (text != null) {
    n.textContent = text
  }
  return n
}

async function examFetch(path) {
  const res = await fetch(API + path)
  if (!res.ok) {
    throw new Error('HTTP ' + res.status)
  }
  return res.json()
}

function fmt(s) {
  const m = Math.floor(s / 60)
  const ss = s % 60
  return String(m).padStart(2, '0') + ':' + String(ss).padStart(2, '0')
}




/* ==================== 作答记录 / 会话 ==================== */
const answersKey = (id) => 'oasil-exam-answers-' + id
const sessionKey = 'oasil-exam-session'

function loadAnswers(id) {
  try {
    const raw = JSON.parse(localStorage.getItem(answersKey(id)) || '{}')
    const base = emptyAnswers()
    base.writing = typeof raw.writing === 'string' ? raw.writing : ''
    base.translation = typeof raw.translation === 'string' ? raw.translation : ''
    base.items = raw.items && typeof raw.items === 'object' ? raw.items : {}
    return base
  } catch (e) {
    return emptyAnswers()
  }
}

function saveAnswers() {
  if (!currentPaper) {
    return
  }
  try {
    localStorage.setItem(answersKey(currentPaper.id), JSON.stringify(answers))
  } catch (e) {
    /* 隐私模式可能写失败，忽略 */
  }
}

function saveSession() {
  if (!currentPaper) {
    return
  }
  try {
    localStorage.setItem(sessionKey, JSON.stringify({
      paperId: currentPaper.id,
      phase: phase,
      endAt: endAt
    }))
  } catch (e) {
    /* 忽略 */
  }
}

function loadSession() {
  try {
    return JSON.parse(localStorage.getItem(sessionKey) || 'null')
  } catch (e) {
    return null
  }
}

function clearSession() {
  try {
    localStorage.removeItem(sessionKey)
  } catch (e) {
    /* 忽略 */
  }
}

/** 清空某一套试卷上一次的作答记录（重新考试时用） */
function clearAnswers(id) {
  try {
    localStorage.removeItem(answersKey(id))
  } catch (e) {
    /* 忽略 */
  }
}

function removeResumeBar() {
  levelStep.querySelectorAll('.exam-resume').forEach((n) => n.remove())
}

/* ==================== 选卷 ==================== */
async function loadLevels() {
  levels = []
  repoDir = ''
  try {
    const d = await examFetch('/levels')
    levels = (d && d.levels) || []
    repoDir = (d && d.repoDir) || ''
  } catch (e) {
    levels = []
  }
  renderLevels()
}

function renderLevels() {
  levelList.textContent = ''
  pickerMsg.hidden = true
  if (levels.length === 0) {
    pickerMsg.textContent = '仓库里还没有四六级试题。'
      + (repoDir ? '后端当前使用的试卷目录是：' + repoDir + '。' : '')
      + '请把真题按 CET-4 / CET-6 目录结构放进该目录（每套卷的文件夹名带「XXXX年XX月」，'
      + '真题文件名带「真题」和「第X套」）后再回来。'
    pickerMsg.hidden = false
    return
  }
  levels.forEach((l) => {
    const b = node('button', 'level-card')
    b.type = 'button'
    b.append(node('span', 'level-name', l.name), node('span', 'level-count', l.paperCount + ' 套试卷'))
    b.addEventListener('click', () => selectLevel(l))
    levelList.appendChild(b)
  })
}

async function selectLevel(l) {
  papers = []
  try {
    const d = await examFetch('/papers?level=' + encodeURIComponent(l.key))
    papers = (d && d.papers) || []
  } catch (e) {
    papers = []
  }
  renderPapers()
  levelStep.hidden = true
  paperStep.hidden = false
}

function renderPapers() {
  paperList.textContent = ''
  if (papers.length === 0) {
    paperList.appendChild(node('div', 'exam-msg', '该级别下还没有可用的试卷。'))
    return
  }
  const groups = new Map()
  papers.forEach((p) => {
    const k = p.year + '-' + p.month
    if (!groups.has(k)) {
      groups.set(k, [])
    }
    groups.get(k).push(p)
  })
  groups.forEach((list) => {
    const group = node('div', 'paper-group')
    const p0 = list[0]
    group.appendChild(node('div', 'paper-group-title', p0.year + ' 年 ' + p0.month + ' 月'))
    const sets = node('div', 'paper-sets')
    list.forEach((p) => {
      const b = node('button', 'set-btn', '第 ' + p.set + ' 套')
      b.type = 'button'
      if (!p.hasAudio) {
        b.title = '该套未匹配到听力音频'
        b.dataset.noAudio = '1'
      }
      b.addEventListener('click', () => selectPaper(p))
      sets.appendChild(b)
    })
    group.appendChild(sets)
    paperList.appendChild(group)
  })
}

function selectPaper(p) {
  const flow = '写作 30 分钟 → 听力（播放音频）→ 阅读 + 翻译 70 分钟，时间到自动收卷。'
  if (!window.confirm('确定开始「' + p.title + '」吗？\n\n' + flow)) {
    return
  }
  startExam(p)
}

/* ==================== 开考 ==================== */
async function startExam(p, resume) {
  stopTimer()
  submitModal.hidden = true
  try {
    audioEl.pause()
  } catch (e) {
    /* 忽略 */
  }
  removeResumeBar()

  currentPaper = p
  if (resume) {
    // 继续未完成的考试：沿用之前的作答
    answers = loadAnswers(p.id)
  } else {
    // 重新考试同一套卷：清空上一次的作答记录，从空白开始
    answers = emptyAnswers()
    clearAnswers(p.id)
  }
  content = null
  mode = 'structured'
  activeListenSection = 0
  activeReadTab = 0
  examStartedAt = Date.now()
  stageReview.hidden = true

  stagePicker.hidden = true
  stageExam.hidden = false
  examTitle.textContent = p.title
  pdfOpen.href = API + '/file?id=' + encodeURIComponent(p.id) + '&kind=pdf'
  pdfOpen.hidden = !p.hasPdf
  pdfFrame.src = API + '/file?id=' + encodeURIComponent(p.id) + '&kind=pdf'

  loadingTip.hidden = false
  setPanelsHidden(true)

  try {
    const d = await examFetch('/content?id=' + encodeURIComponent(p.id))
    content = (d && d.content) || null
  } catch (e) {
    content = null
  }
  loadingTip.hidden = true

  if (!content) {
    content = { quality: 'none', warning: '读取试卷内容失败，已切换为「原卷 PDF」模式。', skipped: [] }
  }

  // 结构化模式：只要解析出了任何一部分内容就用结构化模式，并把「没给的部分」整段跳过
  const hasAny = hasWriting() || hasListening() || hasReading() || hasTranslation()
  mode = hasAny ? 'structured' : 'pdf'

  seq = mode === 'pdf'
    ? ['writing', 'listening', 'reading']
    : buildSequence()

  renderNotice()
  if (mode === 'structured') {
    renderStructured()
  }

  if (resume && phase && seq.includes(phase)) {
    resumePhase()
  } else {
    enterPhase(seq[0])
  }
}

/** 按「本套实际有哪些部分」排出考试阶段：缺哪部分就整段跳过 */
function buildSequence() {
  const out = []
  if (hasWriting()) {
    out.push('writing')
  }
  if (hasListening()) {
    out.push('listening')
  }
  if (hasReading() || hasTranslation()) {
    out.push('reading')
  }
  return out.length ? out : ['writing', 'listening', 'reading']
}

function hasWriting() {
  return !!(content && (content.writingDirections || '').trim())
}

function hasListening() {
  return !!(content && (content.listening || []).length)
}

function hasReading() {
  return !!(content && (content.reading || []).length)
}

function hasTranslation() {
  return !!(content && (content.translationText || '').trim())
}

/** 该部分是否属于「试卷没给（与其它套重复）」而被跳过 */
function isSkipped(part) {
  return !!(content && (content.skipped || []).includes(part))
}

function setPanelsHidden(hidden) {
  writingPanel.hidden = hidden
  listeningPanel.hidden = hidden
  readingPanel.hidden = hidden
  pdfPanel.hidden = hidden
  bottomNav.hidden = true
  audioBar.hidden = true
}

function renderNotice() {
  const notes = []
  if (mode === 'pdf') {
    notes.push('这份试卷没有可解析的 Word 版，已切换为「原卷 PDF + 标准答题卡」模式：上面看原卷，下面按标准题号作答。')
  } else {
    const names = (content.skipped || [])
      .map((s) => ({ listening: '听力', reading: '阅读', writing: '写作', translation: '翻译' }[s] || s))
    if (names.length) {
      notes.push('本套试卷的 Word 版没有包含「' + names.join('、') + '」（原文注明该部分与其它套重复），已自动跳过对应环节。')
    }
    if (content.notice) {
      notes.push('试卷原文备注：' + content.notice)
    }
  }
  if (notes.length === 0) {
    qualityNotice.hidden = true
    return
  }
  qualityNotice.textContent = 'ℹ ' + notes.join(' ')
  qualityNotice.hidden = false
}

/* ==================== 结构化内容渲染 ==================== */
function renderStructured() {
  // 写作题目
  const wd = (content.writingDirections || '').trim()
  writingPrompt.textContent = wd
  writingPrompt.hidden = !wd
  writingFallback.hidden = !!wd || isSkipped('writing')

  // 翻译题目：整段没给（与其它套重复）时直接隐藏整个翻译区
  const tt = (content.translationText || '').trim()
  const tBlock = el('translateBlock')
  if (tt) {
    tBlock.hidden = false
    translationPrompt.textContent = tt
    translationPrompt.hidden = false
    translationFallback.hidden = true
  } else if (isSkipped('translation')) {
    tBlock.hidden = true
  } else {
    tBlock.hidden = false
    translationPrompt.textContent = ''
    translationPrompt.hidden = true
    translationFallback.hidden = false
  }

  renderListening()
  renderReading()

  writingAnswer.value = answers.writing || ''
  translationAnswer.value = answers.translation || ''
  updateCounts()
}

function renderListening() {
  listeningBody.textContent = ''
  const sections = (content.listening || [])
  if (sections.length === 0) {
    if (isSkipped('listening')) {
      return
    }
    listeningBody.appendChild(node('div', 'split-fallback',
      '本套试卷的 Word 版里没有包含听力题目。'
      + '请点右上角「查看原卷 PDF」对照原卷作答。'))
    return
  }
  sections.forEach((sec, i) => {
    const wrap = node('section', 'listen-section')
    wrap.id = 'listen-sec-' + i
    wrap.hidden = i !== activeListenSection
    wrap.appendChild(node('h3', 'listen-head', sec.name))
    if (sec.directions) {
      wrap.appendChild(node('p', 'paper-directions', sec.directions))
    }
    let lastGroup = null
    ;(sec.questions || []).forEach((q) => {
      if (q.group && q.group !== lastGroup) {
        wrap.appendChild(node('div', 'listen-group', q.group))
        lastGroup = q.group
      }
      wrap.appendChild(buildQuestion(q, false))
    })
    listeningBody.appendChild(wrap)
  })
}

function renderReading() {
  readingBody.textContent = ''
  const sections = (content.reading || [])

  if (sections.length === 0 && !isSkipped('reading')) {
    readingBody.appendChild(node('div', 'split-fallback',
      '本套试卷的 Word 版里没有可解析的阅读题目。请点右上角「查看原卷 PDF」对照原卷作答。'))
  }

  // 每个小节单独成块，底部按钮切换显示（一次只显示当前这一块）
  sections.forEach((sec, si) => {
    const wrap = node('section', 'read-section')
    wrap.id = 'read-sec-' + si
    wrap.hidden = true
    const kindName = sec.kind === 'banked' ? '选词填空' : sec.kind === 'matching' ? '段落匹配' : '仔细阅读'
    wrap.appendChild(node('h3', 'read-head', sec.name + ' · ' + kindName))
    if (sec.directions) {
      wrap.appendChild(node('p', 'paper-directions', sec.directions))
    }
    ;(sec.blocks || []).forEach((block) => wrap.appendChild(buildReadingRow(sec, block)))
    readingBody.appendChild(wrap)
  })
}

/** 阅读一小节里的一行：左边文章（可上下滚动），右边答题区（与文章区等高、各自滚动） */
function buildReadingRow(sec, block) {
  const row = node('div', 'exam-split read-row')
  const left = node('div', 'split-col')
  const right = node('div', 'split-col')

  const leftScroll = node('div', 'panel-scroll')
  if (block.title) {
    leftScroll.appendChild(node('h4', 'read-block-title', block.title))
  }
  const text = node('div', 'paper-text')
  text.textContent = (block.text || '').trim() || '（未识别到文章内容，请点右上角查看原卷 PDF）'
  leftScroll.appendChild(text)
  left.appendChild(leftScroll)

  const rightScroll = node('div', 'panel-scroll')
  if (block.wordBank && block.wordBank.length) {
    rightScroll.appendChild(node('h4', 'read-block-title', '词库（选词填空）'))
    const bank = node('div', 'word-bank')
    // 原卷常是双栏排版，取出来是 A、I、B、J… 的顺序，这里按字母重排更易读
    block.wordBank.slice().sort(byLabel).forEach((w) => bank.appendChild(node('span', 'word-item', w)))
    rightScroll.appendChild(bank)
  }

  const letterMode = sec.kind === 'banked' || sec.kind === 'matching'
  if (letterMode) {
    rightScroll.appendChild(node('p', 'letter-tip',
      sec.kind === 'matching' ? '填对应段落的字母' : '填词库里单词的字母'))
  }
  const qs = block.questions || []
  if (qs.length === 0) {
    rightScroll.appendChild(node('div', 'split-fallback', '这一篇没有识别出题目，请对照原卷。'))
  }
  if (sec.kind === 'banked') {
    // 选词填空：两列，左列 5 个、右列 5 个
    const grid = node('div', 'bank-grid')
    qs.forEach((q) => grid.appendChild(buildQuestion(q, true)))
    rightScroll.appendChild(grid)
  } else {
    qs.forEach((q) => rightScroll.appendChild(buildQuestion(q, letterMode)))
  }
  right.appendChild(rightScroll)

  row.append(left, right)
  return row
}

/** 一道题：有选项就渲染成 A/B/C/D 按钮，没有选项就渲染成字母填空 */
function buildQuestion(q, letterMode) {
  const box = node('div', 'qbox')
  const head = node('div', 'qhead')
  head.appendChild(node('span', 'qno', String(q.number)))
  if (q.stem) {
    head.appendChild(node('span', 'qstem', q.stem))
  }
  box.appendChild(head)

  const options = q.options || []
  if (letterMode || options.length === 0) {
    const row = node('div', 'qletter-row')
    const input = document.createElement('input')
    input.type = 'text'
    input.className = 'ans-letter'
    input.maxLength = 1
    input.placeholder = '—'
    input.value = answers.items[q.number] || ''
    input.addEventListener('input', () => {
      const v = input.value.toUpperCase().replace(/[^A-Z]/g, '')
      input.value = v
      answers.items[q.number] = v
      onAnswerChanged()
    })
    row.appendChild(input)
    if (!letterMode) {
      row.appendChild(node('span', 'letter-tip', '填字母'))
    }
    box.appendChild(row)
    return box
  }

  const opts = node('div', 'qopts')
  // 双栏排版取出来的选项可能是 A、C、B、D 的顺序，按字母重排
  options.slice().sort((a, b) => a.label.localeCompare(b.label)).forEach((o) => {
    const b = node('button', 'opt-btn')
    b.type = 'button'
    b.dataset.v = o.label
    b.append(node('span', 'opt-label', o.label), node('span', 'opt-text', o.text || ''))
    b.addEventListener('click', () => {
      answers.items[q.number] = o.label
      refreshQ(box, q.number)
      onAnswerChanged()
    })
    opts.appendChild(b)
  })
  box.appendChild(opts)
  refreshQ(box, q.number)
  return box
}

function refreshQ(box, number) {
  const cur = answers.items[number]
  box.querySelectorAll('.opt-btn').forEach((b) => {
    b.classList.toggle('selected', b.dataset.v === cur)
  })
}

/** 词库条目「A) word」按字母排序用 */
function byLabel(a, b) {
  return String(a).localeCompare(String(b))
}

function onAnswerChanged() {
  saveAnswers()
}

function updateCounts() {
  const wc = el('writingCount')
  const tc = el('translationCount')
  if (wc) {
    wc.textContent = countWords(answers.writing)
  }
  if (tc) {
    tc.textContent = countWords(answers.translation)
  }
}

/** 单词数：以空白分隔，两个空格之间算一个单词 */
function countWords(text) {
  const t = (text || '').trim()
  return t ? t.split(/\s+/).length : 0
}

/* ==================== PDF 兜底模式的答题卡 ==================== */
function renderPdfAnswers() {
  pdfAnswerBody.textContent = ''
  pdfAnswerBody.dataset.phase = phase
  if (!content) {
    return
  }

  if (phase === 'writing') {
    const wrap = node('div', 'pdf-answer-block')
    wrap.appendChild(node('h4', 'read-block-title', 'Part I 写作（在原卷上找题目，在此作答）'))
    const area = document.createElement('textarea')
    area.className = 'sheet-textarea tall'
    area.placeholder = '在此输入作文…'
    area.value = answers.writing || ''
    area.addEventListener('input', () => {
      answers.writing = area.value
      updateCounts()
      onAnswerChanged()
    })
    wrap.appendChild(area)
    pdfAnswerBody.appendChild(wrap)
    return
  }

  if (phase === 'listening') {
    pdfAnswerBody.appendChild(node('h4', 'read-block-title', 'Part II 听力答题卡（1-25 题）'))
    choiceRange(STD_LISTENING[0], STD_LISTENING[1]).forEach((q) => {
      pdfAnswerBody.appendChild(buildQuestion(q, false))
    })
    return
  }

  if (phase === 'reading') {
    pdfAnswerBody.appendChild(node('h4', 'read-block-title', 'Part III 阅读答题卡'))
    pdfAnswerBody.appendChild(node('p', 'letter-tip', 'Section A 选词填空（26-35）· 填字母'))
    letterRange(STD_READING_A[0], STD_READING_A[1]).forEach((q) => {
      pdfAnswerBody.appendChild(buildQuestion(q, true))
    })
    pdfAnswerBody.appendChild(node('p', 'letter-tip', 'Section B 段落匹配（36-45）· 填段落字母'))
    letterRange(STD_READING_B[0], STD_READING_B[1]).forEach((q) => {
      pdfAnswerBody.appendChild(buildQuestion(q, true))
    })
    pdfAnswerBody.appendChild(node('p', 'letter-tip', 'Section C 仔细阅读（46-55）'))
    choiceRange(STD_READING_C[0], STD_READING_C[1]).forEach((q) => {
      pdfAnswerBody.appendChild(buildQuestion(q, false))
    })

    pdfAnswerBody.appendChild(node('h4', 'read-block-title', 'Part IV 翻译'))
    const area = document.createElement('textarea')
    area.className = 'sheet-textarea'
    area.rows = 10
    area.placeholder = '在此输入译文…'
    area.value = answers.translation || ''
    area.addEventListener('input', () => {
      answers.translation = area.value
      updateCounts()
      onAnswerChanged()
    })
    pdfAnswerBody.appendChild(area)
  }
}

function letterRange(start, end) {
  return plainRange(start, end).map((n) => ({ number: n, stem: '', options: [], group: null }))
}

function choiceRange(start, end) {
  return plainRange(start, end).map((n) => ({
    number: n,
    stem: '',
    options: CHOICES.map((l) => ({ label: l, text: '' })),
    group: null
  }))
}

function plainRange(start, end) {
  const out = []
  for (let n = start; n <= end; n++) {
    out.push(n)
  }
  return out
}

/* ==================== 阶段切换 ==================== */
/** 进入某个阶段（按 seq 里排好的顺序） */
function enterPhase(name, remainingMs) {
  if (name === 'writing') {
    enterWriting(remainingMs)
  } else if (name === 'listening') {
    enterListening()
  } else if (name === 'reading') {
    enterReading(remainingMs)
  } else {
    finishExam()
  }
}

/** 推进到下一个阶段；已经是最后一个了就直接收卷 */
function advance() {
  const i = seq.indexOf(phase)
  const next = seq[i + 1]
  if (!next) {
    finishExam()
    return
  }
  enterPhase(next)
}

function enterWriting(remainingMs) {
  phase = 'writing'
  endAt = Date.now() + (remainingMs != null ? remainingMs : WRITING_SECONDS * 1000)
  refreshUI()
  startTimer()
  saveSession()
}

function enterListening() {
  phase = 'listening'
  endAt = null
  refreshUI()
  startTimer()
  saveSession()
  tryPlayAudio()
}

function enterReading(remainingMs) {
  phase = 'reading'
  endAt = Date.now() + (remainingMs != null ? remainingMs : READING_SECONDS * 1000)
  refreshUI()
  startTimer()
  saveSession()
}

function finishExam() {
  phase = 'done'
  stopTimer()
  refreshUI()
  clearSession()
  submitAndReview()
}

/** 交卷 -> 交给后端按解析文件批改 -> 直接展示批改回顾 */
async function submitAndReview() {
  if (!currentPaper) {
    return
  }
  loadingTip.hidden = false
  loadingTip.textContent = '正在按解析文件批改…'
  let payload = null
  let saveError = ''
  try {
    const res = await fetch(API + '/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        paperId: currentPaper.id,
        answers: answers.items,
        writing: answers.writing || '',
        translation: answers.translation || '',
        durationSec: totalDuration()
      })
    })
    const d = await res.json()
    if (d && d.ok && d.recordId) {
      payload = await examFetch('/records/' + d.recordId)
    } else if (d && d.saveError) {
      saveError = d.saveError
    }
  } catch (e) {
    payload = null
    saveError = e.message || String(e)
  }
  loadingTip.hidden = true

  if (!payload) {
    submitText.textContent = '已完成「' + currentPaper.title + '」。但考试记录保存失败，作答已保存在本地浏览器。'
      + (saveError ? '\n\n原因：' + saveError : '')
    submitModal.hidden = false
    return
  }
  showReview(payload)
}

function totalDuration() {
  const end = Date.now()
  return Math.max(0, Math.round((end - examStartedAt) / 1000))
}

function showReview(payload) {
  stageExam.hidden = true
  stageReview.hidden = false
  mountReview(stageReview, payload, {
    showBackToList: true,
    backLabel: '← 重新选卷',
    menuLabel: '主页',
    onBack: () => {
      stageReview.hidden = true
      stageExam.hidden = true
      stagePicker.hidden = false
      levelStep.hidden = false
      paperStep.hidden = true
      currentPaper = null
      content = null
      phase = null
      window.scrollTo({ top: 0 })
      loadLevels()
    }
  })
}

function refreshUI() {
  const structured = mode === 'structured'

  writingPanel.hidden = !(structured && phase === 'writing')
  listeningPanel.hidden = !(structured && phase === 'listening')
  readingPanel.hidden = !(structured && phase === 'reading')
  pdfPanel.hidden = structured || phase === 'done'

  audioBar.hidden = phase !== 'listening'

  if (!structured && phase !== 'done') {
    renderPdfAnswers()
  }

  renderBottomNav()
  if (structured && phase === 'reading') {
    const n = ((content && content.reading) || []).length
    if (activeReadTab > n) {
      activeReadTab = 0
    }
    showReadingTab(activeReadTab)
  }
  applyLocks()
  updateChip()
  updateBanner()
  updateTimerLabel()
  updateCounts()
}

/** 底部条：听力阶段切换 Section A/B/C；阅读阶段跳转阅读一/二/三 + 翻译 */
function renderBottomNav() {
  bottomNav.textContent = ''
  if (mode !== 'structured') {
    bottomNav.hidden = true
    return
  }
  if (phase === 'listening') {
    const sections = content.listening || []
    sections.forEach((sec, i) => {
      const b = node('button', 'exam-jump', sec.name)
      b.type = 'button'
      b.dataset.sec = String(i)
      if (i === activeListenSection) {
        b.classList.add('active')
      }
      b.addEventListener('click', () => showListenSection(i))
      bottomNav.appendChild(b)
    })
    // 音频没播完/加载失败时也要能手动进入下一部分
    const adv = node('button', 'exam-jump primary', '听力结束 → 下一部分')
    adv.type = 'button'
    adv.addEventListener('click', () => advance())
    bottomNav.appendChild(adv)
    bottomNav.hidden = false
    return
  }
  if (phase === 'reading') {
    const sections = content.reading || []
    sections.forEach((sec, i) => {
      const kindName = sec.kind === 'banked' ? '选词填空' : sec.kind === 'matching' ? '段落匹配' : '仔细阅读'
      const b = node('button', 'exam-jump', '阅读' + (CN_NUM[i] || (i + 1)) + ' ' + kindName)
      b.type = 'button'
      b.dataset.read = String(i)
      b.addEventListener('click', () => showReadingTab(i))
      bottomNav.appendChild(b)
    })
    if (hasTranslation()) {
      const tb = node('button', 'exam-jump', '翻译')
      tb.type = 'button'
      tb.dataset.read = String(sections.length)
      tb.addEventListener('click', () => showReadingTab(sections.length))
      bottomNav.appendChild(tb)
    }
    bottomNav.hidden = sections.length === 0 && !hasTranslation()
    return
  }
  bottomNav.hidden = true
}

/** 阅读阶段：底部按钮直接切换到对应模块，一次只显示该模块的题目 */
function showReadingTab(i) {
  activeReadTab = i
  const sections = (content && content.reading) || []
  sections.forEach((sec, k) => {
    const box = el('read-sec-' + k)
    if (box) {
      box.hidden = k !== i
    }
  })
  const tBlock = el('translateBlock')
  if (tBlock) {
    tBlock.hidden = !(hasTranslation() && i === sections.length)
  }
  bottomNav.querySelectorAll('.exam-jump[data-read]').forEach((b) => {
    b.classList.toggle('active', Number(b.dataset.read) === i)
  })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function showListenSection(i) {
  activeListenSection = i
  listeningBody.querySelectorAll('.listen-section').forEach((s, k) => {
    s.hidden = k !== i
  })
  bottomNav.querySelectorAll('.exam-jump[data-sec]').forEach((b) => {
    b.classList.toggle('active', Number(b.dataset.sec) === i)
  })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function scrollTo(target) {
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

/** 锁定：只有当前阶段的输入可改，其它阶段一律禁用 */
function applyLocks() {
  stageExam.querySelectorAll('[data-phase]').forEach((box) => {
    const on = box.dataset.phase === phase
    const targets = []
    if (box.matches('input, textarea, button')) {
      targets.push(box)
    }
    box.querySelectorAll('input, textarea, button.opt-btn').forEach((n) => targets.push(n))
    targets.forEach((n) => {
      n.disabled = !on
    })
  })
}

function updateChip() {
  const hasListen = seq.includes('listening')
  const map = {
    // writing: hasListen ? '写作 · 30 分钟' : '写作 · 30 分钟（本套无听力）',
    if(hasListen)
    {writing: '本套无听力'},
    // listening: '听力 · 播放中',
    // reading: '阅读 + 翻译 · 70 分钟',
    done: '已交卷'
  }
  phaseChip.textContent = map[phase] || ''
  phaseChip.className = 'phase-chip phase-' + phase
}

function updateBanner() {
  const hasListen = seq.includes('listening')
  const map = {
    writing: hasListen
      ? '✍ 写作阶段：请先完成作文（30 分钟），时间到自动进入听力。'
      : '✍ 写作阶段：请先完成作文（30 分钟）。本套听力与其它套重复，已跳过听力，时间到直接进入阅读与翻译。',
    listening: '🎧 听力阶段：点击播放键开始听力，用底部按钮切换 Section A / B / C。播放结束后作文与听力将锁定，自动进入阅读与翻译。',
    reading: '📖 阅读 + 翻译阶段（70 分钟）：左边读文章、右边作答；用底部按钮在阅读一 / 二 / 三 / 翻译之间跳转。',
    done: '📮 考试结束，已自动收卷。'
  }
  phaseBanner.textContent = map[phase] || ''
}

function updateTimerLabel() {
  if (phase === 'writing' || phase === 'reading') {
    const remain = Math.max(0, Math.round((endAt - Date.now()) / 1000))
    timerEl.textContent = fmt(remain)
    timerEl.classList.toggle('danger', remain <= 60)
  } else if (phase === 'listening') {
    timerEl.textContent = '听力进行中'
    timerEl.classList.remove('danger')
  } else {
    timerEl.textContent = '00:00'
    timerEl.classList.remove('danger')
  }
}

/* ==================== 计时 ==================== */
function startTimer() {
  stopTimer()
  timerId = setInterval(timerTick, 250)
}

function stopTimer() {
  if (timerId) {
    clearInterval(timerId)
    timerId = null
  }
}

function timerTick() {
  if (phase === 'done') {
    return
  }
  if (phase === 'listening') {
    updateTimerLabel()
    return
  }
  const remain = endAt - Date.now()
  updateTimerLabel()
  if (remain <= 0) {
    advance()
  }
}

/* ==================== 听力音频 ==================== */
function tryPlayAudio() {
  audioBar.hidden = false
  if (!currentPaper || !currentPaper.hasAudio) {
    audioEl.hidden = true
    audioHint.hidden = false
    audioHint.textContent = '本套未匹配到听力音频，可直接点底部按钮进入阅读与翻译。'
    return
  }
  audioEl.hidden = false
  audioEl.src = API + '/file?id=' + encodeURIComponent(currentPaper.id) + '&kind=audio'
  audioHint.hidden = false
  audioHint.textContent = '请点击播放键开始听力（浏览器可能禁止自动播放）。'
  audioEl.load()
  const p = audioEl.play()
  if (p && typeof p.catch === 'function') {
    p.catch(() => { /* 被自动播放策略拦截，用户手动点播放即可 */ })
  }
}

audioEl.addEventListener('ended', () => {
  if (phase === 'listening') {
    advance()
  }
})

audioEl.addEventListener('error', () => {
  if (phase !== 'listening') {
    return
  }
  audioHint.hidden = false
  audioHint.textContent = '听力音频加载失败，可直接点底部按钮进入阅读与翻译。'
})

/* ==================== 弹窗 / 返回 ==================== */
el('btnBackHome').addEventListener('click', () => {
  window.location.href = 'menu.html'
})

el('btnNewPaper').addEventListener('click', () => {
  submitModal.hidden = true
  stopTimer()
  clearSession()
  removeResumeBar()
  currentPaper = null
  content = null
  phase = null
  stageExam.hidden = true
  stagePicker.hidden = false
  levelStep.hidden = false
  paperStep.hidden = true
  window.scrollTo({ top: 0 })
  loadLevels()
})

el('btnBackLevel').addEventListener('click', () => {
  levelStep.hidden = false
  paperStep.hidden = true
})

/* ==================== 恢复未完成的考试 ==================== */
function resumePhase() {
  const s = loadSession()
  if (!s) {
    enterPhase(seq[0])
    return
  }
  const saved = seq.includes(s.phase) ? s.phase : seq[0]
  if (saved === 'writing' || saved === 'reading') {
    if (s.endAt && Date.now() < s.endAt) {
      enterPhase(saved, s.endAt - Date.now())
    } else {
      // 离开期间这个阶段已经超时了，按顺序推进
      phase = saved
      advance()
    }
    return
  }
  enterPhase(saved)
}

function checkResume() {
  const s = loadSession()
  if (!s || !s.paperId || s.phase === 'done') {
    clearSession()
    removeResumeBar()
    return
  }
  removeResumeBar()
  const bar = node('div', 'exam-resume')
  bar.appendChild(node('span', null, '检测到一次未完成的考试，是否继续？'))
  const yes = node('button', 'exam-btn', '继续考试')
  yes.type = 'button'
  const no = node('button', 'exam-btn ghost', '放弃')
  no.type = 'button'
  bar.append(yes, no)
  levelStep.prepend(bar)

  yes.addEventListener('click', () => resumePaper(s))
  no.addEventListener('click', () => {
    clearSession()
    bar.remove()
  })
}

async function resumePaper(s) {
  try {
    const d = await examFetch('/paper/' + encodeURIComponent(s.paperId))
    const p = d && d.paper
    if (!p) {
      clearSession()
      return
    }
    phase = s.phase
    endAt = s.endAt
    await startExam(p, true)
  } catch (e) {
    clearSession()
  }
}

/* ==================== 初始化 ==================== */
function bindTextareas() {
  writingAnswer.dataset.phase = 'writing'
  translationAnswer.dataset.phase = 'reading'
  listeningBody.dataset.phase = 'listening'
  readingBody.dataset.phase = 'reading'

  writingAnswer.addEventListener('input', () => {
    answers.writing = writingAnswer.value
    updateCounts()
    onAnswerChanged()
  })
  translationAnswer.addEventListener('input', () => {
    answers.translation = translationAnswer.value
    updateCounts()
    onAnswerChanged()
  })
}

async function init() {
  bindTextareas()
  await loadLevels()
  checkResume()
}

init()
