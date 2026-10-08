import { useEffect, useRef, useState } from 'react';

/** Execution flow as a vertical stepper with a "walk through" player. */
export default function FlowStepper({ steps = [] }) {
  const [active, setActive] = useState(-1);
  const [playing, setPlaying] = useState(false);
  const timer = useRef(null);

  // a new analysis resets the player
  useEffect(() => {
    setActive(-1);
    setPlaying(false);
  }, [steps]);

  useEffect(() => {
    if (!playing) return undefined;
    timer.current = setInterval(() => {
      setActive((a) => {
        if (a + 1 >= steps.length) {
          setPlaying(false);
          return a;
        }
        return a + 1;
      });
    }, 1600);
    return () => clearInterval(timer.current);
  }, [playing, steps.length]);

  if (!steps.length) {
    return <p className="empty">No steps recognised for this statement.</p>;
  }

  function play() {
    setActive(0);
    setPlaying(true);
  }

  return (
    <div>
      <div className="player">
        <button className="ghost" onClick={playing ? () => setPlaying(false) : play}>
          {playing ? '⏸ Pause' : '▶ Walk through'}
        </button>
        <button className="ghost" onClick={() => setActive((a) => Math.max(0, a - 1))} disabled={active <= 0}>
          ◀ Back
        </button>
        <button
          className="ghost"
          onClick={() => setActive((a) => Math.min(steps.length - 1, a + 1))}
          disabled={active >= steps.length - 1}
        >
          Next ▶
        </button>
        {active >= 0 && (
          <button className="ghost" onClick={() => { setActive(-1); setPlaying(false); }}>
            Reset
          </button>
        )}
      </div>

      <ol className="flow">
        {steps.map((s, i) => {
          const open = i === active;
          const dim = active >= 0 && !open;
          return (
            <li
              key={s.step}
              className={`${open ? 'open' : ''} ${dim ? 'dim' : ''}`}
              onClick={() => { setPlaying(false); setActive(open ? -1 : i); }}
            >
              <span className="num">{s.step}</span>
              <div className="body">
                <div className="op">{s.operation}</div>
                <div className="desc">{s.description}</div>
                {s.expression && <pre className="expr">{s.expression}</pre>}
                {open && s.details && <div className="details">💡 {s.details}</div>}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
