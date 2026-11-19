// Shared editor for ProcessingSettings — used by New Project (per-job) and Settings (defaults).

const OUTPUT_SIZES = {
  '9:16': { '1080p': '1080×1920', '720p': '720×1280' },
  '1:1': { '1080p': '1080×1080', '720p': '720×720' },
  '16:9': { '1080p': '1920×1080', '720p': '1280×720' },
};

export const DEFAULT_SETTINGS = {
  segmentation_method: 'hybrid',
  target_duration: 30,
  scene_threshold: 0.3,
  scene_window: 5,
  min_duration: 15,
  max_duration: null,
  remainder_min: 10,
  short_remainder: 'drop',
  overlap: 0,
  aspect_ratio: '9:16',
  resolution: '1080p',
  fit_mode: 'crop',
  crop_position: 'center',
  crop_offset: 0.5,
  crf: 20,
  preset: 'medium',
  encoder: 'software',
  export_format: 'mp4',
  audio_bitrate_kbps: 192,
  audio_normalize: false,
  thumbnails: true,
};

/** Mirrors the server-side consistency rules so users get immediate feedback. */
export function validateSettings(s) {
  const errors = [];
  if (!(s.target_duration >= 3 && s.target_duration <= 600)) errors.push('Clip duration must be 3–600 s.');
  if (s.min_duration > s.target_duration) errors.push('Minimum duration cannot exceed the target duration.');
  if (s.max_duration != null && s.max_duration < s.target_duration) errors.push('Maximum duration cannot be below the target duration.');
  if (s.overlap >= s.min_duration) errors.push('Overlap must be smaller than the minimum duration.');
  if (!(s.scene_threshold >= 0.05 && s.scene_threshold <= 0.95)) errors.push('Scene threshold must be 0.05–0.95.');
  if (!(s.crf >= 0 && s.crf <= 51)) errors.push('Quality (CRF) must be 0–51.');
  return errors;
}

