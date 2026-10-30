// Left column: connected code sources, the "Connect a repository" affordance and the
// sources that are coming next. Collapses to a strip of 32px icon tiles.

import { el, replace } from '../dom.js';
import { icon } from '../icons.js';
import { plural, progressLine, sourcePercent, timeAgo } from '../format.js';

const COMING_NEXT = [
  { name: 'Jira', meta: 'Tickets and requirements', icon: 'ticket' },
  { name: 'Confluence', meta: 'Specs and decisions', icon: 'book' },
];

const STATE_LABEL = { ready: 'Ready', indexing: 'Indexing', failed: 'Failed' };
const STATE_CLASS = { ready: 'ok', indexing: 'busy', failed: 'fail' };

function metaLine(source) {
  if (source.state === 'failed') return source.error || 'Indexing stopped';
  if (source.state === 'indexing') return progressLine(source);
  const parts = [];
  if (source.languages?.length) parts.push(source.languages.join(', '));
  parts.push(plural(source.pieces, 'piece'));
  if (source.lastSyncedAt) parts.push(`synced ${timeAgo(source.lastSyncedAt)}`);
  return parts.join(' · ');
}

function tooltip(source) {
  const pct = sourcePercent(source);
  const state = source.state === 'indexing' && pct != null ? `Indexing ${pct}%` : STATE_LABEL[source.state];
  return `${source.name}: ${state}`;
}

/**
 * @param root the <nav> element
 * @param actions { onConnect(), onRemove(source), onToggle(), onExpand() }
 */
export function createSources(root, actions) {
  let lastSources = [];
  let collapsed = false;

  function card(source) {
    return el('div', { class: `src is-${source.state}`, title: source.name, dataset: { id: source.id } },
      el('span', { class: 'ico' }, icon('repo', 14)),
      el('div', { class: 'info' },
        el('div', { class: 'name' }, source.name),
        el('div', { class: 'meta' }, metaLine(source)),
        source.state === 'indexing'
          ? el('div', { class: 'progress', role: 'progressbar', 'aria-label': `${source.name} progress`, 'aria-valuemin': 0, 'aria-valuemax': 100 },
            el('i'))
          : null,
      ),
      el('span', { class: `lbl ${STATE_CLASS[source.state]} state` }, STATE_LABEL[source.state]),
      el('button', {
        class: 'remove', type: 'button', title: `Remove ${source.name}`, 'aria-label': `Remove ${source.name}`,
        onClick: () => actions.onRemove(lastSources.find((s) => s.id === source.id) ?? source),
      }, icon('trash', 14)),
    );
  }

  /** Updates the parts of a card or tile that change while indexing, without replacing nodes. */
  function patch(source) {
    const node = root.querySelector(`[data-id="${CSS.escape(source.id)}"]`);
    if (!node) return;
    if (collapsed) {
      node.title = tooltip(source);
      node.setAttribute('aria-label', tooltip(source));
      return;
    }
    const pct = sourcePercent(source) ?? 0;
    node.querySelector('.meta').textContent = metaLine(source);
    const bar = node.querySelector('.progress');
    if (bar) {
      bar.setAttribute('aria-valuenow', String(pct));
      bar.querySelector('i').style.width = `${pct}%`;
    }
  }

  const structureKey = (sources) => `${collapsed}|${sources.map((s) => `${s.id}:${s.state}`).join(',')}`;
  let renderedKey = null;

  function expandedView(sources) {
    return [
      el('div', { class: 'side-scroll' },
        el('div', { class: 'section' },
          el('h4', null, 'Code'),
          sources.length === 0
            ? el('p', { class: 'side-empty' }, 'No repositories yet. Connect one and Ntivo reads it so you can ask questions about it.')
            : sources.map(card),
          el('button', { class: 'add', type: 'button', onClick: () => actions.onConnect() },
            icon('plus', 16), 'Connect a repository'),
        ),
        el('div', { class: 'section soon-list' },
          el('h4', null, 'Coming next'),
          COMING_NEXT.map((item) =>
            el('div', { class: 'src', 'aria-disabled': 'true' },
              el('span', { class: 'ico' }, icon(item.icon, 14)),
              el('div', { class: 'info' },
                el('div', { class: 'name' }, item.name),
                el('div', { class: 'meta' }, item.meta)),
              el('span', { class: 'lbl soon state' }, 'Soon'))),
        ),
      ),
      el('div', { class: 'side-foot' },
        el('button', { class: 'btn-icon wide', type: 'button', 'aria-label': 'Collapse sources', title: 'Collapse sources', onClick: () => actions.onToggle() },
          icon('chevronLeft', 16), el('span', null, 'Collapse')),
      ),
    ];
  }

  function collapsedView(sources) {
    return [
      el('div', { class: 'side-scroll tiles' },
        sources.map((source) =>
          el('button', {
            class: 'tile', type: 'button', title: tooltip(source), 'aria-label': tooltip(source), dataset: { id: source.id },
            onClick: () => actions.onExpand(),
          }, icon('repo', 16), el('span', { class: `dot ${STATE_CLASS[source.state]}` }))),
        el('button', {
          class: 'tile add-tile', type: 'button', title: 'Connect a repository', 'aria-label': 'Connect a repository',
          onClick: () => actions.onConnect(),
        }, icon('plus', 16)),
      ),
      el('div', { class: 'side-foot' },
        el('button', { class: 'btn-icon', type: 'button', 'aria-label': 'Expand sources', title: 'Expand sources', onClick: () => actions.onToggle() },
          icon('chevronRight', 16)),
      ),
    ];
  }

  function render() {
    const key = structureKey(lastSources);
    if (key === renderedKey) {
      lastSources.forEach(patch);
      return;
    }
    renderedKey = key;
    const keepScroll = root.querySelector('.side-scroll')?.scrollTop ?? 0;
    replace(root, collapsed ? collapsedView(lastSources) : expandedView(lastSources));
    const scroller = root.querySelector('.side-scroll');
    if (scroller) scroller.scrollTop = keepScroll;
    lastSources.forEach(patch);
  }

  return {
    update(sources) {
      lastSources = sources;
      render();
    },
    setCollapsed(value) {
      collapsed = value;
      render();
    },
  };
}
