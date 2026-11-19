export function formatBytes(bytes) {
  if (bytes == null || Number.isNaN(bytes)) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = Number(bytes);
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

/** 75.5 -> "1:15.5"; 3725 -> "1:02:05.0" */
export function formatTimecode(seconds) {
  if (seconds == null || Number.isNaN(seconds)) return '—';
  const s = Math.max(0, Number(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = (s % 60).toFixed(1).padStart(4, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

/** 75.5 -> "1m 16s"; 12.04 -> "12.0s" */
export function formatDuration(seconds) {
  if (seconds == null || Number.isNaN(seconds)) return '—';
  const s = Number(seconds);
  if (s < 60) return `${s.toFixed(1)}s`;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = Math.round(s % 60);
  return h ? `${h}h ${m}m ${r}s` : `${m}m ${r}s`;
}

export function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export const STAGE_LABELS = {
  queued: 'Waiting in queue',
  starting: 'Starting',
  inspecting: 'Inspecting source',
  detecting_scenes: 'Detecting scenes',
  planning: 'Planning segments',
  rendering: 'Rendering clips',
  signing: 'Hashing & signing manifest',
  completed: 'Completed',
  failed: 'Failed',
  cancelled: 'Cancelled',
};

export const ALLOWED_EXTENSIONS = ['.mp4', '.m4v', '.mov', '.mkv', '.webm', '.avi'];

export function fileExtension(name) {
  const i = (name || '').lastIndexOf('.');
  return i >= 0 ? name.slice(i).toLowerCase() : '';
}

/** Client-side pre-check; the server re-validates the actual content. */
export function validateFile(file, maxBytes) {
  if (!file) return 'No file selected.';
  if (!ALLOWED_EXTENSIONS.includes(fileExtension(file.name))) {
    return `Unsupported file type. Use ${ALLOWED_EXTENSIONS.join(', ')}.`;
  }
  if (file.size === 0) return 'The file is empty.';
  if (maxBytes && file.size > maxBytes) return `File is larger than the ${formatBytes(maxBytes)} upload limit.`;
  return null;
}
