/*
  批改回顾（考试结束后 / 日志统计里点开记录时共用）
  layout：左侧为阅读文章（一段原文 + 该段中文译文，逐段输出）或听力原文；
          右侧为题目，每题下方给出对错标记与解析。
  写作 / 翻译不打分，只展示审题、参考范文（译文）、难词译注与逐句讲解。
*/

const CN = ['一', '二', '三', '四', '五', '六']

const node = (tag, cls, text) => {
  const n = document.createElement(tag)
  if (cls) n.className = cls
  if (text != null) n.textContent = text
  return n
}

const splitParagraphs = (text) =>
  (text || '').split('\n').map((s) => s.trim()).filter(Boolean)

/* 后端在解析 Word 时保留下来的强调标记（私有区字符） */
const U_ON = '\uE000'
const U_OFF = '\uE001'
const H_ON = '\uE002'
const H_OFF = '\uE003'

/** 把带标记的文本渲染成 DOM：下划线 -> <u>，高亮 -> <mark>，其余按 pre-wrap 原样显示 */
function appendMarked(parent, text) {
  const s = String(text == null ? '' : text)
  const re = /[\uE000-\uE003]/g
  let last = 0
  let cur = parent
  const stack = []
  let m
  while ((m = re.exec(s)) !== null) {
    const seg = s.slice(last, m.index)
    if (seg) {
      cur.appendChild(node('span', null, seg))
    }
    const ch = m[0]
    if (ch === U_ON || ch === H_ON) {
      const wrap = node(ch === U_ON ? 'u' : 'mark')
      cur.appendChild(wrap)
      stack.push(cur)
      cur = wrap
    } else {
      cur = stack.pop() || parent
    }
    last = m.index + 1
  }
  const tail = s.slice(last)
  if (tail) {
    cur.appendChild(node('span', null, tail))
  }
}

/** 逐段配对：优先按段落标号（A) B) …）配，其次按下标配 */
function pairParagraphs(enText, zhParas) {
  const en = splitParagraphs(enText)
  const zh = zhParas || []
  if (en.length === 0 && zh.length === 0) return []

  const labeled = zh.length > 0 && /^[A-Z]\)/.test(zh[0])
  if (labeled) {
    const map = new Map()
    zh.forEach((z) => {
      const m = /^([A-Z])\)/.exec(z)
      if (m) map.set(m[1], z.replace(/^[A-Z]\)\s*/, ''))
    })
    return en.map((e) => {
      const m = /^([A-Z])\)/.exec(e)
      const key = m ? m[1] : null
      return { en: e, zh: key && map.has(key) ? map.get(key) : '' }
    })
  }
  const n = Math.max(en.length, zh.length)
  const out = []
  for (let i = 0; i < n; i++) {
    out.push({ en: en[i] || '', zh: zh[i] || '' })
  }
  return out
}

