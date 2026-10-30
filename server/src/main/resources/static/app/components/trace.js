// Right column: how the latest (or the selected) answer was produced. Collapses to a 40px strip.

import { el, replace } from '../dom.js';
import { icon } from '../icons.js';
import { duration, num } from '../format.js';

const LIMIT_COPY = {
  toolCalls: 'Stopped early: it reached the limit of 6 steps for one question.',
  time: 'Stopped early: it ran out of the 60 seconds allowed for one question.',
  tokens: 'Stopped early: it reached the length budget for one question.',
};

/** Bolds "3 closest pieces" and "2 connections" inside the server's explanation sentence. */
function explanation(text) {
  const out = [];
  const pattern = /(\d+ closest pieces?|\d+ connections?)/g;
  let last = 0;
  for (const match of String(text ?? '').matchAll(pattern)) {
    if (match.index > last) out.push(text.slice(last, match.index));
    out.push(el('b', null, match[0]));
    last = match.index + match[0].length;
  }
  if (last < String(text ?? '').length) out.push(String(text).slice(last));
  return out;
}

function section(title, ...children) {
  return el('div', { class: 'section' }, el('h4', null, title), children);
}

/**
 * @param root the <aside> element
 * @param actions { onOpenPiece(tracePiece), onToggle() }
 */
export function createTrace(root, actions) {
  let collapsed = false;
  let view = { kind: 'empty' };

  function head() {
    return el('div', { class: 'aside-head' },
      el('h4', null, 'How it answered'),
      el('button', { class: 'btn-icon', type: 'button', title: 'Collapse', 'aria-label': 'Collapse how it answered', onClick: () => actions.onToggle() },
        icon('chevronRight', 16)));
  }

  function emptyBody() {
    return el('div', { class: 'trace-empty' },
      el('p', null, 'Ask a question and this panel shows how the answer was found:'),
      el('ul', null,
        el('li', null, el('b', null, 'Closest pieces: '), 'the code that matched your question best, and how closely.'),
        el('li', null, el('b', null, 'Connections followed: '), 'calls and reads Ntivo traced from those pieces.'),
        el('li', null, el('b', null, 'Not available yet: '), 'sources it could not look at, so you know what the answer leaves out.')));
  }

  function traceBody(trace, answer) {
    const pieces = trace.pieces ?? [];
    const hops = trace.hops ?? [];
    return [
      answer?.question ? el('p', { class: 'trace-question', title: answer.question }, answer.question) : null,
      el('div', { class: 'explain' }, explanation(trace.explanation)),
      (trace.limitsHit ?? []).map((name) =>
        el('div', { class: 'limit' }, icon('alert', 14), LIMIT_COPY[name] ?? `Stopped early: ${name} limit reached.`)),
      section('Closest pieces',
        pieces.length
          ? pieces.map((piece) => el('button', {
            class: 'piece', type: 'button', title: `Open ${piece.name}`,
            onClick: () => actions.onOpenPiece(piece),
          },
          el('div', { class: 'nm' }, piece.name),
          el('div', { class: 'path' }, `${piece.sourceName.split('/').pop()} · ${piece.language}`),
          el('div', { class: 'sim' },
            el('div', { class: 'bar' }, el('i', { style: { width: `${Math.max(0, Math.min(1, piece.score)) * 100}%` } })),
            el('div', { class: 'v' }, Number(piece.score).toFixed(2)))))
          : el('p', { class: 'muted small' }, 'No piece was close enough to use.')),
      section('Connections followed',
        hops.length
          ? hops.map((hop) => el('div', { class: 'hop' },
            el('span', { class: 'end', title: hop.from }, hop.from),
            el('span', { class: 'rel' }, hop.relation),
            el('span', { class: 'end', title: hop.to }, hop.to, ' ', el('span', { class: 'conf' }, Number(hop.confidence).toFixed(1)))))
          : el('p', { class: 'muted small' }, 'No connections were followed for this answer.')),
      trace.unavailable?.length
        ? section('Not available yet',
          trace.unavailable.map((item) => el('p', { class: 'unavailable' }, `${item}. Not checked for this answer.`)))
        : null,
      trace.toolCalls?.length
        ? el('details', { class: 'section steps' },
          el('summary', null, `Steps taken (${num(trace.toolCalls.length)})`),
          el('ol', null, trace.toolCalls.map((call) => el('li', null,
            el('div', null, el('code', null, call.tool), ' ', el('span', { class: 'muted' }, duration(call.durationMs))),
            el('div', { class: 'args' }, call.args),
            el('div', { class: 'muted' }, call.summary)))))
        : null,
      trace.model ? el('p', { class: 'model' }, `Answered with ${trace.model}`) : null,
    ];
  }

  function render() {
    root.classList.toggle('collapsed', collapsed);
    if (collapsed) {
      const count = view.kind === 'trace' ? (view.trace.pieces ?? []).length : null;
      replace(root, el('button', {
        class: 'strip', type: 'button', title: 'Show how it answered', 'aria-label': 'Expand how it answered',
        onClick: () => actions.onToggle(),
      },
      icon('chevronLeft', 16),
      el('span', { class: 'vlabel' }, 'How it answered'),
      count != null ? el('span', { class: 'count', title: `${count} pieces` }, num(count)) : null));
      return;
    }
    let body;
    if (view.kind === 'loading') body = el('div', { class: 'trace-loading' }, el('span', { class: 'spin', 'aria-hidden': 'true' }), 'Loading');
    else if (view.kind === 'error') body = el('p', { class: 'muted small' }, view.message);
    else if (view.kind === 'trace') body = traceBody(view.trace, view.answer);
    else body = emptyBody();
    replace(root, head(), el('div', { class: 'aside-scroll' }, body));
  }

  render();

  return {
    showEmpty() { view = { kind: 'empty' }; render(); },
    showLoading() { view = { kind: 'loading' }; render(); },
    showError(message) { view = { kind: 'error', message }; render(); },
    show(trace, answer) { view = { kind: 'trace', trace, answer }; render(); },
    setCollapsed(value) { collapsed = value; render(); },
  };
}
