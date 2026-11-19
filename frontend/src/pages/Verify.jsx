import { useState } from 'react';
import { CheckCircle2, Copy, Download, HardDriveDownload, ShieldCheck, XCircle } from 'lucide-react';
import { api } from '../lib/api.js';
import { useLoad } from '../lib/hooks.js';
import { ErrorBanner, KV, PageHeader, Spinner } from '../components/ui.jsx';

const STATUS_TEXT = {
  match: 'Matches manifest',
  modified: 'MODIFIED — hash differs from manifest',
  unknown: 'Not in this manifest',
};

export function Report({ report }) {
  const s = report.summary;
  const download = () => {
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'verification_report.json';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return (
    <section className="card report">
      <div className={`report-head ${s.ok ? 'ok' : 'bad'}`}>
        {s.ok ? <CheckCircle2 size={22} aria-hidden /> : <XCircle size={22} aria-hidden />}
        <div className="grow">
          <strong>{s.ok ? 'Verified' : 'Verification failed'}</strong>
          <div className="small">
            Signature {s.signature_valid ? 'valid' : 'INVALID'}
            {' · '}{s.trusted_key ? 'signed by the trusted key' : 'signer not confirmed as trusted key'}
            {' · '}{s.files_matched}/{s.files_checked} files matched
            {s.files_missing ? ` · ${s.files_missing} missing` : ''}
          </div>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={download}>
          <Download size={13} aria-hidden /> Report JSON
        </button>
      </div>
      {report.signature.error && <div className="banner banner-error">{report.signature.error}</div>}
      <KV items={[
        ['Project ID', report.project_id ? <code key="p">{report.project_id}</code> : '—'],
        ['Signing key ID', report.signature.key_id ? <code key="k">{report.signature.key_id}</code> : '—'],
        ['Algorithm', report.signature.algorithm || '—'],
        ['Verified at', report.verified_at],
      ]} />
      {(report.files.length > 0 || report.missing.length > 0) && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>File</th><th>Result</th><th>Asset</th></tr></thead>
            <tbody>
              {report.files.map((f) => (
                <tr key={`${f.name}-${f.sha256}`}>
                  <td className="truncate">{f.name}<div className="muted small hash">{f.sha256}</div></td>
                  <td className={`status-${f.status}`}>{STATUS_TEXT[f.status]}{f.role === 'source' ? ' (source video)' : ''}</td>
                  <td className="muted small">{f.asset_id || '—'}</td>
                </tr>
              ))}
              {report.missing.map((m) => (
                <tr key={m.asset_id}><td>{m.name}</td><td className="status-modified">Missing from storage</td><td className="muted small">{m.asset_id}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">{report.disclaimer}</p>
    </section>
  );
}

export default function Verify() {
  const key = useLoad(() => api.keyInfo());
  const jobs = useLoad(() => api.jobs('completed'));
  const [manifest, setManifest] = useState(null);
  const [files, setFiles] = useState([]);
  const [pubkey, setPubkey] = useState(null);
  const [trust, setTrust] = useState('local');
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [jobId, setJobId] = useState('');
  const [copied, setCopied] = useState(false);

  const run = async (fn) => {
    setBusy(true);
    setError(null);
    setReport(null);
    try { setReport(await fn()); } catch (e) { setError(e); } finally { setBusy(false); }
  };

  const verifyUpload = (e) => {
    e.preventDefault();
    const fd = new FormData();
    fd.append('manifest', manifest);
    files.forEach((f) => fd.append('files', f));
    if (pubkey) fd.append('public_key', pubkey);
    fd.append('trust', trust);
    run(() => api.verify(fd));
  };

  const copyKey = async () => {
    try {
      await navigator.clipboard.writeText(key.data.public_key_pem);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch { /* clipboard unavailable */ }
  };

  return (
    <>
      <PageHeader title="Ownership verification"
        subtitle="Check signed manifests and detect modified files using this installation's Ed25519 key." />

      <div className="banner banner-info">
        <ShieldCheck size={16} aria-hidden />
        <span>
          A valid result shows that a manifest was signed by a specific private key and that files are byte-identical
          to what was recorded. It does not prevent copying and does not by itself establish copyright ownership.
        </span>
      </div>

      <div className="two-col">
        <section className="card">
          <h2>Signing key</h2>
          {key.loading ? <Spinner /> : key.error ? <ErrorBanner error={key.error} /> : (
            <>
              <KV items={[
                ['Algorithm', key.data.algorithm],
                ['Key ID', <code key="k">{key.data.key_id}</code>],
                ['Fingerprint', <code key="f" className="hash">{key.data.fingerprint}</code>],
                ['Created', key.data.created_at],
              ]} />
              <pre className="code-block small">{key.data.public_key_pem}</pre>
              <div className="row-wrap">
                <button type="button" className="btn btn-ghost btn-sm" onClick={copyKey}>
                  <Copy size={13} aria-hidden /> {copied ? 'Copied' : 'Copy public key'}
                </button>
                <a className="btn btn-ghost btn-sm" href={api.publicKeyUrl()} download>
                  <Download size={13} aria-hidden /> public_key.pem
                </a>
              </div>
              <p className="muted small">{key.data.storage}</p>
            </>
          )}
        </section>

        <section className="card">
          <h2>Verify a project on disk</h2>
          <p className="muted small">Re-hashes every stored clip and checks the project&apos;s manifest signature.</p>
          <div className="row-wrap">
            <select aria-label="Project to verify" value={jobId} onChange={(e) => setJobId(e.target.value)}>
              <option value="">Choose a project…</option>
              {(jobs.data || []).map((j) => <option key={j.id} value={j.id}>{j.name}</option>)}
            </select>
            <button type="button" className="btn btn-primary btn-sm" disabled={!jobId || busy}
              onClick={() => run(() => api.verifyJob(jobId))}>
              <HardDriveDownload size={14} aria-hidden /> Verify
            </button>
          </div>
        </section>
      </div>

      <form className="card" onSubmit={verifyUpload}>
        <h2>Verify files</h2>
        <div className="grid-fields">
          <label className="field">
            <span className="field-label">Manifest (manifest.json) — required</span>
            <input type="file" accept=".json,application/json" onChange={(e) => setManifest(e.target.files[0] || null)} />
          </label>
          <label className="field">
            <span className="field-label">Video files to check (optional, up to 50)</span>
            <input type="file" multiple accept="video/*,.mp4,.mov,.mkv,.webm,.avi"
              onChange={(e) => setFiles(Array.from(e.target.files || []))} />
            {files.length > 0 && <span className="field-hint">{files.length} file(s) selected</span>}
          </label>
          <fieldset className="field">
            <span className="field-label">Trust</span>
            <label className="radio"><input type="radio" name="trust" value="local" checked={trust === 'local'}
              onChange={() => setTrust('local')} disabled={!!pubkey} /> Require this installation&apos;s key</label>
            <label className="radio"><input type="radio" name="trust" value="embedded" checked={trust === 'embedded'}
              onChange={() => setTrust('embedded')} disabled={!!pubkey} /> Integrity only (key embedded in manifest)</label>
          </fieldset>
          <label className="field">
            <span className="field-label">Or a specific public key (.pem)</span>
            <input type="file" accept=".pem" onChange={(e) => setPubkey(e.target.files[0] || null)} />
            <span className="field-hint">Use the public_key.pem shipped in an export to verify someone else&apos;s package.</span>
          </label>
        </div>
        <button type="submit" className="btn btn-primary" disabled={!manifest || busy}>
          <ShieldCheck size={15} aria-hidden /> {busy ? 'Verifying…' : 'Verify'}
        </button>
      </form>

      <ErrorBanner error={error} />
      {report && <Report report={report} />}
    </>
  );
}
