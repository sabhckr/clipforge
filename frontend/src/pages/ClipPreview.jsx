import { ArrowLeft, ChevronLeft, ChevronRight, Download } from 'lucide-react';
import { api, apiUrl } from '../lib/api.js';
import { formatBytes, formatDate, formatDuration, formatTimecode } from '../lib/format.js';
import { useLoad } from '../lib/hooks.js';
import { ErrorBanner, KV, PageHeader, Spinner } from '../components/ui.jsx';

export default function ClipPreview({ clipId }) {
  const clip = useLoad(() => api.clip(clipId), [clipId]);
  const c = clip.data;
  const job = useLoad(() => (c ? api.job(c.job_id) : Promise.resolve(null)), [c?.job_id]);
  const siblings = useLoad(() => (c ? api.jobClips(c.job_id) : Promise.resolve([])), [c?.job_id]);

  if (clip.loading && !c) return <Spinner />;
  if (clip.error) return <ErrorBanner error={clip.error} onRetry={clip.reload} />;
  if (!c) return null;

  const list = siblings.data || [];
  const pos = list.findIndex((x) => x.id === c.id);
  const prev = pos > 0 ? list[pos - 1] : null;
  const next = pos >= 0 && pos < list.length - 1 ? list[pos + 1] : null;

  return (
    <>
      <PageHeader title={c.filename} subtitle={job.data ? `${job.data.name} · clip ${c.index} of ${list.length || '…'}` : null}>
        <a className="btn btn-ghost" href={`#/gallery?job=${c.job_id}`}><ArrowLeft size={15} aria-hidden /> Gallery</a>
        <a className="btn btn-primary" href={apiUrl(c.urls.download)} download><Download size={15} aria-hidden /> Download</a>
      </PageHeader>
      <div className="preview-layout">
        <div className="card preview-player">
          <video key={c.id} controls playsInline preload="metadata" src={apiUrl(c.urls.stream)}
            poster={c.urls.thumbnail ? apiUrl(c.urls.thumbnail) : undefined}
            style={{ aspectRatio: `${c.width} / ${c.height}` }} />
          <div className="row-between">
            {prev ? <a className="btn btn-ghost btn-sm" href={`#/clips/${prev.id}`}><ChevronLeft size={14} aria-hidden /> {prev.filename}</a> : <span />}
            {next ? <a className="btn btn-ghost btn-sm" href={`#/clips/${next.id}`}>{next.filename} <ChevronRight size={14} aria-hidden /></a> : <span />}
          </div>
        </div>
        <div className="card">
          <h2>Clip details</h2>
          <KV items={[
            ['Source range', <span key="r" className="mono">{formatTimecode(c.start)} → {formatTimecode(c.end)}</span>],
            ['Duration', formatDuration(c.duration)],
            ['Resolution', `${c.width}×${c.height}`],
            ['File size', formatBytes(c.size_bytes)],
            ['Audio', c.has_audio ? 'Preserved from source (AAC)' : 'None (source has no audio)'],
            ['Start boundary', c.boundary.start],
            ['End boundary', c.boundary.end],
            ['Created', formatDate(c.created_at)],
            ['Asset ID', <code key="a">{c.id}</code>],
            ['SHA-256', <code key="h" className="hash">{c.sha256}</code>],
            job.data && ['Parent asset', <code key="p">{job.data.source.asset_id}</code>],
          ]} />
          <p className="muted small">
            The asset ID and hash are recorded in this project&apos;s signed manifest.
            Verify a copy of this file on the <a href="#/verify">Ownership</a> page.
          </p>
        </div>
      </div>
    </>
  );
}
