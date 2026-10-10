/** First-screen gallery: one click runs the example, so a new visitor sees the tool working immediately. */
export default function ExampleCards({ samples, onPick, disabled }) {
  const cards = samples.filter((s) => s.card);
  return (
    <section className="examples" aria-labelledby="examples-title">
      <h2 id="examples-title">New here? Try an example</h2>
      <p className="hint">One click analyses it. Each one shows a different thing MySQL does with your query.</p>
      <ul className="example-grid">
        {cards.map((s) => (
          <li key={s.card.title}>
            <button
              type="button"
              className={`example-card tone-${s.card.tone}`}
              onClick={() => onPick(s)}
              disabled={disabled}
            >
              <span className="example-icon" aria-hidden="true">{s.card.icon}</span>
              <span className="example-title">{s.card.title}</span>
              <span className="example-blurb">{s.card.blurb}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
