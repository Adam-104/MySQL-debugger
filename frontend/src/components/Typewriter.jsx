import { useEffect, useState } from 'react';

function reducedMotion() {
  // no matchMedia (tests, very old browsers) counts as "no animation"
  return typeof window === 'undefined' || typeof window.matchMedia !== 'function' ||
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Types the phrases one after another (erasing each before the next) and settles on the last one.
 * With reduced motion the last phrase is shown at once.
 */
export function useTyped(phrases, { typeMs = 42, eraseMs = 20, holdMs = 1700, startDelay = 250 } = {}) {
  const last = phrases[phrases.length - 1];
  const [still] = useState(reducedMotion);
  const [text, setText] = useState(still ? last : '');
  const [done, setDone] = useState(still);

  useEffect(() => {
    if (still) return undefined;
    let i = 0;
    let n = 0;
    let phase = 'type';
    let timer;
    const step = () => {
      const phrase = phrases[i];
      if (phase === 'type') {
        n += 1;
        setText(phrase.slice(0, n));
        if (n >= phrase.length) {
          if (i === phrases.length - 1) {
            setDone(true);
            return;
          }
          phase = 'erase';
          timer = setTimeout(step, holdMs);
          return;
        }
        timer = setTimeout(step, typeMs + Math.random() * 28);
      } else {
        n -= 1;
        setText(phrase.slice(0, Math.max(n, 0)));
        if (n <= 0) {
          i += 1;
          phase = 'type';
          timer = setTimeout(step, 260);
          return;
        }
        timer = setTimeout(step, eraseMs);
      }
    };
    timer = setTimeout(step, startDelay);
    return () => clearTimeout(timer);
    // phrases are constants of the caller
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [still]);

  return { text, done };
}

/**
 * Typed text that never shifts the layout: the longest phrase is rendered invisibly to reserve its space.
 * Screen readers get the final text once; the animation itself is hidden from them.
 */
export default function Typewriter({ phrases, as: Tag = 'span', className = '', ...timing }) {
  const { text, done } = useTyped(phrases, timing);
  const longest = phrases.reduce((a, b) => (b.length > a.length ? b : a), '');
  const final = phrases[phrases.length - 1];
  return (
    <Tag className={`typed ${className}`}>
      <span className="sr-only">{final}</span>
      <span className="typed-view" aria-hidden="true">
        <span className="typed-space">{longest}</span>
        <span className="typed-live">
          {text}
          <span className={`caret${done ? ' idle' : ''}`} />
        </span>
      </span>
    </Tag>
  );
}
