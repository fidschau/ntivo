// Middle column: the stream of turns (ingestion cards, questions, answers) and the composer.

import { el, replace, inline, paragraphs } from '../dom.js';
import { icon } from '../icons.js';
import {
  basename, duration, joinList, lineRange, num, plural, sourcePercent, timeLeft, totalDuration,
} from '../format.js';

const EXAMPLES = [
  'Do iOS and Android enforce the same friend limit in bring-a-friend?',
  'Where does the friend limit come from?',
  'What calls the invite service?',
];

const STEP_COPY = {
  fetch: {
    todo: () => 'Fetch files',
    running: () => 'Fetching files',
    done: (s, src) => `Fetched ${plural(s.total || src.files, 'file')}`,
    sub: (src) => (src.kind === 'github' ? 'from GitHub' : src.kind === 'upload' ? 'from your upload' : 'from pasted code'),
  },
  split: {
    todo: () => 'Split into pieces',
    running: () => 'Splitting into pieces',
    done: (s, src, msg) => msg || (pieceCount(src) ? `Split into ${plural(pieceCount(src), 'piece')}` : 'Split into pieces'),
    sub: () => 'one per function or method, so answers point at the exact code',
  },
  embed: {
    todo: () => 'Capture meaning',
    running: () => 'Capturing meaning',
    done: () => 'Captured meaning',
    sub: () => 'each piece becomes a point in a space where similar code sits close together',
  },
  connect: {
    todo: () => 'Map connections',
    running: () => 'Mapping connections',
    done: () => 'Mapped connections',
    sub: () => 'who calls what, so answers can follow the trail',
  },
};

function pieceCount(source) {
  return source.pieces || source.stages?.find((s) => s.name === 'embed')?.total || 0;
}

function stepMark(state) {
  if (state === 'done') return el('span', { class: 'ck' }, icon('check', 10));
  if (state === 'failed') return el('span', { class: 'ck' }, icon('x', 10));
  return el('span', { class: 'ck' });
}

/** Splits "They don't match. Rest..." into a bold lead sentence and the rest. */
function verdictBody(text) {
  const match = /^(.+?[.!?])(\s+[\s\S]+)?$/.exec(String(text ?? '').trim());
  if (!match) return [el('b', null, text)];
  return [el('b', null, match[1]), match[2] ? inline(match[2]) : null];
}

const VERDICT_ICON = { mismatch: 'alert', confirmed: 'checkCircle', neutral: 'info' };

function avatar(kind) {
  return kind === 'bot'
    ? el('div', { class: 'who', 'aria-hidden': 'true' }, 'n')
    : el('div', { class: 'who', 'aria-hidden': 'true' });
}

/**
 * @param root the chat <section>
 * @param actions { onAsk(question), onOpenCitations(citations, index), onShowTrace(answerId), onConnect() }
 */
