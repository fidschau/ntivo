// Line icons drawn on a 16px grid, in the spirit of GitHub's Octicons.
// They inherit the text colour through `currentColor`.

const PATHS = {
  check: '<path d="M3.5 8.5l3 3 6-7"/>',
  x: '<path d="M4.5 4.5l7 7M11.5 4.5l-7 7"/>',
  plus: '<path d="M8 3v10M3 8h10"/>',
  chevronLeft: '<path d="M10 3.5L5.5 8l4.5 4.5"/>',
  chevronRight: '<path d="M6 3.5L10.5 8 6 12.5"/>',
  alert: '<path d="M8 2L14.5 13.5h-13z"/><path d="M8 6.5v3.2"/><path d="M8 11.8v.01" stroke-width="2"/>',
  info: '<circle cx="8" cy="8" r="6.25"/><path d="M8 7.25v4"/><path d="M8 4.9v.01" stroke-width="2"/>',
  checkCircle: '<circle cx="8" cy="8" r="6.25"/><path d="M5.3 8.2l1.9 1.9 3.5-4"/>',
  repo: '<path d="M3.25 12.75V3.5c0-.7.55-1.25 1.25-1.25h8.25v9H4.5c-.7 0-1.25.55-1.25 1.25s.55 1.25 1.25 1.25h1.75"/><path d="M8.5 12.75v2l1.25-.85 1.25.85v-2"/>',
  arrowRight: '<path d="M3 8h10M9 4l4 4-4 4"/>',
  copy: '<rect x="5.75" y="5.75" width="7.5" height="7.5" rx="1.25"/><path d="M10.25 3.5V3a.75.75 0 0 0-.75-.75H3a.75.75 0 0 0-.75.75v6.5c0 .41.34.75.75.75h.5"/>',
  file: '<path d="M4 1.75h5.25L12.5 5v9.25H4z"/><path d="M9.25 1.75V5h3.25"/>',
  trash: '<path d="M2.75 4.25h10.5M6.25 4.25V2.75h3.5v1.5M4.25 4.25l.55 9h6.4l.55-9"/>',
  upload: '<path d="M8 10.25V2.75M4.75 6L8 2.75 11.25 6M2.75 10.25v3h10.5v-3"/>',
  ticket: '<path d="M2 4.25h12v2.25a1.5 1.5 0 0 0 0 3v2.25H2V9.5a1.5 1.5 0 0 0 0-3z"/>',
  book: '<path d="M1.75 3h4.5c.97 0 1.75.78 1.75 1.75v8.5c0-.83-.67-1.5-1.5-1.5H1.75zM14.25 3h-4.5C8.78 3 8 3.78 8 4.75v8.5c0-.83.67-1.5 1.5-1.5h4.75z"/>',
  folder: '<path d="M1.75 3.25h4.5l1.5 1.5h6.5v8H1.75z"/>',
  github: '<path d="M8 1.75a6.25 6.25 0 0 0-1.98 12.18c.31.06.43-.14.43-.3v-1.1c-1.74.38-2.1-.74-2.1-.74-.29-.72-.7-.92-.7-.92-.57-.39.04-.38.04-.38.63.04.96.65.96.65.56.96 1.47.68 1.83.52.06-.4.22-.68.4-.84-1.39-.16-2.85-.7-2.85-3.1 0-.68.24-1.24.65-1.68-.07-.16-.28-.8.06-1.66 0 0 .52-.17 1.72.64a6 6 0 0 1 3.13 0c1.2-.81 1.72-.64 1.72-.64.34.86.13 1.5.06 1.66.4.44.65 1 .65 1.68 0 2.4-1.46 2.94-2.86 3.1.23.2.43.58.43 1.17v1.73c0 .17.11.37.43.3A6.25 6.25 0 0 0 8 1.75z"/>',
  sparkle: '<path d="M8 1.75v3M8 11.25v3M1.75 8h3M11.25 8h3M3.6 3.6l2.1 2.1M10.3 10.3l2.1 2.1M3.6 12.4l2.1-2.1M10.3 5.7l2.1-2.1"/>',
};

/** Returns an inline SVG icon element. */
export function icon(name, size = 16, className = '') {
  const template = document.createElement('template');
  template.innerHTML =
    `<svg class="icon ${className}" width="${size}" height="${size}" viewBox="0 0 16 16" fill="none" ` +
    `stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" ` +
    `aria-hidden="true" focusable="false">${PATHS[name] ?? ''}</svg>`;
  return template.content.firstElementChild;
}
