import { AlertTriangle, CheckCircle2, CircleDashed, Loader2, XCircle, Ban } from 'lucide-react';

const STATUS_ICONS = {
  queued: CircleDashed,
  running: Loader2,
  completed: CheckCircle2,
  failed: XCircle,
  cancelled: Ban,
};

export function StatusBadge({ status }) {
  const Icon = STATUS_ICONS[status] || CircleDashed;
  return (
    <span className={`badge badge-${status}`}>
      <Icon size={13} className={status === 'running' ? 'spin' : undefined} aria-hidden />
      {status}
    </span>
  );
}

export function ProgressBar({ value, label }) {
  const pct = Math.max(0, Math.min(100, Number(value) || 0));
  return (
    <div className="progress" role="progressbar" aria-valuenow={pct} aria-valuemin={0}
      aria-valuemax={100} aria-label={label || 'Progress'}>
      <div className="progress-fill" style={{ width: `${pct}%` }} />
    </div>
  );
}

export function ErrorBanner({ error, onRetry }) {
  if (!error) return null;
  const message = typeof error === 'string' ? error : error.message;
  return (
    <div className="banner banner-error" role="alert">
      <AlertTriangle size={16} aria-hidden />
      <span>{message}</span>
      {onRetry && <button type="button" className="btn btn-ghost btn-sm" onClick={onRetry}>Retry</button>}
    </div>
  );
}

export function EmptyState({ icon: Icon, title, children }) {
  return (
    <div className="empty">
      {Icon && <Icon size={28} aria-hidden />}
      <h3>{title}</h3>
      {children && <div className="muted">{children}</div>}
    </div>
  );
}

export function PageHeader({ title, subtitle, children }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {children && <div className="page-actions">{children}</div>}
    </header>
  );
}

export function Stat({ label, value, hint, icon: Icon }) {
  return (
    <div className="stat card">
      <div className="stat-label">{Icon && <Icon size={15} aria-hidden />}{label}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint muted">{hint}</div>}
    </div>
  );
}

export function KV({ items }) {
  return (
    <dl className="kv">
      {items.filter(Boolean).map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd>{v}</dd></div>
      ))}
    </dl>
  );
}

export function Spinner({ label = 'Loading…' }) {
  return <div className="loading muted"><Loader2 size={16} className="spin" aria-hidden /> {label}</div>;
}
