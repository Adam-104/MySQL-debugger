import { useEffect, useState } from 'react';
import { applyTheme, getStoredTheme, storeTheme } from '../lib/theme.js';

const OPTIONS = [
  { id: 'system', label: 'Auto', icon: '🖥️', title: 'Follow system theme' },
  { id: 'light', label: 'Light', icon: '☀️', title: 'Light theme' },
  { id: 'dark', label: 'Dark', icon: '🌙', title: 'Dark theme' },
];

export default function ThemeToggle() {
  const [mode, setMode] = useState(() => getStoredTheme());

  useEffect(() => {
    applyTheme(mode);
    storeTheme(mode);
  }, [mode]);

  return (
    <div className="theme-toggle" role="group" aria-label="Color theme">
      {OPTIONS.map((o) => (
        <button
          key={o.id}
          type="button"
          aria-pressed={mode === o.id}
          title={o.title}
          onClick={() => setMode(o.id)}
        >
          <span aria-hidden="true">{o.icon}</span>
          <span className="sr-only">{o.label}</span>
        </button>
      ))}
    </div>
  );
}
