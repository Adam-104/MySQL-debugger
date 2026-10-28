import ThemeToggle from './ThemeToggle.jsx';
import Typewriter from './Typewriter.jsx';

const TAGLINES = [
  'Why is this query slow?',
  'Which index is missing?',
  'See how MySQL runs your SQL, why it’s slow, and how to fix it.',
];

export default function Header() {
  return (
    <header className="topbar">
      <div className="brand">
        <span className="logo" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
            <path d="M5 7l5 5-5 5" />
            <path d="M13 18h6" />
          </svg>
        </span>
        <div className="brand-text">
          <h1>MySQL Query Debugger</h1>
          <Typewriter as="p" phrases={TAGLINES} />
        </div>
      </div>
      <ThemeToggle />
    </header>
  );
}
