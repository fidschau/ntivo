// Small DOM helpers. Everything that comes from the server is added as text, never as HTML.

/**
 * Creates an element. Props: `class`, `style` (object), `onX` listeners, `dataset` (object),
 * boolean attributes (true adds, false/null skips) and plain attributes.
 */
export function el(tag, props, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'style' && typeof value === 'object') Object.assign(node.style, value);
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value') node.value = value;
    else if (value === true) node.setAttribute(key, '');
    else node.setAttribute(key, String(value));
  }
  append(node, children);
  return node;
}

/** Appends children, flattening arrays and skipping null, undefined and false. */
export function append(node, children) {
  for (const child of [children].flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** Replaces all children of `node`. */
export function replace(node, ...children) {
  node.replaceChildren();
  return append(node, children);
}

/**
 * Renders a line of model prose: `code` spans and **bold** runs, everything else as text.
 */
export function inline(text) {
  const out = [];
  const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*)/g;
  let last = 0;
  for (const match of String(text ?? '').matchAll(pattern)) {
    if (match.index > last) out.push(text.slice(last, match.index));
    const token = match[0];
    out.push(token.startsWith('`') ? el('code', null, token.slice(1, -1)) : el('b', null, token.slice(2, -2)));
    last = match.index + token.length;
  }
  if (last < String(text ?? '').length) out.push(String(text).slice(last));
  return out;
}

/** Splits prose into paragraphs on blank lines; single newlines become line breaks. */
export function paragraphs(text, className) {
  return String(text ?? '')
    .split(/\n\s*\n/)
    .map((block) => block.trim())
    .filter(Boolean)
    .map((block) => {
      const lines = block.split('\n');
      const parts = [];
      lines.forEach((line, i) => {
        if (i > 0) parts.push(el('br'));
        parts.push(inline(line));
      });
      return el('p', { class: className }, parts);
    });
}

let toastTimer;
/** Shows a short status message at the bottom of the window. */
export function toast(message) {
  const node = document.getElementById('toast');
  if (!node) return;
  node.textContent = message;
  node.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('show'), 2600);
}

/** localStorage that never throws (private windows, blocked storage). */
export const prefs = {
  get(key, fallback) {
    try {
      const value = localStorage.getItem(`ntivo.${key}`);
      return value == null ? fallback : JSON.parse(value);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(`ntivo.${key}`, JSON.stringify(value));
    } catch {
      // Storage unavailable: the preference lasts for this page only.
    }
  },
};
