import { useEffect, useState } from 'react';
import { Save } from 'lucide-react';
import { api } from '../lib/api.js';
import { formatBytes } from '../lib/format.js';
import { useLoad } from '../lib/hooks.js';
import SettingsForm, { DEFAULT_SETTINGS, validateSettings } from '../components/SettingsForm.jsx';
import { ErrorBanner, KV, PageHeader, Spinner } from '../components/ui.jsx';

export default function Settings() {
  const saved = useLoad(() => api.settings());
  const health = useLoad(() => api.health());
  const stats = useLoad(() => api.stats());
  const encoders = useLoad(() => api.encoders());
  const [draft, setDraft] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (saved.data && !draft) setDraft({ ...saved.data, defaults: { ...DEFAULT_SETTINGS, ...saved.data.defaults } });
  }, [saved.data, draft]);

  if (!draft) return saved.error ? <ErrorBanner error={saved.error} onRetry={saved.reload} /> : <Spinner />;

  const errors = validateSettings(draft.defaults);
  const save = async () => {
    setError(null);
    setStatus(null);
    try {
      const res = await api.saveSettings(draft);
      setDraft(res);
      setStatus('Saved. New projects will use these defaults.');
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <PageHeader title="Settings" subtitle="Default processing settings for new projects, and owner details.">
        <button type="button" className="btn btn-ghost" onClick={() => setDraft({ ...draft, defaults: { ...DEFAULT_SETTINGS } })}>
          Restore factory defaults
        </button>
        <button type="button" className="btn btn-primary" disabled={errors.length > 0} onClick={save}>
          <Save size={15} aria-hidden /> Save
        </button>
      </PageHeader>
      <ErrorBanner error={error} />
      {errors.length > 0 && <ErrorBanner error={errors.join(' ')} />}
      {status && <div className="banner banner-ok">{status}</div>}

      <section className="card">
        <h2>Processing defaults</h2>
        <SettingsForm value={draft.defaults} onChange={(d) => setDraft({ ...draft, defaults: d })}
          hardwareEncoder={encoders.data ? encoders.data.hardware : undefined} />
      </section>

      <section className="card">
        <h2>Owner information</h2>
        <p className="muted small">
          Optional. When enabled, these details are written into each new project&apos;s signed manifest
          as an owner-declared statement. They are stored only in this installation&apos;s database.
        </p>
        <div className="grid-fields">
          <label className="field">
            <span className="field-label">Owner name</span>
            <input type="text" maxLength={200} value={draft.owner_name}
              onChange={(e) => setDraft({ ...draft, owner_name: e.target.value })} />
          </label>
          <label className="field">
            <span className="field-label">Contact (optional)</span>
            <input type="text" maxLength={200} value={draft.owner_contact}
              onChange={(e) => setDraft({ ...draft, owner_contact: e.target.value })} />
          </label>
          <label className="field toggle">
            <input type="checkbox" checked={draft.include_owner_in_manifest}
              onChange={(e) => setDraft({ ...draft, include_owner_in_manifest: e.target.checked })} />
            <span><span className="field-label">Include owner details in new manifests</span></span>
          </label>
        </div>
      </section>

      <section className="card">
        <h2>System</h2>
        {health.data && stats.data ? (
          <KV items={[
            ['Version', `${health.data.app} ${health.data.version}`],
            ['FFmpeg', health.data.ffmpeg_version],
            ['Hardware H.264 encoder', encoders.data ? (encoders.data.hardware || 'none detected') : 'checking…'],
            ['Upload limit', formatBytes(stats.data.limits.max_upload_bytes)],
            ['Concurrent jobs', stats.data.limits.max_concurrent_jobs],
            ['Queue limit', stats.data.limits.max_queued_jobs],
            ['Disk free', formatBytes(stats.data.storage.disk_free_bytes)],
            ['AI used', 'No — all processing is deterministic FFmpeg operations'],
          ]} />
        ) : <Spinner />}
        <p className="muted small">Server limits are configured with environment variables; see INSTALL.md.</p>
      </section>
    </>
  );
}