/** 一道题：题干 + 选项 + 我的答案 / 正确答案 + 解析 */
function questionCard(no, opts) {
  const { content, analysis, grading } = opts
  const num = String(no)
  const g = grading ? grading[num] : null

  const card = node('div', 'rv-q')
  const head = node('div', 'rv-q-head')
  head.appendChild(node('span', 'rv-q-no', num))

  // 真题里的题目优先，没有就用解析文件里的中文题干
  const real = opts.realQuestion
  const stem = (real && real.stem ? real.stem : '') || (analysis.stems ? analysis.stems[num] : '') || ''
  if (stem) head.appendChild(node('span', 'rv-q-stem', stem))

  if (g) {
    const tag = node('span', 'rv-q-tag ' + (g.ok ? 'ok' : 'bad'),
      g.ok ? '✓ 正确' : (g.mine ? '✗ 错误' : '— 未作答'))
    head.appendChild(tag)
  }
  card.appendChild(head)

  const realOpts = (real && real.options && real.options.length) ? real.options : null
  const anaOpts = analysis.options && analysis.options[num] ? analysis.options[num] : null
  const list = realOpts
    ? realOpts.map((o) => ({ label: o.label, text: o.text }))
    : (anaOpts || []).map((s) => {
      const m = /^([A-Z])\)\s*(.*)$/.exec(s)
      return m ? { label: m[1], text: m[2] } : { label: '', text: s }
    })

  if (list.length) {
    const box = node('div', 'rv-opts')
    list.sort((a, b) => String(a.label).localeCompare(String(b.label))).forEach((o) => {
      const row = node('div', 'rv-opt')
      if (g) {
        if (o.label === g.correct) row.classList.add('right')
        if (o.label === g.mine && !g.ok) row.classList.add('wrong')
      }
      row.appendChild(node('span', 'rv-opt-label', o.label))
      row.appendChild(node('span', 'rv-opt-text', o.text || ''))
      box.appendChild(row)
    })
    card.appendChild(box)
  }

  if (g) {
    const mine = node('div', 'rv-answer')
    mine.appendChild(node('span', 'rv-answer-key', '我的答案：'))
    if(g.mine == g.correct)
    {
      mine.appendChild(node('span', g.mine ? 'rv-mine-correct' : 'rv-none', g.mine || '未作答'))
    }
    else
    {
      mine.appendChild(node('span', g.mine ? 'rv-mine-false' : 'rv-none', g.mine || '未作答'))
    }
    mine.appendChild(node('span', 'rv-answer-key', '　正确答案：'))
    mine.appendChild(node('span', 'rv-correct', g.correct))
    card.appendChild(mine)
    
  }

  // 题目 / 选项的中文翻译（解析文件里有、且与上面展示的英文不同时才显示，放在解析上方）
  const zhStem = (analysis.stems && analysis.stems[num]) || ''
  const zhOpts = analysis.options && analysis.options[num] ? analysis.options[num] : null
  if ((zhStem && zhStem !== stem) || (zhOpts && realOpts)) {
    const box = node('div', 'rv-trans')
    box.appendChild(node('div', 'rv-trans-title', '题目 / 选项翻译'))
    if (zhStem && zhStem !== stem) {
      box.appendChild(node('div', 'rv-trans-stem', zhStem))
    }
    if (zhOpts) {
      const ol = node('div', 'rv-trans-opts')
      zhOpts.forEach((s) => {
        const m = /^([A-Z])\)\s*(.*)$/.exec(s)
        ol.appendChild(node('div', 'rv-trans-opt', m ? m[1] + ') ' + m[2] : s))
      })
      box.appendChild(ol)
    }
    card.appendChild(box)
  }

  const exp = (analysis.explanations && analysis.explanations[num]) || ''
  if (exp) {
    card.appendChild(node('div', 'rv-exp-title', '解析'))
    card.appendChild(node('div', 'rv-exp', exp))
  } else if (!g) {
    card.appendChild(node('div', 'rv-exp', '（本套解析文件未提供该题解析）'))
  }
  return card
}

/** 左：文章（逐段 原文 + 译文）/ 听力原文 */
function articlePanel(paragraphs, script) {
  const left = node('div', 'split-col rv-left')
  const scroll = node('div', 'panel-scroll')
  if (script != null) {
    scroll.appendChild(node('h4', 'rv-panel-title', '听力原文'))
    const pre = node('div', 'paper-text rv-script')
    appendMarked(pre, script || '（解析文件里没有听力原文）')
    scroll.appendChild(pre)
    left.appendChild(scroll)
    return left
  }
  scroll.appendChild(node('h4', 'rv-panel-title', '文章（原文 + 逐段译文）'))
  const box = node('div', 'paper-text rv-article')
  if (paragraphs.length === 0) {
    box.textContent = '（没有可展示的文章）'
  }
  paragraphs.forEach((p, i) => {
    const block = node('div', 'rv-para')
    block.appendChild(node('div', 'rv-para-no', '第 ' + (i + 1) + ' 段'))
    if (p.en) block.appendChild(node('p', 'rv-para-en', p.en))
    if (p.zh) {
      const zh = node('p', 'rv-para-zh')
      appendMarked(zh, p.zh)
      block.appendChild(zh)
    }
    box.appendChild(block)
  })
  scroll.appendChild(box)
  left.appendChild(scroll)
  return left
}

