/*
  考试记录页（logs.html）
  原来的「聊天日志统计」已移除，改为展示模拟考试的答题记录：
    - 顶部概览：考试次数、听力/阅读平均正确率、最近一次考试
    - 记录列表：试卷、时间、用时、听力/阅读得分、正确率
    - 点「回顾」→ 用共享的批改回顾组件展示逐题对错、解析、原文与译文
*/
import { mountReview } from './exam-review.js'

const API = '/api/exam'
const el = (id) => document.getElementById(id)

const stats = el('stats')
const listView = el('listView')
const reviewView = el('reviewView')
const reviewRoot = el('reviewRoot')
const recordList = el('recordList')

const node = (tag, cls, text) => {
  const n = document.createElement(tag)
  if (cls) n.className = cls
  if (text != null) n.textContent = text
  return n
}

const pct = (correct, total) => (total > 0 ? Math.round((correct / total) * 100) + '%' : '-')

function fmtDuration(sec) {
  const s = Number(sec) || 0
  if (s <= 0) return '-'
  const m = Math.floor(s / 60)
  return m >= 60 ? Math.floor(m / 60) + ' 小时 ' + (m % 60) + ' 分' : m + ' 分钟'
}

/* ---------------- 概览 ---------------- */
function renderStats(records) {
  el('statCount').textContent = records.length
  let lTotal = 0
  let lRight = 0
  let rTotal = 0
  let rRight = 0
  records.forEach((r) => {
    lTotal += r.listeningTotal || 0
    lRight += r.listeningCorrect || 0
    rTotal += r.readingTotal || 0
    rRight += r.readingCorrect || 0
  })
  el('statListen').textContent = pct(lRight, lTotal)
  el('statRead').textContent = pct(rRight, rTotal)
  el('statLast').textContent = records.length ? (records[0].submittedAt || '-') : '-'
}

/* ---------------- 列表 ---------------- */
function renderList(records) {
  recordList.textContent = ''
  if (records.length === 0) {
    const empty = node('div', 'empty')
    empty.textContent = '还没有考试记录。去「模拟考试」做一套，交卷后这里就会出现记录。'
    recordList.appendChild(empty)
    return
  }
  records.forEach((r) => {
    const card = node('div', 'rec-card')

    const left = node('div', 'rec-main')
    left.appendChild(node('div', 'rec-title', r.paperTitle))
    const meta = node('div', 'rec-meta')
    meta.appendChild(node('span', null, r.submittedAt || '-'))
    meta.appendChild(node('span', null, '用时 ' + fmtDuration(r.durationSec)))
    meta.appendChild(node('span', null, '听力 ' + r.listeningCorrect + '/' + r.listeningTotal))
    meta.appendChild(node('span', null, '阅读 ' + r.readingCorrect + '/' + r.readingTotal))
    if (!r.graded) {
      meta.appendChild(node('span', 'rec-tag warn', '无解析文件 · 未批改'))
    }
    left.appendChild(meta)

    const right = node('div', 'rec-side')
    const rate = node('div', 'rec-rate')
    rate.appendChild(node('span', 'rec-rate-num', r.objectiveTotal
      ? Math.round((r.objectiveCorrect / r.objectiveTotal) * 100) + '%' : '-'))
    rate.appendChild(node('span', 'rec-rate-label', '客观题正确率'))
    right.appendChild(rate)

    const view = node('button', 'exam-btn', '回顾')
    view.type = 'button'
    view.addEventListener('click', () => openReview(r.id))
    const del = node('button', 'exam-btn ghost', '删除')
    del.type = 'button'
    del.addEventListener('click', () => removeRecord(r))
    right.append(view, del)

    card.append(left, right)
    recordList.appendChild(card)
  })
}

/* ---------------- 打开回顾 ---------------- */
async function openReview(id) {
  reviewRoot.textContent = ''
  reviewRoot.appendChild(node('div', 'exam-loading', '正在加载回顾数据…'))
  listView.hidden = true
  stats.hidden = true
  reviewView.hidden = false
  window.scrollTo({ top: 0 })
  try {
    const res = await fetch(API + '/records/' + id)
    const d = await res.json()
    if (!d || !d.ok) {
      throw new Error((d && d.message) || '加载失败')
    }
    mountReview(reviewRoot, d, {
      showBackToList: true,
      backLabel: '← 返回考试记录',
      onBack: backToList
    })
  } catch (e) {
    reviewRoot.textContent = ''
    reviewRoot.appendChild(node('div', 'split-fallback', '加载回顾数据失败：' + e.message))
    const back = node('button', 'exam-btn ghost', '← 返回考试记录')
    back.type = 'button'
    back.addEventListener('click', backToList)
    reviewRoot.appendChild(back)
  }
}

function backToList() {
  reviewView.hidden = true
  listView.hidden = false
  stats.hidden = false
  reviewRoot.textContent = ''
  load()
  window.scrollTo({ top: 0 })
}

async function removeRecord(r) {
  if (!window.confirm('确定删除「' + r.paperTitle + '」这条考试记录吗？')) {
    return
  }
  try {
    await fetch(API + '/records/' + r.id, { method: 'DELETE' })
  } catch (e) {
    /* 忽略 */
  }
  load()
}

/* ---------------- 加载 ---------------- */
async function load() {
  let records = []
  try {
    const res = await fetch(API + '/records?limit=200')
    const d = await res.json()
    records = (d && d.records) || []
  } catch (e) {
    records = []
  }
  renderStats(records)
  renderList(records)
}

load()