function Num({ label, value, onChange, min, max, step = 1, suffix, disabled, hint, allowEmpty }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <span className="input-wrap">
        <input type="number" value={value ?? ''} min={min} max={max} step={step} disabled={disabled}
          placeholder={allowEmpty ? 'auto' : undefined}
          onChange={(e) => {
            const raw = e.target.value;
            if (raw === '' && allowEmpty) onChange(null);
            else if (raw !== '') onChange(Number(raw));
          }} />
        {suffix && <span className="suffix">{suffix}</span>}
      </span>
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

function Select({ label, value, onChange, options, disabled, hint }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <select value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

function Toggle({ label, checked, onChange, hint }) {
  return (
    <label className="field toggle">
      <input type="checkbox" checked={!!checked} onChange={(e) => onChange(e.target.checked)} />
      <span>
        <span className="field-label">{label}</span>
        {hint && <span className="field-hint">{hint}</span>}
      </span>
    </label>
  );
}

export default function SettingsForm({ value, onChange, hardwareEncoder }) {
  const s = value;
  const set = (key) => (v) => onChange({ ...s, [key]: v });
  const hybrid = s.segmentation_method === 'hybrid';
  const effectiveMax = s.max_duration ?? s.target_duration + s.scene_window;

  return (
    <div className="settings-form">
      <fieldset>
        <legend>Segmentation</legend>
        <div className="grid-fields">
          <Select label="Method" value={s.segmentation_method} onChange={set('segmentation_method')}
            options={[['hybrid', 'Hybrid — snap to scene cuts'], ['fixed', 'Fixed intervals']]}
            hint="Hybrid uses FFmpeg's deterministic scene score; no AI." />
          <Num label="Target clip duration" value={s.target_duration} onChange={set('target_duration')}
            min={3} max={600} step={1} suffix="s" />
          <Num label="Scene detection threshold" value={s.scene_threshold} onChange={set('scene_threshold')}
            min={0.05} max={0.95} step={0.01} disabled={!hybrid}
            hint="Lower = more sensitive (more cuts detected)." />
          <Num label="Scene search window" value={s.scene_window} onChange={set('scene_window')}
            min={0} max={120} step={0.5} suffix="± s" disabled={!hybrid} />
          <Num label="Minimum clip duration" value={s.min_duration} onChange={set('min_duration')}
            min={1} max={600} step={0.5} suffix="s" />
          <Num label="Maximum clip duration" value={s.max_duration} onChange={set('max_duration')}
            min={1} max={1200} step={0.5} suffix="s" allowEmpty
            hint={`Empty = target + window (${effectiveMax}s).`} />
          <Num label="Final remainder minimum" value={s.remainder_min} onChange={set('remainder_min')}
            min={0.5} max={600} step={0.5} suffix="s" />
          <Select label="Short final remainder" value={s.short_remainder} onChange={set('short_remainder')}
            options={[['drop', 'Skip it (reported in job notes)'], ['merge', 'Merge into previous clip']]} />
          <Num label="Overlap between clips" value={s.overlap} onChange={set('overlap')}
            min={0} max={30} step={0.5} suffix="s" hint="0 = no overlap." />
        </div>
      </fieldset>

      <fieldset>
        <legend>Format</legend>
        <div className="grid-fields">
          <Select label="Aspect ratio" value={s.aspect_ratio} onChange={set('aspect_ratio')}
            options={[['9:16', '9:16 Vertical'], ['1:1', '1:1 Square'], ['16:9', '16:9 Landscape']]} />
          <Select label="Output resolution" value={s.resolution} onChange={set('resolution')}
            options={[['1080p', `1080p — ${OUTPUT_SIZES[s.aspect_ratio]['1080p']}`],
              ['720p', `720p — ${OUTPUT_SIZES[s.aspect_ratio]['720p']}`]]} />
          <Select label="Fit" value={s.fit_mode} onChange={set('fit_mode')}
            options={[['crop', 'Crop to fill'], ['pad', 'Fit with black bars']]}
            hint="Both preserve the source aspect ratio — nothing is stretched." />
          <Select label="Crop position" value={s.crop_position} onChange={set('crop_position')}
            disabled={s.fit_mode !== 'crop'}
            options={[['center', 'Center'], ['left', 'Left / top'], ['right', 'Right / bottom'], ['custom', 'Custom']]}
            hint="A fixed crop region. CLIPFORGE does not track faces or speakers." />
          {s.fit_mode === 'crop' && s.crop_position === 'custom' && (
            <label className="field">
              <span className="field-label">Custom crop offset: {Math.round(s.crop_offset * 100)}%</span>
              <input type="range" min={0} max={1} step={0.01} value={s.crop_offset}
                onChange={(e) => set('crop_offset')(Number(e.target.value))} />
              <span className="field-hint">0% = left/top edge, 100% = right/bottom edge.</span>
            </label>
          )}
        </div>
      </fieldset>

      <fieldset>
        <legend>Video quality</legend>
        <div className="grid-fields">
          <label className="field">
            <span className="field-label">Quality (CRF {s.crf})</span>
            <input type="range" min={14} max={32} step={1} value={s.crf}
              onChange={(e) => set('crf')(Number(e.target.value))} />
            <span className="field-hint">Lower is higher quality and larger files. 18–23 is typical.</span>
          </label>
          <Select label="Encoder speed preset" value={s.preset} onChange={set('preset')}
            options={['ultrafast', 'superfast', 'veryfast', 'faster', 'fast', 'medium', 'slow', 'slower', 'veryslow']
              .map((p) => [p, p])}
            hint="Slower presets give smaller files at the same quality (software encoder)." />
          <Select label="Encoder" value={s.encoder} onChange={set('encoder')}
            options={[['software', 'Software (libx264)'],
              ['hardware', `Hardware${hardwareEncoder === undefined ? '' : hardwareEncoder ? ` (${hardwareEncoder})` : ' (none detected — falls back)'}`]]}
            hint="Hardware encoding falls back to libx264 automatically on failure." />
        </div>
      </fieldset>

      <fieldset>
        <legend>Audio &amp; export</legend>
        <div className="grid-fields">
          <Select label="Audio bitrate (AAC)" value={String(s.audio_bitrate_kbps)}
            onChange={(v) => set('audio_bitrate_kbps')(Number(v))}
            options={[['96', '96 kbps'], ['128', '128 kbps'], ['192', '192 kbps'], ['256', '256 kbps'], ['320', '320 kbps']]} />
          <Select label="Export container" value={s.export_format} onChange={set('export_format')}
            options={[['mp4', 'MP4 (H.264 + AAC)'], ['mov', 'MOV (H.264 + AAC)']]} />
          <Toggle label="Normalize loudness" checked={s.audio_normalize} onChange={set('audio_normalize')}
            hint="EBU R128 loudnorm to −16 LUFS. Off keeps source levels." />
          <Toggle label="Generate thumbnails" checked={s.thumbnails} onChange={set('thumbnails')} />
        </div>
      </fieldset>
    </div>
  );
}