/** 真题里的题目（听力 / 阅读）按题号建索引，用于取英文题干与选项 */
function contentQuestionMap(content) {
  const map = new Map()
  ;(content.listening || []).forEach((sec) =>
    (sec.questions || []).forEach((q) => map.set(String(q.number), q)))
  ;(content.reading || []).forEach((sec) =>
    (sec.blocks || []).forEach((b) => (b.questions || []).forEach((q) => map.set(String(q.number), q))))
  return map
}

/** 右侧答题区（题干 + 对错 + 解析），带独立滚动 */
function answerPanel(title, numbers, ctx, extra) {
  const right = node('div', 'split-col rv-right')
  const scroll = node('div', 'panel-scroll')
  scroll.appendChild(node('h4', 'rv-panel-title', title))
  if (extra) {
    scroll.appendChild(extra)
  }
  const qmap = contentQuestionMap(ctx.content)
  numbers.forEach((n) => {
    scroll.appendChild(questionCard(n, { ...ctx, realQuestion: qmap.get(String(n)) }))
  })
  right.appendChild(scroll)
  return right
}

/** 听力：Section A / B / C 分别切换查看 */
function buildListening(ctx) {
  const { content, analysis } = ctx
  const parts = analysis.listeningParts || []
  const wrap = node('div')

  if (parts.length === 0) {
    // 解析文件没有按小节拆分时，退回单栏展示
    const qs = []
    ;(content.listening || []).forEach((sec) => (sec.questions || []).forEach((q) => qs.push(q.number)))
    if (qs.length === 0) {
      for (let n = 1; n <= 25; n++) {
        qs.push(n)
      }
    }
    const row = node('div', 'exam-split rv-row rv-equal')
    row.appendChild(articlePanel(null, analysis.listeningScript))
    row.appendChild(answerPanel('题目与解析', qs.sort((a, b) => a - b), ctx))
    wrap.appendChild(row)
    return wrap
  }

  const bar = node('div', 'rv-subtabs')
  const holder = node('div')
  const show = (i) => {
    bar.querySelectorAll('.rv-subtab').forEach((b, k) => b.classList.toggle('active', k === i))
    holder.textContent = ''
    const p = parts[i]
    const row = node('div', 'exam-split rv-row rv-equal')
    row.appendChild(articlePanel(null, p.script))
    row.appendChild(answerPanel('题目与解析', p.numbers || [], ctx))
    holder.appendChild(row)
  }
  parts.forEach((p, i) => {
    const b = node('button', 'rv-subtab', p.name)
    b.type = 'button'
    b.addEventListener('click', () => show(i))
    bar.appendChild(b)
  })
  wrap.append(bar, holder)
  show(0)
  return wrap
}

/** 组装某个阅读小节的回顾内容：左文章（原文+逐段译文）、右答题区，两栏等高各自滚动 */
function buildSection(body, tab, ctx) {
  const { content, analysis } = ctx
  const wrap = node('div')
  const sec = tab.section
  if (!sec) {
    wrap.appendChild(node('div', 'split-fallback', '这一段没有可回顾的内容。'))
    return wrap
  }
  ;(sec.blocks || []).forEach((block) => {
    const zhKey = sec.kind === 'careful' ? block.title : sec.name
    const zh = (analysis.translations && analysis.translations[zhKey]) || []
    const row = node('div', 'exam-split rv-row rv-equal')
    // 段落匹配的原文有标题，中文译文里也含标题译文，这里把英文标题补进段落列表好逐段对齐
    const enText = (sec.kind === 'matching' && block.title)
      ? block.title + '\n' + (block.text || '')
      : block.text
    row.appendChild(articlePanel(pairParagraphs(enText, zh), null))

    let extra = null
    if (block.wordBank && block.wordBank.length) {
      extra = node('div', 'word-bank')
      block.wordBank.slice().sort().forEach((w) => extra.appendChild(node('span', 'word-item', w)))
    }
    const title = sec.kind === 'banked' ? '题目与解析（填词库字母）'
      : sec.kind === 'matching' ? '题目与解析（填段落字母）' : '题目与解析'
    const numbers = (block.questions || []).map((q) => q.number)
    row.appendChild(answerPanel(title, numbers, ctx, extra))
    wrap.appendChild(row)
  })

  // 选词填空的「干扰项说明」单独一栏展示
  const dis = analysis.distractors && analysis.distractors[sec.name]
  if (dis) {
    const panel = node('div', 'split-col rv-full')
    panel.appendChild(node('h4', 'rv-panel-title', '干扰项说明'))
    panel.appendChild(node('div', 'paper-text rv-wide', dis))
    wrap.appendChild(panel)
  }
  return wrap
}

