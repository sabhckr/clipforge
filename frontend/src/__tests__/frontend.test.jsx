import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { formatBytes, formatDuration, formatTimecode, validateFile } from '../lib/format.js';
import { errorMessage } from '../lib/api.js';
import { parseHash } from '../lib/hooks.js';
import SettingsForm, { DEFAULT_SETTINGS, validateSettings } from '../components/SettingsForm.jsx';
import { Report } from '../pages/Verify.jsx';
import { StatusBadge, ProgressBar } from '../components/ui.jsx';

afterEach(cleanup);

describe('format helpers', () => {
  it('formats bytes, durations and timecodes', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(5 * 1024 ** 3)).toBe('5.0 GB');
    expect(formatTimecode(75.5)).toBe('1:15.5');
    expect(formatTimecode(3725)).toBe('1:02:05.0');
    expect(formatDuration(12.04)).toBe('12.0s');
    expect(formatDuration(75.5)).toBe('1m 16s');
  });

  it('validates files before upload', () => {
    expect(validateFile({ name: 'a.exe', size: 10 })).toMatch(/Unsupported/);
    expect(validateFile({ name: 'a.mp4', size: 0 })).toMatch(/empty/);
    expect(validateFile({ name: 'a.MOV', size: 10 }, 5)).toMatch(/upload limit/);
    expect(validateFile({ name: 'a.mkv', size: 10 }, 100)).toBeNull();
  });
});

describe('api helpers', () => {
  it('extracts readable error messages', () => {
    expect(errorMessage({ detail: { message: 'Job not found' } }, 'x')).toBe('Job not found');
    expect(errorMessage({ detail: 'plain' }, 'x')).toBe('plain');
    expect(errorMessage(null, 'fallback')).toBe('fallback');
    expect(errorMessage({ detail: [{ loc: ['body', 'crf'], msg: 'too big' }] }, 'x')).toBe('crf: too big');
  });

  it('parses hash routes', () => {
    expect(parseHash('#/gallery?job=abc')).toEqual({ path: '/gallery', query: { job: 'abc' } });
    expect(parseHash('')).toEqual({ path: '/', query: {} });
  });
});

describe('settings', () => {
  it('mirrors server validation rules', () => {
    expect(validateSettings(DEFAULT_SETTINGS)).toEqual([]);
    expect(validateSettings({ ...DEFAULT_SETTINGS, min_duration: 40 })).toHaveLength(1);
    expect(validateSettings({ ...DEFAULT_SETTINGS, max_duration: 10 })).toHaveLength(1);
    expect(validateSettings({ ...DEFAULT_SETTINGS, overlap: 20 })).toHaveLength(1);
  });

  it('renders every control and reports changes', () => {
    const onChange = vi.fn();
    render(<SettingsForm value={DEFAULT_SETTINGS} onChange={onChange} hardwareEncoder="h264_videotoolbox" />);
    expect(screen.getByText('Scene detection threshold')).toBeTruthy();
    expect(screen.getByText(/1080p — 1080×1920/)).toBeTruthy();
    expect(screen.getByText(/Hardware \(h264_videotoolbox\)/)).toBeTruthy();
    const aspect = screen.getByDisplayValue('9:16 Vertical');
    fireEvent.change(aspect, { target: { value: '1:1' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ aspect_ratio: '1:1' }));
  });

  it('disables scene controls for fixed segmentation', () => {
    render(<SettingsForm value={{ ...DEFAULT_SETTINGS, segmentation_method: 'fixed' }} onChange={() => {}} />);
    const threshold = screen.getByText('Scene detection threshold').closest('label').querySelector('input');
    expect(threshold.disabled).toBe(true);
  });
});

describe('components', () => {
  it('renders status badges and clamps progress', () => {
    render(<><StatusBadge status="failed" /><ProgressBar value={150} label="p" /></>);
    expect(screen.getByText('failed')).toBeTruthy();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100');
  });

  it('renders a verification report with modified files flagged', () => {
    const report = {
      project_id: 'p1', verified_at: '2026-01-01T00:00:00Z', disclaimer: 'Not proof of copyright.',
      signature: { valid: true, trusted_key: true, key_id: 'abc', algorithm: 'Ed25519', error: null },
      files: [
        { name: 'clip_0001.mp4', sha256: 'aa', status: 'match', role: 'output', asset_id: 'a1' },
        { name: 'clip_0002.mp4', sha256: 'bb', status: 'modified', role: 'output', asset_id: 'a2' },
      ],
      missing: [],
      summary: { ok: false, signature_valid: true, trusted_key: true, files_checked: 2, files_matched: 1, files_missing: 0 },
    };
    render(<Report report={report} />);
    expect(screen.getByText('Verification failed')).toBeTruthy();
    expect(screen.getByText(/MODIFIED/)).toBeTruthy();
    expect(screen.getByText('Not proof of copyright.')).toBeTruthy();
  });
});
