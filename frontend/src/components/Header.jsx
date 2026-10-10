import ThemeToggle from './ThemeToggle.jsx';

export default function Header() {
  return (
    <header className="topbar">
      <div className="brand">
        <span className="logo" aria-hidden="true">🐬</span>
        <div>
          <h1>MySQL Query Debugger</h1>
          <p>See how MySQL runs your SQL, why it’s slow, and how to fix it.</p>
        </div>
      </div>
      <ThemeToggle />
    </header>
  );
}
