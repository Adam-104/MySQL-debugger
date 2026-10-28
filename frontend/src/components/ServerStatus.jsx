import { useEffect, useState } from 'react';
import { onWaking } from '../api.js';

/** Shown while a request is slow, which on a free host usually means the backend is waking from sleep. */
export default function ServerStatus() {
  const [waking, setWaking] = useState(false);
  useEffect(() => onWaking(setWaking), []);
  if (!waking) return null;
  return (
    <div className="wake" role="status">
      <span className="wake-dot" aria-hidden="true" />
      <div>
        <b>Waking up the server…</b>
        <span>Free hosting sleeps when idle. The first request can take up to a minute; later ones are fast.</span>
      </div>
    </div>
  );
}
