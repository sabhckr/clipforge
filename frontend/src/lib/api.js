// API client. The base URL is configurable (VITE_API_BASE); by default all
// requests are relative so the same build works behind any host or proxy.
const BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '');

export const apiUrl = (path) => `${BASE}${path}`;

export class ApiError extends Error {
  constructor(status, message, detail) {
    super(message);
    this.status = status;
    this.detail = detail;
  }
}

export function errorMessage(body, fallback) {
  const d = body?.detail;
  if (!d) return fallback;
  if (typeof d === 'string') return d;
  if (Array.isArray(d)) return d.map((e) => `${(e.loc || []).slice(1).join('.')}: ${e.msg}`).join('; ');
  if (d.errors && Array.isArray(d.errors)) {
    return `${d.message}: ${d.errors.map((e) => `${(e.loc || []).join('.')} ${e.msg}`).join('; ')}`;
  }
  return d.message || fallback;
}

async function request(path, opts = {}) {
  const res = await fetch(apiUrl(path), { credentials: 'include', ...opts });
  if (!res.ok) {
    let body = null;
    try { body = await res.json(); } catch { /* not JSON */ }
    if (res.status === 401) window.dispatchEvent(new Event('clipforge:auth-required'));
    throw new ApiError(res.status, errorMessage(body, `${res.status} ${res.statusText}`), body?.detail);
  }
  const type = res.headers.get('content-type') || '';
  return type.includes('application/json') ? res.json() : res;
}

const json = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body ?? {}),
});

/**
 * Stream a file to POST /api/jobs as the raw request body (no multipart buffering).
 * Returns { promise, abort }. onProgress receives a 0..1 fraction.
 */
export function uploadJob(file, { settings, name, onProgress } = {}) {
  const params = new URLSearchParams({ filename: file.name });
  if (settings) params.set('settings', JSON.stringify(settings));
  if (name) params.set('name', name);
  const xhr = new XMLHttpRequest();
  const promise = new Promise((resolve, reject) => {
    xhr.open('POST', apiUrl(`/api/jobs?${params}`));
    xhr.withCredentials = true;
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
    };
    xhr.onload = () => {
      let body = null;
      try { body = JSON.parse(xhr.responseText); } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body);
      else {
        if (xhr.status === 401) window.dispatchEvent(new Event('clipforge:auth-required'));
        reject(new ApiError(xhr.status, errorMessage(body, `Upload failed (${xhr.status})`), body?.detail));
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'Network error — is the CLIPFORGE backend running?'));
    xhr.onabort = () => reject(new ApiError(0, 'Upload cancelled'));
    xhr.send(file);
  });
  return { promise, abort: () => xhr.abort() };
}

export const api = {
  health: () => request('/api/health'),
  stats: () => request('/api/stats'),
  encoders: () => request('/api/encoders'),
  session: () => request('/api/session'),
  login: (token) => request('/api/session', json('POST', { token })),

  jobs: (status) => request(`/api/jobs${status ? `?status=${encodeURIComponent(status)}` : ''}`),
  job: (id) => request(`/api/jobs/${encodeURIComponent(id)}`),
  cancelJob: (id) => request(`/api/jobs/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),
  retryJob: (id) => request(`/api/jobs/${encodeURIComponent(id)}/retry`, { method: 'POST' }),
  deleteJob: (id) => request(`/api/jobs/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  jobClips: (id) => request(`/api/jobs/${encodeURIComponent(id)}/clips`),
  verifyJob: (id) => request(`/api/jobs/${encodeURIComponent(id)}/verify`, { method: 'POST' }),
  manifestUrl: (id) => apiUrl(`/api/jobs/${encodeURIComponent(id)}/manifest`),

  clips: () => request('/api/clips'),
  clip: (id) => request(`/api/clips/${encodeURIComponent(id)}`),

  exports: () => request('/api/exports'),
  createExport: (jobId, clipIds) =>
    request(`/api/jobs/${encodeURIComponent(jobId)}/exports`, json('POST', clipIds ? { clip_ids: clipIds } : {})),

  settings: () => request('/api/settings'),
  saveSettings: (s) => request('/api/settings', json('PUT', s)),

  keyInfo: () => request('/api/provenance/key'),
  publicKeyUrl: () => apiUrl('/api/provenance/public_key.pem'),
  verify: (formData) => request('/api/verify', { method: 'POST', body: formData }),
};

/** Trigger a browser download of a same-origin API URL. */
export function downloadUrl(url) {
  const a = document.createElement('a');
  a.href = url.startsWith('http') ? url : apiUrl(url);
  a.download = '';
  document.body.appendChild(a);
  a.click();
  a.remove();
}
