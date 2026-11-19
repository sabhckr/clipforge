import { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
import { api } from '../lib/api.js';

/** Shown only when the backend runs with CLIPFORGE_API_TOKEN and the session is missing. */
export default function AuthGate() {
  const [needed, setNeeded] = useState(false);
  const [token, setToken] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.session().then((s) => setNeeded(s.auth_required && !s.authenticated)).catch(() => {});
    const onAuth = () => setNeeded(true);
    window.addEventListener('clipforge:auth-required', onAuth);
    return () => window.removeEventListener('clipforge:auth-required', onAuth);
  }, []);

  if (!needed) return null;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.login(token);
      setNeeded(false);
      window.location.reload();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop">
      <form className="modal card" onSubmit={submit}>
        <h2><KeyRound size={18} aria-hidden /> Access token required</h2>
        <p className="muted">This CLIPFORGE server requires the access token configured by its owner.</p>
        <label className="field">
          <span className="field-label">Token</span>
          <input type="password" autoComplete="current-password" value={token}
            onChange={(e) => setToken(e.target.value)} autoFocus />
        </label>
        {error && <div className="banner banner-error">{error}</div>}
        <button className="btn btn-primary" type="submit" disabled={busy || !token}>Sign in</button>
      </form>
    </div>
  );
}
