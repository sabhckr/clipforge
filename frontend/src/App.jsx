import { createContext, useContext, useState } from 'react';
import {
  LayoutDashboard, PlusSquare, ListOrdered, Film, PackageOpen, Settings as SettingsIcon, ShieldCheck,
} from 'lucide-react';
import { useRoute } from './lib/hooks.js';
import AuthGate from './components/AuthGate.jsx';
import Dashboard from './pages/Dashboard.jsx';
import NewProject from './pages/NewProject.jsx';
import Queue from './pages/Queue.jsx';
import Gallery from './pages/Gallery.jsx';
import ClipPreview from './pages/ClipPreview.jsx';
import ExportCenter from './pages/ExportCenter.jsx';
import Settings from './pages/Settings.jsx';
import Verify from './pages/Verify.jsx';

const NAV = [
  ['/', 'Dashboard', LayoutDashboard],
  ['/new', 'New project', PlusSquare],
  ['/queue', 'Processing queue', ListOrdered],
  ['/gallery', 'Clip gallery', Film],
  ['/exports', 'Export center', PackageOpen],
  ['/verify', 'Ownership', ShieldCheck],
  ['/settings', 'Settings', SettingsIcon],
];

// Hands a file picked on the dashboard to the New Project page.
const PendingFileContext = createContext({ file: null, setFile: () => {} });
export const usePendingFile = () => useContext(PendingFileContext);

function Page({ route }) {
  const { path, query } = route;
  if (path === '/') return <Dashboard />;
  if (path === '/new') return <NewProject />;
  if (path === '/queue') return <Queue highlight={query.highlight} />;
  if (path === '/gallery') return <Gallery jobId={query.job} />;
  if (path.startsWith('/clips/')) return <ClipPreview clipId={decodeURIComponent(path.slice(7))} />;
  if (path === '/exports') return <ExportCenter jobId={query.job} />;
  if (path === '/settings') return <Settings />;
  if (path === '/verify') return <Verify />;
  return (
    <div className="card">
      <h1>Page not found</h1>
      <a href="#/">Go to the dashboard</a>
    </div>
  );
}

export default function App() {
  const route = useRoute();
  const [file, setFile] = useState(null);
  const active = (p) => (p === '/' ? route.path === '/' : route.path.startsWith(p)
    || (p === '/gallery' && route.path.startsWith('/clips/')));

  return (
    <PendingFileContext.Provider value={{ file, setFile }}>
      <div className="shell">
        <aside className="sidebar">
          <a className="brand" href="#/" aria-label="CLIPFORGE home">
            <span className="brand-mark" aria-hidden />
            <span>CLIPFORGE</span>
          </a>
          <nav aria-label="Main">
            {NAV.map(([p, label, Icon]) => (
              <a key={p} href={`#${p}`} className={active(p) ? 'active' : undefined}
                aria-current={active(p) ? 'page' : undefined}>
                <Icon size={17} aria-hidden />
                <span>{label}</span>
              </a>
            ))}
          </nav>
          <div className="sidebar-foot muted small">v1.0 · deterministic · zero AI</div>
        </aside>
        <main className="main">
          <Page route={route} />
        </main>
      </div>
      <AuthGate />
    </PendingFileContext.Provider>
  );
}
