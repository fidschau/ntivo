// Entry point: wires the API (real or mock) to the three columns, the drawer and the dialog.

import { getApi, isMock } from './api.js';
import { prefs, toast } from './dom.js';
import { createSources } from './components/sources.js';
import { createChat } from './components/chat.js';
import { createTrace } from './components/trace.js';
import { createDrawer } from './components/code-drawer.js';
import { createConnectDialog } from './components/connect-dialog.js';

const api = await getApi();

const app = document.getElementById('app');
const crumbChat = document.getElementById('crumb-chat');
document.getElementById('mock-badge').hidden = !isMock;

const state = {
  sources: new Map(),
  answers: new Map(),
  traces: new Map(),
  activeAnswerId: null,
  busy: false,
  leftCollapsed: prefs.get('leftCollapsed', false),
  rightCollapsed: prefs.get('rightCollapsed', false),
};

// Below 1024px the side columns start collapsed, without overwriting the saved preference.
const narrow = window.matchMedia('(max-width: 1023px)');

const sourcesList = () => [...state.sources.values()];

const sources = createSources(document.getElementById('sources'), {
  onConnect: () => dialog.open('github'),
  onRemove: removeSource,
  onToggle: () => setLeft(!isLeftCollapsed()),
  onExpand: () => setLeft(false),
});

const chat = createChat(document.getElementById('chat'), {
  onAsk: ask,
  onOpenCitations: (list, index) => drawer.open(list, index),
  onShowTrace: (answerId) => showTrace(answerId, true),
  onConnect: () => dialog.open('github'),
});

const trace = createTrace(document.getElementById('trace'), {
  onOpenPiece: (piece) => drawer.open([{ pieceId: piece.pieceId, sourceName: piece.sourceName, path: '', startLine: 0, endLine: 0 }]),
  onToggle: () => setRight(!isRightCollapsed()),
});

const drawer = createDrawer(document.getElementById('drawer'), api);

const dialog = createConnectDialog(document.getElementById('connect'), api, {
  onConnected(source) {
    putSource(source);
    chat.addIngestion(source);
  },
});

// ---- Layout -----------------------------------------------------------------------------------

function isLeftCollapsed() {
  return app.classList.contains('left-collapsed');
}

function isRightCollapsed() {
  return app.classList.contains('right-collapsed');
}

function applyLayout() {
  const left = narrow.matches || state.leftCollapsed;
  const right = narrow.matches || state.rightCollapsed;
  app.classList.toggle('left-collapsed', left);
  app.classList.toggle('right-collapsed', right);
  sources.setCollapsed(left);
  trace.setCollapsed(right);
}

function setLeft(collapsed) {
  state.leftCollapsed = collapsed;
  if (!narrow.matches) prefs.set('leftCollapsed', collapsed);
  app.classList.toggle('left-collapsed', collapsed);
  sources.setCollapsed(collapsed);
}

function setRight(collapsed) {
  state.rightCollapsed = collapsed;
  if (!narrow.matches) prefs.set('rightCollapsed', collapsed);
  app.classList.toggle('right-collapsed', collapsed);
  trace.setCollapsed(collapsed);
}

narrow.addEventListener('change', () => {
  state.leftCollapsed = prefs.get('leftCollapsed', false);
  state.rightCollapsed = prefs.get('rightCollapsed', false);
  applyLayout();
});

// ---- Sources ----------------------------------------------------------------------------------

let renderQueued = false;
function renderSources() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    const list = sourcesList();
    sources.update(list);
    chat.setSources(list);
  });
}

function putSource(source) {
  state.sources.set(source.id, source);
  renderSources();
}

function onSourceUpdated(source) {
  const known = state.sources.has(source.id);
  putSource(source);
  // A source connected from another tab, or after a reconnect: give it a card while it indexes.
  if (!known && source.state === 'indexing') chat.addIngestion(source);
}

function onJobProgress(progress) {
  chat.stageMessage(progress);
  const source = state.sources.get(progress.sourceId);
  if (!source) return;
  const stages = (source.stages ?? []).map((stage) =>
    stage.name === progress.stage
      ? { ...stage, state: stage.state === 'done' ? 'done' : 'running', done: progress.done, total: progress.total }
      : stage);
  putSource({ ...source, stages });
}

async function loadSources() {
  try {
    const list = await api.listSources();
    state.sources = new Map(list.map((source) => [source.id, source]));
    chat.banner(null);
    renderSources();
    return list;
  } catch (error) {
    chat.banner(`Could not load sources. ${error.message}`);
    renderSources();
    return [];
  }
}

async function removeSource(source) {
  const ok = window.confirm(`Remove ${source.name}? Ntivo forgets what it read from it. The repository itself is not touched.`);
  if (!ok) return;
  try {
    await api.deleteSource(source.id);
    state.sources.delete(source.id);
    renderSources();
    toast(`Removed ${source.name}`);
  } catch (error) {
    toast(`Could not remove ${source.name}. ${error.message}`);
  }
}

// ---- Asking -----------------------------------------------------------------------------------

function setBusy(value) {
  state.busy = value;
  chat.setBusy(value);
}

async function ask(question) {
  if (state.busy) return;
  if (!crumbChat.textContent) crumbChat.textContent = question.length > 48 ? `${question.slice(0, 47)}…` : question;
  chat.addQuestion(question);
  const pending = chat.addPending();
  setBusy(true);
  try {
    const answer = await api.ask(question);
    state.answers.set(answer.id, answer);
    pending.resolve(answer);
    await showTrace(answer.id, false);
  } catch (error) {
    pending.fail(error.message || 'Something went wrong.');
  } finally {
    setBusy(false);
  }
}

async function showTrace(answerId, expand) {
  state.activeAnswerId = answerId;
  chat.markActive(answerId);
  if (expand && isRightCollapsed()) setRight(false);
  const answer = state.answers.get(answerId);
  if (state.traces.has(answerId)) {
    trace.show(state.traces.get(answerId), answer);
    return;
  }
  trace.showLoading();
  try {
    const result = await api.getTrace(answerId);
    state.traces.set(answerId, result);
    if (state.activeAnswerId === answerId) trace.show(result, answer);
  } catch (error) {
    if (state.activeAnswerId === answerId) trace.showError(`Could not load how this answer was made. ${error.message}`);
  }
}

// ---- Top bar ----------------------------------------------------------------------------------

document.getElementById('new-chat').addEventListener('click', () => {
  if (state.busy) return;
  chat.reset();
  drawer.close();
  crumbChat.textContent = '';
  state.activeAnswerId = null;
  trace.showEmpty();
  for (const source of sourcesList()) if (source.state === 'indexing') chat.addIngestion(source, 'is still reading');
  renderSources();
  chat.focus();
});

// ---- Start ------------------------------------------------------------------------------------

applyLayout();
const initial = await loadSources();
for (const source of initial) if (source.state === 'indexing') chat.addIngestion(source, 'is reading');

let opened = false;
api.subscribe({
  'source.updated': onSourceUpdated,
  'job.progress': onJobProgress,
  'answer.activity': (activity) => chat.setActivity(activity.message),
  open() {
    // Events missed while disconnected are not replayed, so refresh after a reconnect.
    if (opened) loadSources();
    opened = true;
  },
});

// Mock only: play the mockup scenario by itself (connect, index, ask).
if (isMock && new URLSearchParams(window.location.search).has('scenario')) {
  const source = await api.connectGithub({ url: 'https://github.com/sportcity/backend' });
  putSource(source);
  chat.addIngestion(source);
  setTimeout(() => ask('Do iOS and Android enforce the same friend limit in bring-a-friend?'), 5000);
}
