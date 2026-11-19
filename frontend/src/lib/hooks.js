import { useCallback, useEffect, useRef, useState } from 'react';

/** Load data once (and on demand). Returns { data, error, loading, reload }. */
export function useLoad(loader, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const reload = useCallback(async () => {
    try {
      const data = await loaderRef.current();
      setState({ data, error: null, loading: false });
      return data;
    } catch (error) {
      setState((s) => ({ ...s, error, loading: false }));
      return null;
    }
  }, []);
  useEffect(() => {
    setState((s) => ({ ...s, loading: true }));
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { ...state, reload };
}

/** Poll a loader; interval may be a function of the latest data. */
export function usePoll(loader, interval, deps = []) {
  const result = useLoad(loader, deps);
  const { data, reload } = result;
  const ms = typeof interval === 'function' ? interval(data) : interval;
  useEffect(() => {
    if (!ms) return undefined;
    const t = setInterval(() => {
      if (!document.hidden) reload();
    }, ms);
    return () => clearInterval(t);
  }, [ms, reload]);
  return result;
}

/** Minimal hash router: "#/gallery?job=..." -> { path: '/gallery', query } */
export function parseHash(hash) {
  const raw = (hash || '').replace(/^#/, '') || '/';
  const [path, qs] = raw.split('?');
  return { path: path || '/', query: Object.fromEntries(new URLSearchParams(qs || '')) };
}

export function useRoute() {
  const [route, setRoute] = useState(() => parseHash(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

export function navigate(to) {
  window.location.hash = to;
}