export function createChat(root, actions) {
  const jobs = new Map(); // sourceId -> { node, source }
  const stageMessages = new Map(); // `${sourceId}:${stage}` -> last job.progress message
  const answerTurns = new Map(); // answerId -> turn node
  let sources = [];
  let busy = false;
  let pending = null;

  const stream = el('div', { class: 'stream', 'aria-live': 'polite' });
  const wrap = el('div', { class: 'wrap' });
  stream.append(wrap);

  const textarea = el('textarea', {
    id: 'question', rows: 3, 'aria-label': 'Your question',
    placeholder: 'Ask about your codebase. For example: what happens when an invited friend already has an account?',
  });
  const askButton = el('button', { class: 'btn primary', type: 'submit' }, 'Ask');
  const footText = el('span', { class: 'foot-text' });
  const chips = el('div', { class: 'hint', role: 'list', 'aria-label': 'Example questions' });
  const form = el('form', { class: 'box', onSubmit: (event) => { event.preventDefault(); submit(); } },
    textarea,
    el('div', { class: 'foot' }, footText, el('span', { class: 'keys' }, 'Enter to ask, Shift+Enter for a new line'), askButton),
  );
  const composer = el('div', { class: 'composer' }, el('div', { class: 'wrap' }, form, chips));
  replace(root, stream, composer);

  textarea.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      submit();
    }
  });
  textarea.addEventListener('input', updateComposer);

  for (const question of EXAMPLES) {
    chips.append(el('button', {
      class: 'hint-chip', type: 'button', role: 'listitem',
      onClick: () => { textarea.value = question; updateComposer(); textarea.focus(); },
    }, question));
  }

  function submit() {
    const question = textarea.value.trim();
    if (!question || busy) return;
    textarea.value = '';
    actions.onAsk(question);
  }

  function updateComposer() {
    const ready = sources.filter((s) => s.state === 'ready').length;
    const indexing = sources.filter((s) => s.state === 'indexing').length;
    let text = ready === 0
      ? (indexing ? 'No ready sources yet. Indexing is still running.' : 'No sources yet. Connect a repository first.')
      : `Searching ${plural(ready, 'ready source')}`;
    if (ready && indexing) text += `, ${num(indexing)} still indexing`;
    footText.textContent = busy ? 'Working on your question' : text;
    textarea.disabled = busy;
    askButton.disabled = busy || !textarea.value.trim();
  }

  function scrollToEnd() {
    requestAnimationFrame(() => { stream.scrollTop = stream.scrollHeight; });
  }

  function hasTurns() {
    return wrap.querySelector('.turn') != null;
  }

  function renderEmpty() {
    wrap.querySelector('.welcome')?.remove();
    if (hasTurns()) return;
    const ready = sources.filter((s) => s.state === 'ready');
    wrap.append(el('div', { class: 'welcome' },
      el('div', { class: 'welcome-mark', 'aria-hidden': 'true' }, 'n'),
      el('h2', null, 'Ask how your product actually works'),
      el('p', null,
        'Ntivo reads your repositories once, then answers questions across all of them in plain language, ',
        'with the exact code it used.'),
      ready.length
        ? el('p', { class: 'muted' }, `Ready to search ${joinList(ready.map((s) => s.name))}. Try one of the questions below.`)
        : el('div', { class: 'welcome-actions' },
          el('button', { class: 'btn primary', type: 'button', onClick: () => actions.onConnect() },
            icon('plus', 16), 'Connect a repository')),
    ));
  }

  function addTurn(node) {
    wrap.querySelector('.welcome')?.remove();
    wrap.append(node);
    scrollToEnd();
    return node;
  }

  // ---- Ingestion cards ----------------------------------------------------------------------

  function renderJob(entry) {
    const source = entry.source;
    const pct = sourcePercent(source);
    const total = totalDuration(source);
    const title = source.state === 'ready' ? `Read ${source.name}`
      : source.state === 'failed' ? `Could not read ${source.name}` : `Reading ${source.name}`;
    const label = source.state === 'ready' ? el('span', { class: 'lbl ok' }, 'Ready')
      : source.state === 'failed' ? el('span', { class: 'lbl fail' }, 'Failed')
        : el('span', { class: 'lbl busy' }, pct != null ? `Indexing · ${pct}%` : 'Indexing');
    const side = source.state === 'indexing' ? timeLeft(source) : total ? `in ${duration(total)}` : null;

    const steps = (source.stages ?? []).map((stage) => {
      const copy = STEP_COPY[stage.name];
      if (!copy) return null;
      const state = stage.state === 'running' ? 'run' : stage.state;
      const message = stageMessages.get(`${source.id}:${stage.name}`);
      const titleText = stage.state === 'done' ? copy.done(stage, source, message)
        : stage.state === 'running' ? copy.running(stage, source)
          : stage.state === 'failed' ? `${copy.running(stage, source)} stopped` : copy.todo(stage, source);
      const time = stage.state === 'running' && stage.total
        ? `${num(stage.done)} of ${num(stage.total)}`
        : duration(stage.durationMs);
      return el('div', { class: `step ${state}` },
        stepMark(stage.state),
        el('div', null, titleText, ' ', el('span', { class: 'sub' }, copy.sub(source))),
        el('div', { class: 't' }, time));
    });

    replace(entry.card,
      el('div', { class: 'head' },
        el('span', { class: 'head-title' }, title),
        side ? el('span', { class: 'head-side' }, side) : null,
        label),
      steps,
      source.state === 'failed'
        ? el('div', { class: 'job-foot err' }, icon('alert', 14), source.error || 'Indexing stopped.')
        : null,
      source.state === 'ready'
        ? el('div', { class: 'job-foot' }, `Ready. ${plural(source.pieces, 'piece')} from ${plural(source.files, 'file')} can now be searched.`)
        : null,
    );
  }

  function addIngestion(source, verb = 'connected') {
    if (jobs.has(source.id)) {
      updateSource(source);
      return;
    }
    const card = el('div', { class: 'job' });
    const node = el('div', { class: 'turn bot', dataset: { sourceId: source.id } },
      avatar('bot'),
      el('div', { class: 'body' },
        el('div', { class: 'by' }, 'Ntivo ', el('span', null, `${verb} ${source.name}`)),
        card));
    const entry = { node, card, source };
    jobs.set(source.id, entry);
    renderJob(entry);
    addTurn(node);
  }

  function updateSource(source) {
    const entry = jobs.get(source.id);
    if (!entry) return;
    entry.source = source;
    renderJob(entry);
  }

  // ---- Questions and answers ----------------------------------------------------------------

  function addQuestion(text) {
    return addTurn(el('div', { class: 'turn me' },
      avatar('me'),
      el('div', { class: 'body' },
        el('div', { class: 'by' }, 'You'),
        paragraphs(text))));
  }

  function citeButton(citation, list, index) {
    return el('button', {
      class: 'cite', type: 'button',
      title: `${citation.sourceName}: ${citation.path}`,
      onClick: () => actions.onOpenCitations(list, index),
    }, basename(citation.path), ' ', el('small', null, lineRange(citation.startLine, citation.endLine)));
  }

  function answerText(answer) {
    const lines = [];
    if (answer.verdict) lines.push(answer.verdict.text, '');
    lines.push(answer.prose);
    for (const row of answer.evidence ?? []) {
      const where = row.citation ? ` (${row.citation.path} ${lineRange(row.citation.startLine, row.citation.endLine)})` : '';
      lines.push(`- ${row.label}: ${row.text}${where}`);
    }
    if (answer.couldNotSee?.length) lines.push('', `Could not see: ${answer.couldNotSee.join(', ')}`);
    return lines.join('\n');
  }

  async function copyAnswer(answer, button) {
    const text = answerText(answer);
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const area = el('textarea', { style: { position: 'fixed', opacity: '0' } }, text);
      document.body.append(area);
      area.select();
      document.execCommand('copy');
      area.remove();
    }
    const label = button.querySelector('span');
    label.textContent = 'Copied';
    setTimeout(() => { label.textContent = 'Copy'; }, 1600);
  }

  function renderAnswer(answer) {
    const citations = answer.citations ?? [];
    const evidence = answer.evidence ?? [];
    const indexOf = (citation) => Math.max(0, citations.findIndex((c) => c.pieceId === citation.pieceId));
    const allCitations = citations.length ? citations : evidence.map((r) => r.citation).filter(Boolean);
    const repos = new Set(allCitations.map((c) => c.sourceName));
    const meta = [repos.size ? `looked at ${plural(repos.size, 'repository', 'repositories')}` : null, duration(answer.durationMs)]
      .filter(Boolean).join(' · ');

    const inRows = new Set(evidence.map((r) => r.citation?.pieceId).filter(Boolean));
    const extra = allCitations.filter((c) => !inRows.has(c.pieceId));

    const indexingNames = sources.filter((s) => s.state === 'indexing').map((s) => s.name);
    const mentionsIndexing = (answer.couldNotSee ?? []).some((item) => indexingNames.some((name) => item.includes(name)));

    const actionsRow = el('div', { class: 'actions' },
      el('button', { class: 'chip trace-chip', type: 'button', onClick: () => actions.onShowTrace(answer.id) },
        el('span', null, 'How I answered'), icon('arrowRight', 12)),
      el('button', { class: 'chip', type: 'button', onClick: (e) => copyAnswer(answer, e.currentTarget) },
        icon('copy', 12), el('span', null, 'Copy')),
      allCitations.length
        ? el('button', { class: 'chip', type: 'button', onClick: () => actions.onOpenCitations(allCitations, 0) },
          icon('file', 12), el('span', null, 'Open files'))
        : null,
    );

    return [
      el('div', { class: 'by' }, 'Ntivo ', meta ? el('span', null, meta) : null),
      answer.verdict
        ? el('div', { class: `verdict ${answer.verdict.kind}` },
          icon(VERDICT_ICON[answer.verdict.kind] ?? 'info', 16),
          el('div', null, verdictBody(answer.verdict.text)))
        : null,
      paragraphs(answer.prose),
      evidence.length
        ? el('div', { class: 'finding', role: 'table', 'aria-label': 'Evidence' },
          el('div', { class: 'row', role: 'row' },
            el('div', { role: 'columnheader' }, 'Where'),
            el('div', { role: 'columnheader' }, 'What the code does')),
          evidence.map((row) => el('div', { class: 'row', role: 'row' },
            el('div', { class: 'k', role: 'cell' }, row.label),
            el('div', { role: 'cell' },
              inline(row.text),
              row.citation ? [el('br'), citeButton(row.citation, allCitations, indexOf(row.citation))] : null))))
        : null,
      extra.length
        ? el('p', { class: 'cited' }, el('span', { class: 'muted' }, 'Code cited: '),
          extra.map((c, i) => [i ? ', ' : null, citeButton(c, allCitations, indexOf(c))]))
        : null,
      answer.couldNotSee?.length
        ? el('p', { class: 'notsee' },
          'I could not see ',
          answer.couldNotSee.map((item, i) => [
            i === 0 ? null : i === answer.couldNotSee.length - 1 ? ' and ' : ', ',
            el('b', null, item)]),
          ', so this answer leaves ', answer.couldNotSee.length === 1 ? 'it' : 'them', ' out.',
          mentionsIndexing
            ? [' ', el('button', {
              class: 'linklike', type: 'button',
              onClick: () => { textarea.value = answer.question; updateComposer(); textarea.focus(); },
            }, "Ask again when it's ready"), ' and I will check there too.']
            : null)
        : null,
      answer.unverifiedCitations > 0
        ? el('p', { class: 'unverified' }, icon('alert', 12),
          `Partly unverified: ${plural(answer.unverifiedCitations, 'citation')} pointed at code I had not read, so ${answer.unverifiedCitations === 1 ? 'it was' : 'they were'} removed.`)
        : null,
      actionsRow,
    ];
  }

  function addPending() {
    const activity = el('span', { class: 'activity-text' }, 'Reading your question');
    const body = el('div', { class: 'body' },
      el('div', { class: 'by' }, 'Ntivo ', el('span', null, 'working')),
      el('div', { class: 'activity' }, el('span', { class: 'spin', 'aria-hidden': 'true' }), activity));
    const node = addTurn(el('div', { class: 'turn bot pending' }, avatar('bot'), body));
    pending = {
      setActivity(message) {
        activity.textContent = message;
      },
      resolve(answer) {
        node.classList.remove('pending');
        node.dataset.answerId = answer.id;
        answerTurns.set(answer.id, node);
        replace(body, renderAnswer(answer));
        pending = null;
        scrollToEnd();
      },
      fail(message) {
        node.classList.remove('pending');
        replace(body,
          el('div', { class: 'by' }, 'Ntivo'),
          el('div', { class: 'error-box' }, icon('alert', 16), el('div', null, el('b', null, 'I could not answer that. '), message)));
        pending = null;
        scrollToEnd();
      },
    };
    return pending;
  }

  renderEmpty();
  updateComposer();

  return {
    setSources(next) {
      sources = next;
      for (const source of next) updateSource(source);
      updateComposer();
      if (!hasTurns()) renderEmpty();
    },
    stageMessage(progress) {
      if (progress?.sourceId && progress.message) stageMessages.set(`${progress.sourceId}:${progress.stage}`, progress.message);
    },
    addIngestion,
    addQuestion,
    addPending,
    setActivity(message) {
      pending?.setActivity(message);
    },
    setBusy(value) {
      busy = value;
      updateComposer();
      if (!value) textarea.focus();
    },
    markActive(answerId) {
      for (const [id, node] of answerTurns) node.classList.toggle('active', id === answerId);
    },
    reset() {
      jobs.clear();
      answerTurns.clear();
      replace(wrap);
      renderEmpty();
    },
    focus() {
      textarea.focus();
    },
    banner(message) {
      wrap.querySelector('.banner')?.remove();
      if (message) wrap.prepend(el('div', { class: 'banner' }, icon('alert', 16), message));
    },
  };
}

