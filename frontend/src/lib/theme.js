// Theme choice: 'system' (follow the OS), 'light' or 'dark'. Stored in localStorage; applied as
// <html data-theme="light|dark">. With 'system' the attribute is removed so the CSS media query decides.

export const THEME_KEY = 'mysql-debugger:theme';
export const THEMES = ['system', 'light', 'dark'];

export function getStoredTheme(storage) {
  try {
    const s = storage ?? globalThis.localStorage;
    const value = s?.getItem(THEME_KEY);
    return THEMES.includes(value) ? value : 'system';
  } catch {
    return 'system';
  }
}

export function applyTheme(mode, root = globalThis.document?.documentElement) {
  if (!root) return;
  if (mode === 'light' || mode === 'dark') root.setAttribute('data-theme', mode);
  else root.removeAttribute('data-theme');
}

export function storeTheme(mode, storage) {
  try {
    (storage ?? globalThis.localStorage)?.setItem(THEME_KEY, mode);
  } catch {
    /* ignore: the choice just won't persist */
  }
}
