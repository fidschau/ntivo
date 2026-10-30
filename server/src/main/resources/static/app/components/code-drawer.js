// Read-only code drawer over the right column. Shows one or more cited pieces with the
// cited lines highlighted. Closes with its button or Escape.

import { el, replace } from '../dom.js';
import { icon } from '../icons.js';
import { basename, lineRange } from '../format.js';

/**
 * @param root the drawer element (hidden until opened)
 * @param api  provides getPiece(id)
 */
export function createDrawer(root, api) {
  const cache = new Map();
  let items = [];
  let index = 0;
  let returnFocus = null;
  let token = 0;

  root.setAttribute('role', 'dialog');
  root.setAttribute('aria-label', 'Cited code');

  function loadPiece(pieceId) {
    if (!cache.has(pieceId)) {
      const promise = api.getPiece(pieceId);
      promise.catch(() => cache.delete(pieceId));
      cache.set(pieceId, promise);
    }
    return cache.get(pieceId);
  }

  function header(item, piece) {
    const start = item.startLine || piece?.startLine;
    const end = item.endLine || piece?.endLine;
    return el('div', { class: 'drawer-head' },
      el('div', { class: 'drawer-titles' },
        el('div', { class: 'drawer-title' }, piece?.qualifiedName || piece?.name || basename(item.path) || 'Code'),
        el('div', { class: 'drawer-sub' },
          [item.sourceName || piece?.sourceName, item.path || piece?.path, lineRange(start, end)].filter(Boolean).join(' · '))),
      el('button', { class: 'btn-icon', type: 'button', 'aria-label': 'Close', title: 'Close (Escape)', onClick: close },
        icon('x', 16)));
  }

  function tabs() {
    if (items.length < 2) return null;
    return el('div', { class: 'drawer-tabs', role: 'tablist' },
      items.map((item, i) => el('button', {
        class: `drawer-tab${i === index ? ' selected' : ''}`, type: 'button', role: 'tab',
        'aria-selected': String(i === index), title: item.path,
        onClick: () => show(i),
      }, basename(item.path) || item.name || `Piece ${i + 1}`)));
  }

  function codeBlock(item, piece) {
    const lines = String(piece.text ?? '').replace(/\n$/, '').split('\n');
    const first = piece.startLine || 1;
    const hlStart = item.startLine || 0;
    const hlEnd = item.endLine || item.startLine || 0;
    const rows = lines.map((line, i) => {
      const number = first + i;
      const highlighted = hlStart && number >= hlStart && number <= hlEnd;
      return el('div', { class: `ln-row${highlighted ? ' hl' : ''}` },
        el('span', { class: 'ln', 'aria-hidden': 'true' }, String(number)),
        el('span', { class: 'lc' }, line || ' '));
    });
    return el('pre', { class: 'code', tabindex: '0', 'aria-label': `${piece.name}, lines ${first} to ${first + lines.length - 1}` },
      el('code', null, rows));
  }

  async function show(i) {
    index = i;
    const item = items[index];
    const mine = ++token;
    replace(root, header(item, null), tabs(),
      el('div', { class: 'drawer-body' }, el('div', { class: 'trace-loading' }, el('span', { class: 'spin', 'aria-hidden': 'true' }), 'Loading code')));
    root.querySelector('.btn-icon')?.focus();
    try {
      const piece = await loadPiece(item.pieceId);
      if (mine !== token) return;
      replace(root, header(item, piece), tabs(), el('div', { class: 'drawer-body' }, codeBlock(item, piece)));
      root.querySelector('.drawer-head .btn-icon')?.focus();
      root.querySelector('.ln-row.hl')?.scrollIntoView({ block: 'center' });
    } catch (error) {
      if (mine !== token) return;
      replace(root, header(item, null), tabs(),
        el('div', { class: 'drawer-body' }, el('p', { class: 'drawer-error' }, `Could not load this code. ${error.message || ''}`)));
    }
  }

  function close() {
    if (root.hidden) return;
    token++;
    root.hidden = true;
    replace(root);
    returnFocus?.focus?.();
    returnFocus = null;
  }

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !root.hidden && !document.querySelector('dialog[open]')) {
      event.preventDefault();
      close();
    }
  });

  return {
    /** Opens the drawer on `list[start]`. Each item: { pieceId, sourceName, path, startLine, endLine }. */
    open(list, start = 0) {
      if (!list?.length) return;
      if (root.hidden) returnFocus = document.activeElement;
      items = list;
      root.hidden = false;
      show(Math.min(Math.max(0, start), list.length - 1));
    },
    close,
  };
}
