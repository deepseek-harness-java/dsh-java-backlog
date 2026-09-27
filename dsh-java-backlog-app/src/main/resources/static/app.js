const state = {
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.backlogshaper.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.backlogshaper.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content.length > 2000 ? message.content.slice(0, 2000) + '…' : message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- backlog panels ----------

const currentText = () => $('#ingestInput').value;

const typeBadge = t => ({
  bug: '<span class="badge warn">bug</span>',
  perf: '<span class="badge info">perf</span>',
  security: '<span class="badge warn">security</span>',
  ux: '<span class="badge ok">ux</span>',
  feature: '<span class="badge ok">feature</span>'
}[t] || `<span class="badge info">${escapeHtml(String(t))}</span>`);

async function runIngest() {
  const text = currentText();
  if (!text.trim()) return toast('先粘贴需求/反馈文本', true);
  const data = await request('/api/backlog/ingest', { method: 'POST', body: JSON.stringify({ text }) });
  $('#ingestResult').innerHTML =
    `<div class="small muted">共 ${data.itemCount} 条</div>` +
    (data.items || []).map(i => `
      <div class="branch-row">
        <span class="badge info">#${i.id}</span>
        ${typeBadge(i.type)}
        <span class="badge">${escapeHtml(String(i.confidence))}</span>
        <div class="small">${escapeHtml(String(i.raw).slice(0, 60))}</div>
      </div>`).join('');
  return data;
}

async function runThemes() {
  const text = $('#themesInput').value.trim();
  if (!text) return toast('粘贴需求/反馈文本', true);
  const data = await request('/api/backlog/themes', { method: 'POST', body: JSON.stringify({ text }) });
  $('#themesResult').innerHTML =
    `<div class="small muted">${data.itemCount} 条 → ${data.themeCount} 个主题</div>` +
    (data.themes || []).map(t => `
      <div class="branch-row">
        <b>${escapeHtml(t.theme)}</b> <span class="badge info">×${t.count}</span>
        <div class="muted small">${(t.samples || []).map(s => escapeHtml(String(s))).join(' / ')}</div>
      </div>`).join('');
  return data;
}

async function runStory() {
  const text = $('#storyInput').value.trim();
  if (!text) return toast('粘贴需求/反馈文本', true);
  const data = await request('/api/backlog/user-stories', { method: 'POST', body: JSON.stringify({ text }) });
  $('#storyResult').innerHTML =
    `<div class="small muted">生成 ${data.storyCount} 条故事草稿</div>` +
    (data.stories || []).map(s => `
      <div class="branch-row">
        <span class="badge info">#${s.id}</span>
        ${typeBadge(s.type)}
        <div class="small">${escapeHtml(s.story)}</div>
      </div>`).join('');
  return data;
}

async function runAccept() {
  const text = $('#acceptInput').value.trim();
  if (!text) return toast('粘贴需求/反馈文本', true);
  const data = await request('/api/backlog/acceptance', { method: 'POST', body: JSON.stringify({ text }) });
  $('#acceptResult').innerHTML =
    `<div class="small muted">生成 ${data.caseCount} 组验收草稿</div>` +
    (data.cases || []).map(c => `
      <div class="branch-row">
        <span class="badge info">#${c.id}</span>
        ${(c.acceptance || []).map(a => `
          <div class="small muted">Given ${escapeHtml(a.given)}<br>When ${escapeHtml(a.when)}<br>Then ${escapeHtml(a.then)}</div>`).join('')}
      </div>`).join('');
  return data;
}

async function runPriority(text) {
  const data = await request('/api/backlog/priority-matrix', { method: 'POST', body: JSON.stringify({ text }) });
  const pBadge = p => p.startsWith('P0') ? '<span class="badge warn">P0</span>'
    : p.startsWith('P1') ? '<span class="badge info">P1</span>'
    : p.startsWith('P2') ? '<span class="badge ok">P2</span>' : '<span class="badge">P3</span>';
  $('#ingestResult').innerHTML =
    `<div class="small muted">${data.itemCount} 条打分完成（建议仅供参考，需团队拍板）</div>` +
    (data.matrix || []).map(m => `
      <div class="branch-row">
        ${pBadge(m.suggestedPriority || '')}
        <span class="badge info">V${m.value}/C${m.cost}</span>
        <b class="small">${escapeHtml(String(m.raw).slice(0, 50))}</b>
        <div class="muted small">${escapeHtml(m.quadrant)} · ${(m.signals || []).map(s => escapeHtml(s)).join('、') || '无强信号'}</div>
      </div>`).join('');
  document.querySelector('[data-resource-tab="ingest"]').click();
  return data;
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#openPriority').onclick = () => $('#priorityModal').classList.remove('hidden');
$('#cancelPriorityModal').onclick = () => $('#priorityModal').classList.add('hidden');
$('#priorityForm').onsubmit = async event => {
  event.preventDefault();
  const text = new FormData(event.target).get('text');
  try {
    const data = await runPriority(text);
    $('#ingestInput').value = text;
    $('#priorityModal').classList.add('hidden');
    event.target.reset();
    toast(`打分完成：${data.itemCount} 条，建议需团队拍板`);
  } catch (error) {
    toast(error.message, true);
  }
};
$('#ingestBtn').onclick = () => runIngest().catch(e => toast(e.message, true));
$('#themesBtn').onclick = () => runThemes().catch(e => toast(e.message, true));
$('#storyBtn').onclick = () => runStory().catch(e => toast(e.message, true));
$('#acceptBtn').onclick = () => runAccept().catch(e => toast(e.message, true));
$('#refreshResource').onclick = async () => {
  toast('工作台为即时分析，无需刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.backlogshaper.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.backlogshaper.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
})();
