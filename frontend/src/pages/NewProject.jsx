import { useEffect, useRef, useState } from 'react';
import { FileVideo, RotateCcw, X } from 'lucide-react';
import { api, uploadJob } from '../lib/api.js';
import { fileExtension, formatBytes, formatDuration, validateFile } from '../lib/format.js';
import { navigate, useLoad } from '../lib/hooks.js';
import { usePendingFile } from '../App.jsx';
import Dropzone from '../components/Dropzone.jsx';
import SettingsForm, { DEFAULT_SETTINGS, validateSettings } from '../components/SettingsForm.jsx';
import { ErrorBanner, KV, PageHeader, ProgressBar } from '../components/ui.jsx';

/** Read duration / resolution in the browser when the container is playable here. */
function useLocalMetadata(file) {
  const [meta, setMeta] = useState(null);
  useEffect(() => {
    setMeta(null);
    if (!file) return undefined;
    const url = URL.createObjectURL(file);
    const v = document.createElement('video');
    v.preload = 'metadata';
    v.muted = true;
    let done = false;
    const finish = (m) => { if (!done) { done = true; setMeta(m); } };
    v.onloadedmetadata = () => finish({
      duration: Number.isFinite(v.duration) ? v.duration : null,
      width: v.videoWidth || null,
      height: v.videoHeight || null,
    });
    v.onerror = () => finish({ unavailable: true });
    const timer = setTimeout(() => finish({ unavailable: true }), 8000);
    v.src = url;
    return () => { clearTimeout(timer); v.removeAttribute('src'); URL.revokeObjectURL(url); };
  }, [file]);
  return meta;
}

export default function NewProject() {
  const pending = usePendingFile();
  const [file, setFile] = useState(pending.file);
  const [name, setName] = useState('');
  const [settings, setSettings] = useState(null);
  const [upload, setUpload] = useState(null); // { progress, abort }
  const [error, setError] = useState(null);
  const [created, setCreated] = useState(null);
  const abortRef = useRef(null);

  const saved = useLoad(() => api.settings());
  const stats = useLoad(() => api.stats());
  const encoders = useLoad(() => api.encoders());
  const meta = useLocalMetadata(file);

  useEffect(() => { if (pending.file) pending.setFile(null); }, []); // consume once
  useEffect(() => {
    if (saved.data && !settings) setSettings({ ...DEFAULT_SETTINGS, ...saved.data.defaults });
  }, [saved.data, settings]);
  useEffect(() => () => abortRef.current?.(), []);

  const maxBytes = stats.data?.limits?.max_upload_bytes;
  const fileError = file ? validateFile(file, maxBytes) : null;
  const settingsErrors = settings ? validateSettings(settings) : [];
  const canStart = file && !fileError && settings && settingsErrors.length === 0 && !upload;

  const choose = (f) => {
    setFile(f);
    setCreated(null);
    setError(null);
    if (!name) setName(f.name.replace(/\.[^.]+$/, '').slice(0, 80));
  };

  const start = async () => {
    setError(null);
    const { promise, abort } = uploadJob(file, {
      settings,
      name: name.trim() || undefined,
      onProgress: (p) => setUpload((u) => (u ? { ...u, progress: p } : u)),
    });
    abortRef.current = abort;
    setUpload({ progress: 0 });
    try {
      const job = await promise;
      setCreated(job);
      setFile(null);
      setName('');
    } catch (err) {
      setError(err);
    } finally {
      abortRef.current = null;
      setUpload(null);
    }
  };

  return (
    <>
      <PageHeader title="New project" subtitle="Upload a video you own or have permission to process." />
      <ErrorBanner error={error} />
      {created && (
        <div className="banner banner-ok">
          <span>
            <strong>{created.name}</strong> was validated by the server and queued —
            {' '}{formatDuration(created.source.media.duration)}, {created.source.media.width}×{created.source.media.height},
            {' '}{created.source.media.video_codec}{created.source.media.has_audio ? ` + ${created.source.media.audio_codec}` : ', no audio'}.
          </span>
          <button className="btn btn-sm btn-primary" type="button"
            onClick={() => navigate(`/queue?highlight=${created.id}`)}>Open queue</button>
        </div>
      )}

      <section className="card">
        <h2>Source video</h2>
        {!file ? <Dropzone onFile={choose} /> : (
          <div className="file-panel">
            <FileVideo size={30} aria-hidden />
            <div className="grow">
              <KV items={[
                ['File', file.name],
                ['Size', formatBytes(file.size)],
                ['Type', fileExtension(file.name).replace('.', '').toUpperCase() || 'unknown'],
                ['Duration', meta == null ? 'Reading…' : meta.unavailable ? 'Inspected by the server on upload' : formatDuration(meta.duration)],
                ['Resolution', meta == null ? 'Reading…' : meta.unavailable || !meta.width ? 'Inspected by the server on upload' : `${meta.width}×${meta.height}`],
              ]} />
              {fileError && <div className="banner banner-error">{fileError}</div>}
            </div>
            <button type="button" className="btn btn-ghost btn-sm" disabled={!!upload}
              onClick={() => setFile(null)}><RotateCcw size={14} aria-hidden /> Change</button>
          </div>
        )}
        <label className="field" style={{ marginTop: 16, maxWidth: 480 }}>
          <span className="field-label">Project name</span>
          <input type="text" maxLength={120} value={name} placeholder="Defaults to the file name"
            onChange={(e) => setName(e.target.value)} />
        </label>
      </section>

      <section className="card">
        <div className="row-between">
          <h2>Processing settings</h2>
          {saved.data && (
            <button type="button" className="btn btn-ghost btn-sm"
              onClick={() => setSettings({ ...DEFAULT_SETTINGS, ...saved.data.defaults })}>
              Reset to saved defaults
            </button>
          )}
        </div>
        <ErrorBanner error={saved.error} onRetry={saved.reload} />
        {settings && (
          <SettingsForm value={settings} onChange={setSettings}
            hardwareEncoder={encoders.data ? encoders.data.hardware : undefined} />
        )}
        {settingsErrors.length > 0 && (
          <div className="banner banner-error">{settingsErrors.join(' ')}</div>
        )}
      </section>

      <div className="sticky-actions card">
        {upload ? (
          <div className="upload-progress">
            <div className="row-between">
              <span>Uploading… {Math.round(upload.progress * 100)}%</span>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => abortRef.current?.()}>
                <X size={14} aria-hidden /> Cancel upload
              </button>
            </div>
            <ProgressBar value={upload.progress * 100} label="Upload progress" />
            {upload.progress >= 1 && <div className="muted small">Validating with FFprobe…</div>}
          </div>
        ) : (
          <>
            <span className="muted small">
              {settings ? `${settings.segmentation_method === 'hybrid' ? 'Scene-aware' : 'Fixed'} ~${settings.target_duration}s clips · ${settings.aspect_ratio} · ${settings.resolution} · CRF ${settings.crf}` : ''}
            </span>
            <button type="button" className="btn btn-primary" disabled={!canStart} onClick={start}>
              Upload &amp; process
            </button>
          </>
        )}
      </div>
    </>
  );
}
