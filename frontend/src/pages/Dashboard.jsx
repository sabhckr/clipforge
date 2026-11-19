import { Activity, Film, HardDrive, Video } from 'lucide-react';
import { api } from '../lib/api.js';
import { formatBytes, formatDate, formatDuration, STAGE_LABELS } from '../lib/format.js';
import { navigate, usePoll } from '../lib/hooks.js';
import { usePendingFile } from '../App.jsx';
import Dropzone from '../components/Dropzone.jsx';
import { EmptyState, ErrorBanner, PageHeader, ProgressBar, Spinner, Stat, StatusBadge } from '../components/ui.jsx';

export default function Dashboard() {
  const { setFile } = usePendingFile();
  const stats = usePoll(() => api.stats(), 5000);
  const jobs = usePoll(() => api.jobs(), (data) =>
    (data || []).some((j) => j.status === 'queued' || j.status === 'running') ? 1500 : 5000);
  const health = usePoll(() => api.health(), 30000);

  const s = stats.data;
  const list = jobs.data || [];
  const active = list.filter((j) => j.status === 'queued' || j.status === 'running');

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Turn long-form video into short clips with deterministic, scene-aware cutting.">
        <a className="btn btn-primary" href="#/new">New project</a>
      </PageHeader>
      <ErrorBanner error={stats.error || jobs.error} onRetry={() => { stats.reload(); jobs.reload(); }} />
      {health.data && !(health.data.ffmpeg && health.data.ffprobe) && (
        <ErrorBanner error="FFmpeg or FFprobe was not found on the server PATH. See INSTALL.md." />
      )}

      <section className="stats-grid">
        <Stat icon={Video} label="Videos processed" value={s ? s.videos_processed : '—'}
          hint={s ? `${formatDuration(s.source_seconds_processed)} of source` : null} />
        <Stat icon={Film} label="Clips generated" value={s ? s.clips_generated : '—'}
          hint={s ? `${formatDuration(s.clip_seconds)} total` : null} />
        <Stat icon={Activity} label="Active jobs" value={s ? s.jobs_by_status.running + s.jobs_by_status.queued : '—'}
          hint={s ? `${s.jobs_by_status.failed} failed · ${s.jobs_by_status.cancelled} cancelled` : null} />
        <Stat icon={HardDrive} label="Storage used" value={s ? formatBytes(s.storage.total_bytes) : '—'}
          hint={s ? `${formatBytes(s.storage.disk_free_bytes)} free on disk` : null} />
      </section>

      <div className="two-col">
        <section className="card">
          <h2>Quick start</h2>
          <Dropzone onFile={(f) => { setFile(f); navigate('/new'); }} />
        </section>
        <section className="card">
          <h2>Processing status</h2>
          {jobs.loading && !jobs.data ? <Spinner /> : active.length === 0 ? (
            <p className="muted">Nothing is processing right now.</p>
          ) : (
            <ul className="mini-jobs">
              {active.map((j) => (
                <li key={j.id}>
                  <div className="row-between">
                    <a href={`#/queue?highlight=${j.id}`} className="truncate">{j.name}</a>
                    <span className="muted small">{Math.round(j.progress)}%</span>
                  </div>
                  <ProgressBar value={j.progress} label={`${j.name} progress`} />
                  <div className="muted small">{STAGE_LABELS[j.stage] || j.stage}</div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="card">
        <div className="row-between">
          <h2>Recent jobs</h2>
          <a href="#/queue" className="small">View all</a>
        </div>
        {jobs.loading && !jobs.data ? <Spinner /> : list.length === 0 ? (
          <EmptyState icon={Video} title="No projects yet">Upload a video you own to create your first clips.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>Project</th><th>Status</th><th>Source</th><th>Clips</th><th>Created</th></tr>
              </thead>
              <tbody>
                {list.slice(0, 8).map((j) => (
                  <tr key={j.id}>
                    <td className="truncate">
                      {j.status === 'completed'
                        ? <a href={`#/gallery?job=${j.id}`}>{j.name}</a>
                        : <a href={`#/queue?highlight=${j.id}`}>{j.name}</a>}
                    </td>
                    <td><StatusBadge status={j.status} /></td>
                    <td className="muted">{formatDuration(j.source.media.duration)} · {j.source.media.width}×{j.source.media.height}</td>
                    <td>{j.status === 'completed' ? j.clip_count : '—'}</td>
                    <td className="muted">{formatDate(j.created_at)}</td>
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
