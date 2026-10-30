// Formatting helpers for counts, durations and source progress.

export const num = (n) => Number(n ?? 0).toLocaleString('en-US');

/** "1 piece", "6,201 pieces". */
export function plural(n, one, many = `${one}s`) {
  return `${num(n)} ${n === 1 ? one : many}`;
}

/** "4s", "1m 12s", "1h 3m". Empty for zero. */
export function duration(ms) {
  if (!ms || ms <= 0) return '';
  const seconds = Math.round(ms / 1000);
  if (seconds < 1) return 'under 1s';
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** "just now", "5m ago", "2h ago", "3d ago". */
export function timeAgo(timestamp, now = Date.now()) {
  if (!timestamp) return '';
  const seconds = Math.max(0, Math.round((now - timestamp) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export const basename = (path) => String(path ?? '').split('/').pop();

/** "L41–L52" (en dash), or "L41" for a single line. */
export function lineRange(start, end) {
  if (!start) return '';
  return !end || end === start ? `L${start}` : `L${start}–L${end}`;
}

/** "a", "a and b", "a, b and c". */
export function joinList(items) {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

export const STAGE_ORDER = ['fetch', 'split', 'embed', 'connect'];

/** The stage currently running, if any. */
export function runningStage(source) {
  return (source?.stages ?? []).find((stage) => stage.state === 'running');
}

/** Percentage done of the running stage, or null when its size is not known yet. */
export function sourcePercent(source) {
  const stage = runningStage(source);
  if (!stage || !stage.total) return null;
  return Math.max(0, Math.min(100, Math.round((stage.done * 100) / stage.total)));
}

/** Rough time left for the running stage, from its pace so far. */
export function timeLeft(source) {
  const stage = runningStage(source);
  if (!stage || !stage.total || !stage.done || !stage.durationMs || stage.done >= stage.total) return null;
  const ms = (stage.durationMs / stage.done) * (stage.total - stage.done);
  return ms >= 1000 ? `about ${duration(ms)} left` : null;
}

/** Total time spent on all stages. */
export function totalDuration(source) {
  return (source?.stages ?? []).reduce((sum, stage) => sum + (stage.durationMs || 0), 0);
}

/** One plain line describing where indexing is, for the sources list. */
export function progressLine(source) {
  const stage = runningStage(source);
  if (!stage) return 'Waiting to start';
  const counts = stage.total ? `${num(stage.done)} of ${num(stage.total)}` : '';
  switch (stage.name) {
    case 'fetch':
      return source.kind === 'github' ? 'Fetching from GitHub' : 'Reading the upload';
    case 'split':
      return counts ? `Splitting ${counts} files` : 'Splitting into pieces';
    case 'embed':
      return counts ? `Capturing meaning, ${counts}` : 'Capturing meaning';
    case 'connect':
      return 'Mapping connections';
    default:
      return 'Indexing';
  }
}
