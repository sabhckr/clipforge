import { useEffect, useState } from 'react';
import { Ban, ChevronDown, ChevronRight, FileJson, Film, ListOrdered, RotateCw, Trash2 } from 'lucide-react';
import { api } from '../lib/api.js';
import { formatBytes, formatDate, formatDuration, STAGE_LABELS } from '../lib/format.js';
import { usePoll } from '../lib/hooks.js';
import { EmptyState, ErrorBanner, KV, PageHeader, ProgressBar, Spinner, StatusBadge } from '../components/ui.jsx';

const FILTERS = [['', 'All'], ['running', 'Running'], ['queued', 'Queued'], ['completed', 'Completed'],
  ['failed', 'Failed'], ['cancelled', 'Cancelled']];

function JobCard({ job, highlight, onAction }) {
  const [open, setOpen] = useState(highlight || job.status === 'failed');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  const media = job.source.media;
  const active = job.status === 'queued' || job.status === 'running';

  const act = async (fn, confirmText) => {
    if (confirmText && !window.confirm(confirmText)) return;
    setBusy(true);
    setErr(null);
    try { await fn(); await onAction(); } catch (e) { setErr(e); } finally { setBusy(false); }
  };

  return (
    <article className={`card job-card${highlight ? ' highlight' : ''}`} id={`job-${job.id}`}>
      <div className="job-head">
        <button type="button" className="icon-btn" aria-expanded={open} aria-label="Toggle details"
          onClick={() => setOpen(!open)}>
          {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>
        <div className="grow min0">
          <div className="row-wrap">
            <strong className="truncate">{job.name}</strong>
            <StatusBadge status={job.status} />
          </div>
          <div className="muted small">
            {formatDuration(media.duration)} · {media.width}×{media.height} · {formatBytes(job.source.size_bytes)}
            {' · '}{formatDate(job.created_at)}
          </div>
        </div>
        <div className="job-actions">
          {active && (
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => act(() => api.cancelJob(job.id))}><Ban size={14} aria-hidden /> Cancel</button>
          )}
          {(job.status === 'failed' || job.status === 'cancelled') && (
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => act(() => api.retryJob(job.id))}><RotateCw size={14} aria-hidden /> Retry</button>
          )}
          {job.status === 'completed' && (
            <>
              <a className="btn btn-primary btn-sm" href={`#/gallery?job=${job.id}`}><Film size={14} aria-hidden /> {job.clip_count} clips</a>
              <a className="btn btn-ghost btn-sm" href={api.manifestUrl(job.id)} download><FileJson size={14} aria-hidden /> Manifest</a>
            </>
          )}
          {!active && (
            <button type="button" className="btn btn-ghost btn-sm danger" disabled={busy} aria-label="Delete job"
              onClick={() => act(() => api.deleteJob(job.id),
                `Delete "${job.name}"? Its source upload, clips and manifest will be removed from disk.`)}>
              <Trash2 size={14} aria-hidden />
            </button>
          )}
        </div>
      </div>

      {active && (
        <div className="job-progress">
          <div className="row-between small">
            <span>{STAGE_LABELS[job.stage] || job.stage}</span>
            <span>{job.progress.toFixed(0)}%</span>
          </div>
          <ProgressBar value={job.progress} label={`${job.name} progress`} />
        </div>
      )}
      {job.status === 'failed' && job.error && <div className="banner banner-error">{job.error}</div>}
      <ErrorBanner error={err} />

      {open && (
        <div className="job-details">
          <KV items={[
            ['Job ID', <code key="id">{job.id}</code>],
            ['Stage', STAGE_LABELS[job.stage] || job.stage],
            ['Clips', job.status === 'completed' ? job.clip_count : '—'],
            ['Segmentation', job.plan ? job.plan.method_used : job.settings.segmentation_method],
            job.plan && ['Scene cuts detected / used', `${job.plan.scene_boundaries_detected.length} / ${job.plan.scene_boundaries_used}`],
            ['Output', `${job.settings.aspect_ratio} · ${job.settings.resolution} · ${job.settings.fit_mode} · CRF ${job.settings.crf}`],
            job.encoder_used && ['Encoder', job.encoder_used],
            ['Source codec', `${media.video_codec}${media.has_audio ? ` / ${media.audio_codec}` : ' / no audio'}`],
            ['Source SHA-256', <code key="h" className="hash">{job.source.sha256}</code>],
            ['Attempts', job.attempts],
            job.finished_at && ['Finished', formatDate(job.finished_at)],
          ]} />
          {job.plan?.notes?.length > 0 && (
            <ul className="notes">{job.plan.notes.map((n) => <li key={n}>{n}</li>)}</ul>
          )}
          {job.error_detail && (
            <details>
              <summary>FFmpeg error output</summary>
              <pre className="log">{job.error_detail}</pre>
            </details>
          )}
        </div>
      )}
    </article>
  );
}

export default function Queue({ highlight }) {
  const [filter, setFilter] = useState('');
  const jobs = usePoll(() => api.jobs(filter || undefined),
    (data) => ((data || []).some((j) => j.status === 'queued' || j.status === 'running') ? 1500 : 5000),
    [filter]);

  useEffect(() => {
    if (highlight && jobs.data) document.getElementById(`job-${highlight}`)?.scrollIntoView({ block: 'center' });
  }, [highlight, !!jobs.data]);

  return (
    <>
      <PageHeader title="Processing queue" subtitle="Jobs run in the background; progress updates automatically.">
        <a className="btn btn-primary" href="#/new">New project</a>
      </PageHeader>
      <div className="tabs" role="tablist">
        {FILTERS.map(([v, l]) => (
          <button key={v} type="button" role="tab" aria-selected={filter === v}
            className={filter === v ? 'active' : undefined} onClick={() => setFilter(v)}>{l}</button>
        ))}
      </div>
      <ErrorBanner error={jobs.error} onRetry={jobs.reload} />
      {jobs.loading && !jobs.data ? <Spinner /> : (jobs.data || []).length === 0 ? (
        <EmptyState icon={ListOrdered} title="No jobs here">Upload a video to start processing.</EmptyState>
      ) : (
        <div className="stack">
          {jobs.data.map((j) => (
            <JobCard key={j.id} job={j} highlight={j.id === highlight} onAction={jobs.reload} />
          ))}
        </div>
      )}
    </>
  );
}
