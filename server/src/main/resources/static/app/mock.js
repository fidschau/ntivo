// Mock API for `index.html?mock=1`. Same functions as the real API in api.js, backed by
// sample data: two ready repositories, a scripted four-stage indexing job for anything you
// connect, and a canned answer and trace for the bring-a-friend question.
//
// Handy inputs while in mock mode:
//   - a GitHub URL containing "docs" fails at the split stage (no Kotlin or Swift files)
//   - "?mock=1&scenario=1" connects sportcity/backend and asks the demo question by itself

import { ApiError } from './api.js';

const HOUR = 3600_000;
const now = () => Date.now();
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const copy = (value) => JSON.parse(JSON.stringify(value));

function doneStages(ms) {
  return ['fetch', 'split', 'embed', 'connect'].map((name, i) => ({
    name, state: 'done', done: 1, total: 1, durationMs: ms[i],
  }));
}

const sources = new Map([
  ['src-android', {
    id: 'src-android', name: 'sportcity/android-app', kind: 'github', state: 'ready',
    languages: ['Kotlin'], files: 3412, pieces: 9842, lastSyncedAt: now() - 2 * HOUR, error: null,
    stages: doneStages([4200, 9100, 412000, 61000]),
  }],
  ['src-ios', {
    id: 'src-ios', name: 'sportcity/ios-app', kind: 'github', state: 'ready',
    languages: ['Swift'], files: 2210, pieces: 7120, lastSyncedAt: now() - 2 * HOUR, error: null,
    stages: doneStages([3900, 7400, 301000, 44000]),
  }],
]);

const pieces = {
  'p-android-caninvite': {
    id: 'p-android-caninvite', sourceId: 'src-android', sourceName: 'sportcity/android-app', language: 'Kotlin',
    path: 'feature/invite/src/main/java/nl/sportcity/invite/InviteViewModel.kt',
    name: 'canInvite', qualifiedName: 'InviteViewModel.canInvite', startLine: 41, endLine: 52,
    text: [
      '    fun canInvite(): Boolean {',
      '        val friends = state.value.invitedFriends',
      '        // Product decision: keep bring-a-friend small enough to staff the front desk.',
      '        if (friends.size >= MAX_FRIENDS) {',
      '            analytics.track("invite_limit_reached")',
      '            return false',
      '        }',
      '        if (state.value.isLoading) {',
      '            return false',
      '        }',
      '        return membership.allowsGuests',
      '    }',
    ].join('\n'),
  },
  'p-ios-friendlimit': {
    id: 'p-ios-friendlimit', sourceId: 'src-ios', sourceName: 'sportcity/ios-app', language: 'Swift',
    path: 'SportCity/Features/Invite/InviteService.swift',
    name: 'friendLimit', qualifiedName: 'InviteService.friendLimit', startLine: 88, endLine: 103,
    text: [
      '    /// How many friends a member may bring along.',
      '    func friendLimit() -> Int {',
      '        let defaultLimit = 10',
      '        guard config.isLoaded else {',
      '            logger.warning("Remote config not loaded, using default friend limit")',
      '            return defaultLimit',
      '        }',
      '        let value = RemoteConfig.shared.int(forKey: "friend_limit")',
      '        if value <= 0 {',
      '            return defaultLimit',
      '        }',
      '        return value',
      '    }',
      '',
      '    var hasReachedLimit: Bool { invited.count >= friendLimit() }',
      '',
    ].join('\n'),
  },
  'p-android-limit': {
    id: 'p-android-limit', sourceId: 'src-android', sourceName: 'sportcity/android-app', language: 'Kotlin',
    path: 'data/friends/src/main/java/nl/sportcity/friends/FriendsRepository.kt',
    name: 'limit', qualifiedName: 'FriendsRepository.limit', startLine: 17, endLine: 24,
    text: [
      '    suspend fun limit(): Int {',
      '        val cached = cache.friendLimit',
      '        if (cached != null) {',
      '            return cached',
      '        }',
      '        // The API does not send a limit yet, see MAX_FRIENDS.',
      '        return MAX_FRIENDS',
      '    }',
    ].join('\n'),
  },
};

const answers = new Map();
const traces = new Map();
const listeners = new Set();
let answerCount = 0;

function emit(type, data) {
  for (const handlers of listeners) handlers[type]?.(copy(data));
}

function citationFor(pieceId) {
  const piece = pieces[pieceId];
  return { pieceId, sourceName: piece.sourceName, path: piece.path, startLine: piece.startLine, endLine: piece.endLine };
}