/** 写作：左「题目 + 审题 + 范文 + 范文翻译」，右「我的作文」 */
function buildWriting(ctx) {
  const { content, analysis, record } = ctx
  const row = node('div', 'exam-split rv-row rv-equal')

  const left = node('div', 'split-col rv-left')
  const ls = node('div', 'panel-scroll')
  ls.appendChild(node('h4', 'rv-panel-title', '作文题目'))
  ls.appendChild(node('div', 'paper-text', content.writingDirections || '（没有题目）'))
  ls.appendChild(node('h4', 'rv-panel-title', '审题'))
  ls.appendChild(node('div', 'paper-text', analysis.writingAnalysis || '（没有审题说明）'))
  if (analysis.writingModel) {
    ls.appendChild(node('h4', 'rv-panel-title', '参考范文 & 点评'))
    ls.appendChild(node('div', 'paper-text', analysis.writingModel))
  }
  if (analysis.writingModelTranslation) {
    ls.appendChild(node('h4', 'rv-panel-title', '范文译文'))
    ls.appendChild(node('div', 'paper-text', analysis.writingModelTranslation))
  }
  left.appendChild(ls)

  const right = node('div', 'split-col rv-right')
  const rs = node('div', 'panel-scroll')
  rs.appendChild(node('h4', 'rv-panel-title', '我的作文'))
  rs.appendChild(node('div', 'rv-mine-box', record && record.writing ? record.writing : '（未作答）'))
  right.appendChild(rs)

  row.append(left, right)
  return row
}

/** 翻译：左上「题目 + 难词注释」、右上「我的翻译」、下方通栏「译文 + 逐句讲解」 */
function buildTranslation(ctx) {
  const { content, analysis, record } = ctx
  const wrap = node('div')

  const top = node('div', 'exam-split rv-row rv-equal')
  const left = node('div', 'split-col rv-left')
  const ls = node('div', 'panel-scroll')
  ls.appendChild(node('h4', 'rv-panel-title', '翻译题目'))
  ls.appendChild(node('div', 'paper-text', content.translationText || '（没有题目）'))
  if (analysis.translationWords) {
    ls.appendChild(node('h4', 'rv-panel-title', '难词注释'))
    ls.appendChild(node('div', 'paper-text', analysis.translationWords))
  }
  left.appendChild(ls)

  const right = node('div', 'split-col rv-right')
  const rs = node('div', 'panel-scroll')
  rs.appendChild(node('h4', 'rv-panel-title', '我的翻译'))
  rs.appendChild(node('div', 'rv-mine-box',
    record && record.translation ? record.translation : '（未作答）'))
  right.appendChild(rs)
  top.append(left, right)

  const bottom = node('div', 'split-col rv-full')
  if (analysis.translationModel) {
    bottom.appendChild(node('h4', 'rv-panel-title', '参考译文'))
    bottom.appendChild(node('div', 'paper-text rv-wide', analysis.translationModel))
  }
  if (analysis.translationNotes) {
    bottom.appendChild(node('h4', 'rv-panel-title', '逐句讲解'))
    bottom.appendChild(node('div', 'paper-text rv-wide', analysis.translationNotes))
  }
  wrap.append(top, bottom)
  return wrap
}

/**
 * 渲染整份批改回顾
 * @param root 容器元素
 * @param payload { record, grading, answers, paper, content, analysis }
 * @param options { showBackToList: 是否显示「返回记录列表」按钮, onBack }
 */
