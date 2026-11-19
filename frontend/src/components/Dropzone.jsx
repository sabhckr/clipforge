import { useRef, useState } from 'react';
import { UploadCloud } from 'lucide-react';
import { ALLOWED_EXTENSIONS } from '../lib/format.js';

export default function Dropzone({ onFile, compact = false, disabled = false }) {
  const inputRef = useRef(null);
  const [over, setOver] = useState(false);

  const pick = (files) => {
    if (disabled || !files || !files.length) return;
    onFile(files[0]);
  };

  return (
    <div
      className={`dropzone${over ? ' over' : ''}${compact ? ' compact' : ''}${disabled ? ' disabled' : ''}`}
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-label="Upload a video: drop a file or press Enter to browse"
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); inputRef.current?.click(); } }}
      onDragOver={(e) => { e.preventDefault(); if (!disabled) setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); pick(e.dataTransfer.files); }}
    >
      <UploadCloud size={compact ? 22 : 34} aria-hidden />
      <div>
        <strong>Drop a video here</strong> or <span className="link">browse files</span>
        <div className="muted small">{ALLOWED_EXTENSIONS.join(' · ').toUpperCase().replaceAll('.', '')}</div>
      </div>
      <input
        ref={inputRef}
        type="file"
        hidden
        accept={`${ALLOWED_EXTENSIONS.join(',')},video/*`}
        onChange={(e) => { pick(e.target.files); e.target.value = ''; }}
      />
    </div>
  );
}