function unavailable() {
  return [...sources.values()]
    .filter((source) => source.state !== 'ready')
    .map((source) => {
      if (source.state === 'failed') return `${source.name} (failed)`;
      const stage = source.stages.find((s) => s.state === 'running');
      const pct = stage?.total ? Math.round((stage.done * 100) / stage.total) : 0;
      return `${source.name} (indexing ${pct}%)`;
    });
}

// ---- Indexing job ---------------------------------------------------------------------------

function update(id, change) {
  const next = { ...sources.get(id), ...change };
  sources.set(id, next);
  emit('source.updated', next);
  return next;
}

function setStage(id, name, change) {
  const source = sources.get(id);
  const stages = source.stages.map((stage) => (stage.name === name ? { ...stage, ...change } : stage));
  return update(id, { stages });
}

async function runStage(id, name, total, ms, message) {
  const started = now();
  setStage(id, name, { state: 'running', done: 0, total, durationMs: 0 });
  const ticks = Math.max(1, Math.round(ms / 250));
  for (let i = 1; i <= ticks; i++) {
    await wait(250);
    if (!sources.has(id)) throw new Error('removed');
    const done = Math.round((total * i) / ticks);
    setStage(id, name, { done, durationMs: now() - started });
    emit('job.progress', { sourceId: id, stage: name, done, total, message: message(done) });
  }
  setStage(id, name, { state: 'done', done: total, durationMs: now() - started });
}

async function runJob(id, { files, pieceCount, failAtSplit }) {
  try {
    await runStage(id, 'fetch', files, 1200, () => `Fetched ${files.toLocaleString('en-US')} files`);
    if (failAtSplit) {
      await wait(600);
      setStage(id, 'split', { state: 'failed' });
      update(id, { state: 'failed', error: 'No Kotlin or Swift files found' });
      return;
    }
    await runStage(id, 'split', files, 1500, () => `Split into ${pieceCount.toLocaleString('en-US')} pieces`);
    await runStage(id, 'embed', pieceCount, 9000, () => 'Capturing meaning');
    await runStage(id, 'connect', pieceCount, 1800, () => 'Mapping connections');
    update(id, { state: 'ready', pieces: pieceCount, lastSyncedAt: now() });
  } catch {
    // The source was removed while indexing.
  }
}

function startSource({ name, kind, files, languages, failAtSplit = false }) {
  const id = `src-${Math.random().toString(16).slice(2, 14)}`;
  const pieceCount = Math.max(1, Math.round(files * 2.7));
  const source = {
    id, name, kind, state: 'indexing', languages, files, pieces: 0, lastSyncedAt: null, error: null,
    stages: ['fetch', 'split', 'embed', 'connect'].map((stage) => ({ name: stage, state: 'todo', done: 0, total: 0, durationMs: 0 })),
  };
  sources.set(id, source);
  setTimeout(() => {
    emit('source.updated', source);
    runJob(id, { files, pieceCount, failAtSplit });
  }, 50);
  return copy(source);
}

// ---- API ------------------------------------------------------------------------------------

export async function listSources() {
  await wait(120);
  return copy([...sources.values()]);
}

export async function connectGithub({ url }) {
  await wait(300);
  const match = /^https:\/\/github\.com\/([^/\s]+)\/([^/\s]+?)(\.git)?\/?$/.exec(String(url || '').trim());
  if (!match) throw new ApiError(400, 'Only github.com URLs are supported');
  const name = `${match[1]}/${match[2]}`;
  const lower = name.toLowerCase();
  return startSource({
    name,
    kind: 'github',
    files: lower.includes('backend') ? 2310 : 1640,
    languages: lower.includes('ios') ? ['Swift'] : ['Kotlin'],
    failAtSplit: lower.includes('docs'),
  });
}

export async function uploadFolder(name, files, onProgress) {
  if (!files.length) throw new ApiError(400, 'No files to upload');
  for (let i = 1; i <= 5; i++) {
    await wait(120);
    onProgress?.(i / 5);
  }
  const languages = [...new Set(files.map(({ path }) => (path.endsWith('.swift') ? 'Swift' : 'Kotlin')))].sort();
  return startSource({ name, kind: 'upload', files: files.length, languages });
}

export async function deleteSource(id) {
  await wait(150);
  sources.delete(id);
  return null;
}

const FRIEND_QUESTION = /friend|limit|invite|bring/i;

