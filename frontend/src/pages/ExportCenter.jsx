import { useEffect, useState } from 'react';
import { Download, FileJson, KeyRound, Package, PackageOpen } from 'lucide-react';
import { api, apiUrl, downloadUrl } from '../lib/api.js';
import { formatBytes, formatDate, formatDuration } from '../lib/format.js';
import { useLoad } from '../lib/hooks.js';
import { EmptyState, ErrorBanner, PageHeader, Spinner } from '../components/ui.jsx';

const STRUCTURE = `CLIPFORGE_EXPORT/
  project.json
  manifest.json            signed provenance manifest
  clips/clip_0001.mp4 …
  thumbnails/clip_0001.jpg …
  verification/
    public_key.pem
    verification_report.json`;

export default function ExportCenter({ jobId }) {
  const jobs = useLoad(() => api.jobs('completed'));
  const history = useLoad(() => api.exports());
  const [selected, setSelected] = useState(jobId || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [last, setLast] = useState(null);

  useEffect(() => {
    if (!selected && jobs.data?.length) setSelected(jobs.data[0].id);
  }, [jobs.data, selected]);

  const job = (jobs.data || []).find((j) => j.id === selected);

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const exp = await api.createExport(selected, null);
      setLast(exp);
      downloadUrl(exp.download_url);
      history.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Export center" subtitle="Package clips with their signed manifest and verification files." />
      <ErrorBanner error={jobs.error || history.error || error} />

      <div className="two-col">
        <section className="card">
          <h2>New export</h2>
          {jobs.loading && !jobs.data ? <Spinner /> : !jobs.data?.length ? (
            <p className="muted">No completed projects yet.</p>
          ) : (
            <>
              <label className="field">
                <span className="field-label">Project</span>
                <select value={selected} onChange={(e) => setSelected(e.target.value)}>
                  {jobs.data.map((j) => <option key={j.id} value={j.id}>{j.name} — {j.clip_count} clips</option>)}
                </select>
              </label>
              {job && (
                <p className="muted small">
                  {job.clip_count} clips · {formatDuration(job.source.media.duration)} source ·
                  {' '}{job.settings.aspect_ratio} {job.settings.resolution} {job.settings.export_format.toUpperCase()}
                </p>
              )}
              <div className="row-wrap">
                <button type="button" className="btn btn-primary" disabled={!selected || busy} onClick={create}>
                  <Package size={15} aria-hidden /> {busy ? 'Packaging & verifying…' : 'Export all clips (ZIP)'}
                </button>
                {selected && <a className="btn btn-ghost" href={`#/gallery?job=${selected}`}>Select specific clips…</a>}
                {selected && <a className="btn btn-ghost" href={api.manifestUrl(selected)} download><FileJson size={15} aria-hidden /> Manifest only</a>}
              </div>
              {last && (
                <div className={`banner ${last.verification_ok ? 'banner-ok' : 'banner-error'}`}>
                  {last.filename}: {last.clip_count} clips, {formatBytes(last.size_bytes)}.
                  {' '}{last.verification_ok ? 'All files verified against the signed manifest.' : 'Verification found problems — see verification_report.json in the ZIP.'}
                </div>
              )}
            </>
          )}
        </section>
        <section className="card">
          <h2>Export structure</h2>
          <pre className="code-block">{STRUCTURE}</pre>
          <a className="btn btn-ghost btn-sm" href={api.publicKeyUrl()} download>
            <KeyRound size={14} aria-hidden /> Download public key
          </a>
        </section>
      </div>

      <section className="card">
        <h2>Export history</h2>
        {history.loading && !history.data ? <Spinner /> : !history.data?.length ? (
          <EmptyState icon={PackageOpen} title="No exports yet" />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Created</th><th>Project</th><th>Clips</th><th>Size</th><th /></tr></thead>
              <tbody>
                {history.data.map((e) => (
                  <tr key={e.id}>
                    <td className="muted">{formatDate(e.created_at)}</td>
                    <td className="truncate">{e.job_name}</td>
                    <td>{e.clip_count}</td>
                    <td>{formatBytes(e.size_bytes)}</td>
                    <td className="right">
                      {e.available ? (
                        <a className="btn btn-ghost btn-sm" href={apiUrl(e.download_url)} download>
                          <Download size={13} aria-hidden /> ZIP
                        </a>
                      ) : <span className="muted small" title="Older ZIPs are removed by the retention limit">expired</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
