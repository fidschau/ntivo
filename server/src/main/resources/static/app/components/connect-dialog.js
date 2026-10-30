// "Connect a repository" modal with two ways in: a GitHub URL, or a folder upload.

import { el, replace } from '../dom.js';
import { icon } from '../icons.js';
import { num, plural } from '../format.js';

// Mirrors the server: only these files are read, and these folders are skipped.
const SUPPORTED = new Set(['kt', 'swift']);
const SKIP_DIRS = new Set(['build', '.git', 'Pods', 'node_modules']);

function supported(path) {
  const parts = path.split('/');
  if (parts.slice(0, -1).some((part) => SKIP_DIRS.has(part))) return false;
  const ext = parts[parts.length - 1].split('.').pop().toLowerCase();
  return SUPPORTED.has(ext);
}

function megabytes(bytes) {
  const mb = bytes / (1024 * 1024);
  return mb < 0.1 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${mb.toFixed(mb < 10 ? 1 : 0)} MB`;
}

/** Walks dropped folders. Entries must be taken from the event synchronously, before any await. */
async function walkEntries(entries) {
  const out = [];
  async function walk(entry, prefix) {
    if (entry.isFile) {
      const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
      out.push({ file, path: `${prefix}${entry.name}` });
    } else if (entry.isDirectory) {
      if (SKIP_DIRS.has(entry.name)) return;
      const reader = entry.createReader();
      let batch;
      do {
        batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
        for (const child of batch) await walk(child, `${prefix}${entry.name}/`);
      } while (batch.length);
    }
  }
  for (const entry of entries) await walk(entry, '');
  return out;
}

function field(label, input, note, optional = false) {
  return el('div', { class: 'field' },
    el('label', { for: input.id }, label, optional ? el('span', { class: 'optional' }, ' optional') : null),
    input,
    note ? el('p', { class: 'note', id: `${input.id}-note` }, note) : null);
}

/**
 * @param dialog the <dialog> element
 * @param api    provides connectGithub and uploadFolder
 * @param actions { onConnected(source) }
 */
export function createConnectDialog(dialog, api, actions) {
  let tab = 'github';
  let picked = []; // [{ file, path }] of supported files
  let skipped = 0;
  let working = false;

  // ---- GitHub tab ---------------------------------------------------------------------------
  const url = el('input', { id: 'gh-url', class: 'text', type: 'url', placeholder: 'https://github.com/owner/repository', autocomplete: 'off', spellcheck: 'false', required: true });
  const branch = el('input', { id: 'gh-branch', class: 'text', type: 'text', placeholder: 'Default branch', autocomplete: 'off', spellcheck: 'false' });
  const token = el('input', { id: 'gh-token', class: 'text', type: 'password', placeholder: 'ghp_...', autocomplete: 'off', 'aria-describedby': 'gh-token-note' });
  const githubPanel = el('div', { class: 'tab-panel', id: 'panel-github', role: 'tabpanel', 'aria-labelledby': 'tab-github' },
    field('Repository URL', url),
    field('Branch', branch, null, true),
    field('Personal access token', token, 'Only needed for private repositories. Kept in memory for this session only, never saved.', true));

  // ---- Upload tab ---------------------------------------------------------------------------
  const folderInput = el('input', { type: 'file', webkitdirectory: true, multiple: true, hidden: true });
  const name = el('input', { id: 'up-name', class: 'text', type: 'text', placeholder: 'android-app', autocomplete: 'off' });
  const summary = el('div', { class: 'summary', hidden: true, 'aria-live': 'polite' });
  const drop = el('div', { class: 'drop', tabindex: '0', role: 'button', 'aria-label': 'Choose a folder to upload' },
    icon('upload', 24),
    el('p', null, el('b', null, 'Drop a folder here'), ' or ', el('span', { class: 'linklike' }, 'choose one')),
    el('p', { class: 'note' }, 'Only Kotlin and Swift files are sent. Build output and dependencies are skipped.'));
  const uploadPanel = el('div', { class: 'tab-panel', id: 'panel-upload', role: 'tabpanel', 'aria-labelledby': 'tab-upload', hidden: true },
    drop, folderInput, summary, field('Name', name, 'How this source is labelled in answers.'));

  // ---- Frame ------------------------------------------------------------------------------
  const error = el('p', { class: 'form-error', role: 'alert', hidden: true });
  const submit = el('button', { class: 'btn primary', type: 'submit' }, 'Connect');
  const tabGithub = el('button', { class: 'tab', id: 'tab-github', type: 'button', role: 'tab', 'aria-controls': 'panel-github', onClick: () => setTab('github') }, icon('github', 16), 'GitHub URL');
  const tabUpload = el('button', { class: 'tab', id: 'tab-upload', type: 'button', role: 'tab', 'aria-controls': 'panel-upload', onClick: () => setTab('upload') }, icon('folder', 16), 'Upload a folder');
  const form = el('form', { method: 'dialog', onSubmit: onSubmit },
    el('div', { class: 'dialog-head' },
      el('h2', { id: 'connect-title' }, 'Connect a repository'),
      el('button', { class: 'btn-icon', type: 'button', 'aria-label': 'Close', onClick: () => close() }, icon('x', 16))),
    el('div', { class: 'tabs', role: 'tablist' }, tabGithub, tabUpload),
    el('div', { class: 'dialog-body' }, githubPanel, uploadPanel, error),
    el('div', { class: 'dialog-foot' },
      el('button', { class: 'btn', type: 'button', onClick: () => close() }, 'Cancel'),
      submit));
  dialog.setAttribute('aria-labelledby', 'connect-title');
  replace(dialog, form);

  function setTab(next) {
    tab = next;
    tabGithub.setAttribute('aria-selected', String(tab === 'github'));
    tabUpload.setAttribute('aria-selected', String(tab === 'upload'));
    githubPanel.hidden = tab !== 'github';
    uploadPanel.hidden = tab !== 'upload';
    showError('');
    refresh();
  }

  function showError(message) {
    error.textContent = message;
    error.hidden = !message;
  }

  function refresh() {
    if (tab === 'upload') {
      submit.disabled = working || picked.length === 0;
    } else {
      submit.disabled = working;
    }
    submit.textContent = working ? (tab === 'upload' ? 'Uploading' : 'Connecting') : 'Connect';
  }

  function setPicked(files) {
    picked = files.filter((f) => supported(f.path));
    skipped = files.length - picked.length;
    const bytes = picked.reduce((sum, f) => sum + f.file.size, 0);
    const kotlin = picked.filter((f) => f.path.toLowerCase().endsWith('.kt')).length;
    const swift = picked.length - kotlin;
    if (!name.value) {
      const top = files[0]?.path.includes('/') ? files[0].path.split('/')[0] : '';
      if (top) name.value = top;
    }
    summary.hidden = files.length === 0;
    if (picked.length === 0) {
      replace(summary, el('b', null, 'No Kotlin or Swift files in this folder.'), ' Choose another folder.');
      summary.classList.add('warn');
    } else {
      summary.classList.remove('warn');
      replace(summary,
        el('div', null, el('b', null, `${plural(picked.length, 'file')}, ${megabytes(bytes)}`)),
        el('div', { class: 'muted' },
          [kotlin ? `${num(kotlin)} Kotlin` : null, swift ? `${num(swift)} Swift` : null].filter(Boolean).join(', '),
          skipped ? `. ${plural(skipped, 'other file')} will not be sent.` : '.'));
    }
    showError('');
    refresh();
  }

  folderInput.addEventListener('change', () => {
    setPicked([...folderInput.files].map((file) => ({ file, path: file.webkitRelativePath || file.name })));
  });
  drop.addEventListener('click', () => folderInput.click());
  drop.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      folderInput.click();
    }
  });
  drop.addEventListener('dragover', (event) => {
    event.preventDefault();
    drop.classList.add('over');
  });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', async (event) => {
    event.preventDefault();
    drop.classList.remove('over');
    const items = [...(event.dataTransfer?.items ?? [])];
    const entries = items.map((item) => item.webkitGetAsEntry?.()).filter(Boolean);
    const files = entries.length
      ? await walkEntries(entries)
      : [...(event.dataTransfer?.files ?? [])].map((file) => ({ file, path: file.name }));
    setPicked(files);
  });

  async function onSubmit(event) {
    event.preventDefault();
    if (working) return;
    showError('');
    working = true;
    refresh();
    try {
      let source;
      if (tab === 'github') {
        const value = url.value.trim();
        if (!value) throw new Error('Enter a repository URL.');
        source = await api.connectGithub({ url: value, branch: branch.value.trim(), token: token.value.trim() });
      } else {
        const label = name.value.trim() || 'upload';
        source = await api.uploadFolder(label, picked, (fraction) => {
          submit.textContent = `Uploading ${Math.round(fraction * 100)}%`;
        });
      }
      working = false;
      close(true);
      actions.onConnected(source);
    } catch (err) {
      working = false;
      showError(err.message || 'Something went wrong.');
      refresh();
    }
  }

  function reset() {
    url.value = '';
    branch.value = '';
    token.value = '';
    name.value = '';
    folderInput.value = '';
    picked = [];
    skipped = 0;
    summary.hidden = true;
    showError('');
  }

  function close(clear = false) {
    // The token never outlives the dialog.
    token.value = '';
    if (clear) reset();
    if (dialog.open) dialog.close();
  }

  dialog.addEventListener('close', () => { token.value = ''; });

  setTab('github');

  return {
    open(which = 'github') {
      if (working) return;
      setTab(which);
      dialog.showModal();
      (which === 'github' ? url : drop).focus();
    },
  };
}
