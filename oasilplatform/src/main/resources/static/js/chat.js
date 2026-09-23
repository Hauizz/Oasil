/* 智能问答页：发送问题 -> 显示 AI 回答 */
import { sendChat } from './api.js'

const messages = document.getElementById('messages')
const welcome = document.getElementById('welcome')
const draft = document.getElementById('draft')
const sendBtn = document.getElementById('send')

let sending = false

function scrollBottom() {
  messages.scrollTop = messages.scrollHeight
}

/** 追加一条消息气泡，返回气泡元素（便于后续更新内容） */
function pushMessage(role, text, loading) {
  if (welcome && welcome.isConnected) welcome.remove()

  const wrap = document.createElement('div')
  wrap.className = 'msg ' + role

  const bubble = document.createElement('div')
  bubble.className = 'bubble' + (loading ? ' loading' : '')
  bubble.textContent = text || ''

  wrap.appendChild(bubble)
  messages.appendChild(wrap)
  scrollBottom()
  return bubble
}

async function send() {
  const text = draft.value.trim()
  if (!text || sending) return

  pushMessage('user', text)
  draft.value = ''
  draft.style.height = 'auto'

  sending = true
  sendBtn.disabled = true
  const bubble = pushMessage('ai', '', true)

  try {
    const answer = await sendChat(text)
    bubble.classList.remove('loading')
    bubble.textContent = answer || ''
  } catch (e) {
    bubble.classList.remove('loading')
    bubble.textContent = '请求失败：' + e.message + '\n请确认应用已启动，且 DeepSeek API 已正确配置。'
  } finally {
    sending = false
    sendBtn.disabled = false
    scrollBottom()
  }
}

sendBtn.addEventListener('click', send)

draft.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
})

// 输入框随内容自动增高
draft.addEventListener('input', () => {
  draft.style.height = 'auto'
  draft.style.height = Math.min(draft.scrollHeight, 120) + 'px'
})

draft.focus()