export function mountReview(root, payload, options) {
  const opts = options || {}
  const record = payload.record || {}
  const content = payload.content || {}
  const analysis = payload.analysis || {}
  const grading = payload.grading || {}
  root.textContent = ''

  /* ---------- 成绩头部 ---------- */
  const head = node('div', 'rv-head')
  const title = node('div', 'rv-title', record.paperTitle || (payload.paper && payload.paper.title) || '试卷')
  head.appendChild(title)
  const sum = node('div', 'rv-summary')
  const chip = (label, value, cls) => {
    const c = node('div', 'rv-chip ' + (cls || ''))
    c.appendChild(node('span', 'rv-chip-num', value))
    c.appendChild(node('span', 'rv-chip-label', label))
    return c
  }
  const lTotal = record.listeningTotal || 0
  const lCorrect = record.listeningCorrect || 0
  const rTotal = record.readingTotal || 0
  const rCorrect = record.readingCorrect || 0
  sum.appendChild(chip('听力', lCorrect + ' / ' + lTotal, 'blue'))
  sum.appendChild(chip('阅读', rCorrect + ' / ' + rTotal, 'ok'))
  const total = lTotal + rTotal
  const right = lCorrect + rCorrect
  sum.appendChild(chip('客观题正确率', total ? Math.round((right / total) * 100) + '%' : '-', 'warn'))
  if (record.durationSec) {
    const m = Math.floor(record.durationSec / 60)
    sum.appendChild(chip('用时', m + ' 分钟', ''))
  }
  sum.appendChild(chip('交卷时间', record.submittedAt || '-', ''))
  head.appendChild(sum)

  const meta = node('div', 'rv-meta')
  if (!Object.keys(grading).length) {
    meta.appendChild(node('span', 'rv-warn',
      '这份试卷还没有可用的 Word 版解析文件，无法批改，仅展示作答与试卷内容。'))
  } else if (analysis.quality === 'partial') {
    meta.appendChild(node('span', 'rv-warn', analysis.warning))
  }
  if (opts.showBackToList) {
    const back = node('button', 'exam-btn ghost', opts.backLabel || '← 返回考试记录')
    back.type = 'button'
    back.addEventListener('click', () => opts.onBack && opts.onBack())
    meta.appendChild(back)
  }

  if (meta.childNodes.length) head.appendChild(meta)
  root.appendChild(head)

  /* ---------- 分页 ---------- */
  const tabs = []
  if (content.writingDirections || analysis.writingAnalysis || record.writing) {
    tabs.push({ key: 'writing', label: '写作', kind: 'writing' })
  }
  if ((content.listening || []).length || analysis.listeningScript) {
    tabs.push({ key: 'listening', label: '听力', kind: 'listening' })
  }
  ;(content.reading || []).forEach((sec, i) => {
    const kindName = sec.kind === 'banked' ? '选词填空' : sec.kind === 'matching' ? '段落匹配' : '仔细阅读'
    tabs.push({ key: 'read' + i, label: '阅读' + (CN[i] || i + 1) + ' ' + kindName, kind: 'reading', section: sec })
  })
  if (content.translationText || analysis.translationModel || record.translation) {
    tabs.push({ key: 'translation', label: '翻译', kind: 'translation' })
  }

  if (tabs.length === 0) {
    root.appendChild(node('div', 'split-fallback', '这份记录没有可回顾的内容。'))
    return
  }

  const bar = node('div', 'rv-tabs')
  const body = node('div', 'rv-body')
  const ctx = { content, analysis, grading, record }

  const show = (tab) => {
    bar.querySelectorAll('.rv-tab').forEach((b) => {
      b.classList.toggle('active', b.dataset.key === tab.key)
    })
    body.textContent = ''
    if (tab.kind === 'writing') {
      body.appendChild(buildWriting(ctx))
    } else if (tab.kind === 'translation') {
      body.appendChild(buildTranslation(ctx))
    } else if (tab.kind === 'listening') {
      body.appendChild(buildListening(ctx))
    } else {
      body.appendChild(buildSection(body, tab, ctx))
    }
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  tabs.forEach((tab) => {
    const b = node('button', 'rv-tab', tab.label)
    b.type = 'button'
    b.dataset.key = tab.key
    b.addEventListener('click', () => show(tab))
    bar.appendChild(b)
  })
  root.append(bar, body)
  show(tabs[0])
}
