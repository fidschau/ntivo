// Talks to the Ntivo server. With `?mock=1` in the URL (or `window.NTIVO_MOCK = 1` set before
// this module loads) every call goes to mock.js instead, which plays a scripted scenario.
//
// Both implementations expose the same functions:
//   listSources(), connectGithub({url, branch, token}), uploadFolder(name, files, onProgress),
//   deleteSource(id), ask(question, sourceIds), getTrace(answerId), getPiece(pieceId),
//   subscribe(handlers) -> unsubscribe

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const params = new URLSearchParams(window.location.search);
export const isMock = params.has('mock') ? params.get('mock') !== '0' : Boolean(window.NTIVO_MOCK);

/** Resolves to the API implementation for this page. */
export async function getApi() {
  return isMock ? import('./mock.js') : real;
}

/** Pulls a readable message out of an error response (plain text or JSON). */
async function errorMessage(response) {
  let text = '';
  try {
    text = await response.text();
  } catch {
    // Body unreadable: fall back to the status below.
  }
  try {
    const json = JSON.parse(text);
    text = json.error || json.message || text;
  } catch {
    // Not JSON: keep the text.
  }
  text = String(text || '').trim();
  if (text && text.length < 300 && !text.startsWith('<')) return text;
  if (response.status === 503) return 'The server is not ready to answer. Check that a model key is set.';
  return `The server answered ${response.status}.`;
}

async function request(method, url, body) {
  const options = { method, headers: { Accept: 'application/json' } };
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(url, options);
  } catch {
    throw new ApiError(0, 'Could not reach the Ntivo server.');
  }
  if (!response.ok) throw new ApiError(response.status, await errorMessage(response));
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

const real = {
  listSources: () => request('GET', '/api/sources'),

  connectGithub: ({ url, branch, token }) =>
    request('POST', '/api/sources/github', {
      url,
      ...(branch ? { branch } : {}),
      ...(token ? { token } : {}),
    }),

  /**
   * Uploads a folder as multipart: field `name`, then one `files[]` part per file whose
   * filename is its path relative to the chosen folder. Uses XHR for upload progress.
   */
  uploadFolder(name, files, onProgress) {
    const form = new FormData();
    form.append('name', name);
    for (const { file, path } of files) form.append('files[]', file, path);
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/sources/upload');
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && onProgress) onProgress(event.loaded / event.total);
      };
      xhr.onerror = () => reject(new ApiError(0, 'Could not reach the Ntivo server.'));
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            resolve(JSON.parse(xhr.responseText));
          } catch {
            reject(new ApiError(xhr.status, 'The server sent an answer I could not read.'));
          }
        } else {
          errorMessage(new Response(xhr.responseText, { status: xhr.status })).then((message) =>
            reject(new ApiError(xhr.status, message)),
          );
        }
      };
      xhr.send(form);
    });
  },

  deleteSource: (id) => request('DELETE', `/api/sources/${encodeURIComponent(id)}`),

  ask: (question, sourceIds) =>
    request('POST', '/api/ask', sourceIds?.length ? { question, sourceIds } : { question }),

  getTrace: (answerId) => request('GET', `/api/answers/${encodeURIComponent(answerId)}/trace`),

  getPiece: (pieceId) => request('GET', `/api/pieces/${encodeURIComponent(pieceId)}`),

  /**
   * Listens to /api/events. Handlers: 'source.updated', 'job.progress', 'answer.activity',
   * plus `open` (also called after each reconnect) and `error`.
   */
  subscribe(handlers) {
    const source = new EventSource('/api/events');
    for (const type of ['source.updated', 'job.progress', 'answer.activity']) {
      source.addEventListener(type, (event) => {
        let data;
        try {
          data = JSON.parse(event.data);
        } catch {
          return;
        }
        handlers[type]?.(data);
      });
    }
    source.onopen = () => handlers.open?.();
    source.onerror = () => handlers.error?.();
    return () => source.close();
  },
};