export async function ask(question) {
  const id = `ans-${++answerCount}`;
  const started = now();
  const relevant = FRIEND_QUESTION.test(question);
  const ready = [...sources.values()].filter((s) => s.state === 'ready').map((s) => s.name.split('/').pop());
  const steps = relevant
    ? [...ready.map((name) => `Searching ${name}`), 'Following connections', 'Writing answer']
    : [...ready.map((name) => `Searching ${name}`), 'Writing answer'];
  for (const message of steps) {
    emit('answer.activity', { answerId: id, message });
    await wait(650);
  }
  const notSeen = unavailable();

  let answer;
  let trace;
  if (relevant) {
    answer = {
      id, question,
      verdict: {
        kind: 'mismatch',
        text: "They don't match. Android hard-codes a limit of 5. iOS reads it from remote config and falls back to 10 when the value is missing.",
      },
      prose: 'If remote config ever fails to load on iOS, users there can invite up to 10 friends while Android stops at 5. That would explain accounts with more than 5 friends.',
      evidence: [
        { label: 'Android', text: '`canInvite()` returns false once `friends.size >= 5`. The 5 is a constant, `MAX_FRIENDS`.', citation: citationFor('p-android-caninvite') },
        { label: 'iOS', text: 'Reads `friend_limit` from `RemoteConfig`. If the key is absent, `defaultLimit = 10`.', citation: citationFor('p-ios-friendlimit') },
      ],
      citations: [citationFor('p-android-caninvite'), citationFor('p-ios-friendlimit')],
      couldNotSee: notSeen,
      unverifiedCitations: 0,
      durationMs: now() - started,
    };
    trace = {
      answerId: id,
      explanation: 'Your question was turned into a point in the same space as the code. Ntivo took the 3 closest pieces, then followed 2 connections from them.',
      pieces: [
        { pieceId: 'p-android-caninvite', name: 'InviteViewModel.canInvite', sourceName: 'sportcity/android-app', language: 'Kotlin', score: 0.81 },
        { pieceId: 'p-ios-friendlimit', name: 'InviteService.friendLimit', sourceName: 'sportcity/ios-app', language: 'Swift', score: 0.77 },
        { pieceId: 'p-android-limit', name: 'FriendsRepository.limit', sourceName: 'sportcity/android-app', language: 'Kotlin', score: 0.74 },
      ],
      hops: [
        { from: 'canInvite', relation: 'reads', to: 'MAX_FRIENDS', confidence: 1.0 },
        { from: 'friendLimit', relation: 'calls', to: 'RemoteConfig.int', confidence: 0.5 },
      ],
      unavailable: notSeen,
      toolCalls: [
        { tool: 'searchCodebase', args: '{"query":"friend limit bring a friend"}', summary: '3 pieces above 0.60 from 2 repositories', durationMs: 840 },
        { tool: 'calleesOf', args: '{"functionName":"canInvite"}', summary: '1 connection', durationMs: 120 },
        { tool: 'calleesOf', args: '{"functionName":"friendLimit"}', summary: '1 connection', durationMs: 110 },
      ],
      limitsHit: [],
      model: 'claude-sonnet-5-5',
    };
  } else {
    answer = {
      id, question, verdict: null,
      prose: 'I could not find code that clearly answers this in the connected repositories. Try naming a screen, a class or a feature, for example "bring-a-friend" or "InviteService".',
      evidence: [], citations: [], couldNotSee: notSeen, unverifiedCitations: 0, durationMs: now() - started,
    };
    trace = {
      answerId: id,
      explanation: 'Your question was turned into a point in the same space as the code. No piece was close enough to use.',
      pieces: [], hops: [], unavailable: notSeen,
      toolCalls: [{ tool: 'searchCodebase', args: JSON.stringify({ query: question }), summary: 'No pieces above 0.60', durationMs: 790 }],
      limitsHit: [],
      model: 'claude-sonnet-5-5',
    };
  }
  answers.set(id, answer);
  traces.set(id, trace);
  return copy(answer);
}

export async function getTrace(answerId) {
  await wait(120);
  const trace = traces.get(answerId);
  if (!trace) throw new ApiError(404, 'No trace for this answer');
  return copy(trace);
}

export async function getPiece(pieceId) {
  await wait(150);
  const piece = pieces[pieceId];
  if (!piece) throw new ApiError(404, 'Piece not found');
  return copy(piece);
}

export function subscribe(handlers) {
  listeners.add(handlers);
  setTimeout(() => handlers.open?.(), 0);
  return () => listeners.delete(handlers);
}
