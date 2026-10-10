// Share links: the SQL (and optional schema) travel in the URL *fragment* (#q=...). Fragments are never sent to a
// server, so a shared query is not logged anywhere by merely opening the link.

export const SHARE_PARAM = 'q';
export const MAX_SHARE_URL_LENGTH = 8000; // many browsers/chat apps truncate far beyond this

function toBase64Url(text) {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value) {
  const b64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function encodeShare({ sql, schema = '' }) {
  const payload = { v: 1, s: sql };
  if (schema && schema.trim()) payload.c = schema;
  return toBase64Url(JSON.stringify(payload));
}

/** @returns {{sql:string, schema:string}|null} null for anything that is not a valid share fragment */
export function decodeShare(hash) {
  try {
    const params = new URLSearchParams((hash || '').replace(/^#/, ''));
    const raw = params.get(SHARE_PARAM);
    if (!raw) return null;
    const payload = JSON.parse(fromBase64Url(raw));
    if (!payload || payload.v !== 1 || typeof payload.s !== 'string' || !payload.s.trim()) return null;
    return { sql: payload.s, schema: typeof payload.c === 'string' ? payload.c : '' };
  } catch {
    return null;
  }
}

/** @returns {{url:string, tooLong:boolean}} */
export function buildShareUrl({ origin, pathname }, data) {
  const url = `${origin}${pathname}#${SHARE_PARAM}=${encodeShare(data)}`;
  return { url, tooLong: url.length > MAX_SHARE_URL_LENGTH };
}
