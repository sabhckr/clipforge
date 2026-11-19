import { useEffect, useMemo, useState } from 'react';
import { CheckSquare, Download, Film, Package, Play, Square, Volume2, VolumeX } from 'lucide-react';
import { api, apiUrl, downloadUrl } from '../lib/api.js';
import { formatBytes, formatDuration, formatTimecode } from '../lib/format.js';
import { navigate, useLoad } from '../lib/hooks.js';
import { EmptyState, ErrorBanner, PageHeader, Spinner } from '../components/ui.jsx';

function ClipCard({ clip, selected, onToggle }) {
  return (
    <article className={`clip-card card${selected ? ' selected' : ''}`}>
      <a className="clip-thumb" href={`#/clips/${clip.id}`} aria-label={`Preview ${clip.filename}`}
        style={{ aspectRatio: `${clip.width} / ${clip.height}` }}>
        {clip.urls.thumbnail
          ? <img src={apiUrl(clip.urls.thumbnail)} alt="" loading="lazy" />
          : <Film size={28} aria-hidden />}
        <span className="clip-duration">{formatDuration(clip.duration)}</span>
        <span className="clip-play"><Play size={20} aria-hidden /></span>
      </a>
      <div className="clip-body">
        <div className="row-between">
          <strong className="truncate">{clip.filename}</strong>
          <button type="button" className="icon-btn" aria-pressed={selected}
            aria-label={selected ? `Deselect ${clip.filename}` : `Select ${clip.filename}`} onClick={onToggle}>
            {selected ? <CheckSquare size={17} className="accent" /> : <Square size={17} />}
          </button>
        </div>
        <div className="muted small mono">{formatTimecode(clip.start)} → {formatTimecode(clip.end)}</div>
        <div className="muted small">
          {clip.width}×{clip.height} · {formatBytes(clip.size_bytes)} ·{' '}
          {clip.has_audio ? <Volume2 size={12} aria-label="has audio" /> : <VolumeX size={12} aria-label="no audio" />}
        </div>
        <div className="muted small">cut: {clip.boundary.start} → {clip.boundary.end}</div>
        <div className="clip-actions">
          <a className="btn btn-ghost btn-sm" href={`#/clips/${clip.id}`}><Play size={13} aria-hidden /> Preview</a>
          <a className="btn btn-ghost btn-sm" href={apiUrl(clip.urls.download)} download>
            <Download size={13} aria-hidden /> Download
          </a>
        </div>
      </div>
    </article>
  );
}

export default function Gallery({ jobId }) {
  const jobs = useLoad(() => api.jobs('completed'));
  const completed = jobs.data || [];
  const currentId = jobId || completed[0]?.id;
  const job = completed.find((j) => j.id === currentId);
  const clips = useLoad(() => (currentId ? api.jobClips(currentId) : Promise.resolve([])), [currentId]);
  const [selected, setSelected] = useState(() => new Set());
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState(null);

  useEffect(() => { setSelected(new Set()); }, [currentId]);

  const list = clips.data || [];
  const totalSel = useMemo(() => list.filter((c) => selected.has(c.id)), [list, selected]);
  const toggle = (id) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });

  const exportZip = async (ids) => {
    setExporting(true);
    setExportError(null);
    try {
      const exp = await api.createExport(currentId, ids);
      downloadUrl(exp.download_url);
    } catch (e) {
      setExportError(e);
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader title="Clip gallery" subtitle={job ? `${job.name} — ${job.clip_count} clips from ${formatDuration(job.source.media.duration)} of source` : 'Browse rendered clips by project.'}>
        {completed.length > 0 && (
          <select aria-label="Project" value={currentId || ''} onChange={(e) => navigate(`/gallery?job=${e.target.value}`)}>
            {completed.map((j) => <option key={j.id} value={j.id}>{j.name}</option>)}
          </select>
        )}
      </PageHeader>
      <ErrorBanner error={jobs.error || clips.error} onRetry={() => { jobs.reload(); clips.reload(); }} />
      <ErrorBanner error={exportError} />

      {jobs.loading && !jobs.data ? <Spinner /> : completed.length === 0 ? (
        <EmptyState icon={Film} title="No clips yet">Completed projects appear here. <a href="#/new">Start a project</a>.</EmptyState>
      ) : jobId && !job ? (
        <EmptyState icon={Film} title="Project not found or not completed"><a href="#/queue">Check the queue</a>.</EmptyState>
      ) : (
        <>
          <div className="toolbar card">
            <span className="small">{totalSel.length} of {list.length} selected</span>
            <button type="button" className="btn btn-ghost btn-sm" disabled={!list.length}
              onClick={() => setSelected(new Set(list.map((c) => c.id)))}>Select all</button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={!selected.size}
              onClick={() => setSelected(new Set())}>Clear</button>
            <span className="grow" />
            <button type="button" className="btn btn-ghost btn-sm" disabled={!totalSel.length || exporting}
              onClick={() => exportZip(totalSel.map((c) => c.id))}>
              <Package size={14} aria-hidden /> Export selected (ZIP)
            </button>
            <button type="button" className="btn btn-primary btn-sm" disabled={!list.length || exporting}
              onClick={() => exportZip(null)}>
              <Package size={14} aria-hidden /> {exporting ? 'Packaging…' : 'Export all (ZIP)'}
            </button>
          </div>
          {clips.loading && !clips.data ? <Spinner /> : (
            <div className="clip-grid">
              {list.map((c) => (
                <ClipCard key={c.id} clip={c} selected={selected.has(c.id)} onToggle={() => toggle(c.id)} />
              ))}
            </div>
          )}
        </>
      )}
    </>
  );
}
