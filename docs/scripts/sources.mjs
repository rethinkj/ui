// What the pages take from the rest of the repository: code snippets between markers in the
// documentation's examples, excerpts of other files (element trees, scripts) and the stored images
// of the tests. Shared by scripts/docs.mjs, which checks and copies before a build, and by the
// remark plugin in lib/remark-docs.mjs, which puts them into the pages.
import fs from 'node:fs';
import path from 'node:path';

// The docs folder, where npm runs; not import.meta.url, since Fumadocs bundles this file elsewhere.
export const DOCS = process.cwd();
export const ROOT = path.resolve(DOCS, '..');
export const SITE = 'https://ui.rethinkj.com';
export const REPOSITORY = 'https://github.com/rethinkj/ui';

/** The folders whose files may hold snippets, relative to the repository's root. */
export const SNIPPET_ROOTS = [
  'examples/src/test/java/com/rethinkj/ui/examples/docs',
  'examples/src/test/resources/docs',
];

const START = /^\s*(?:\/\/|#)\s*docs:start\s+([\w-]+)\s*$/;
const END = /^\s*(?:\/\/|#)\s*docs:end\s+([\w-]+)\s*$/;
const MARKER = /^\s*(?:\/\/|#)\s*docs:(?:start|end)\s/;

export class DocsError extends Error {}

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
}

/** Removes the indentation that all non-empty lines share. */
function dedent(lines) {
  const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length);
  const cut = indents.length ? Math.min(...indents) : 0;
  return lines.map((l) => l.slice(cut)).join('\n').replace(/\s+$/, '');
}

let snippetCache;

/**
 * Every snippet, by name: the lines between `// docs:start name` and `// docs:end name` (or `#`
 * in scripts) with the markers of other snippets inside left out. Fails on a name used twice or a
 * marker without its partner.
 */
export function snippets() {
  if (snippetCache) return snippetCache;
  const found = new Map();
  for (const root of SNIPPET_ROOTS) {
    for (const file of walk(path.join(ROOT, root))) {
      const lines = fs.readFileSync(file, 'utf8').split('\n');
      const open = new Map();
      const rel = path.relative(ROOT, file);
      lines.forEach((line, i) => {
        let m;
        if ((m = line.match(START))) {
          if (found.has(m[1]) || open.has(m[1])) {
            throw new DocsError(`Snippet "${m[1]}" is defined twice (${rel}:${i + 1})`);
          }
          open.set(m[1], i + 1);
        } else if ((m = line.match(END))) {
          if (!open.has(m[1])) throw new DocsError(`docs:end ${m[1]} without a start (${rel}:${i + 1})`);
          const from = open.get(m[1]);
          open.delete(m[1]);
          const body = lines.slice(from, i).filter((l) => !MARKER.test(l));
          found.set(m[1], { code: dedent(body), file: rel, line: from + 1 });
        }
      });
      for (const [name, line] of open) {
        throw new DocsError(`docs:start ${name} has no docs:end (${rel}:${line})`);
      }
    }
  }
  snippetCache = found;
  return found;
}

/** The attributes of a code fence's meta or a JSX tag: `name="value"` pairs. */
export function attributes(text) {
  const out = {};
  for (const m of (text ?? '').matchAll(/([\w-]+)="((?:[^"\\]|\\.)*)"/g)) {
    out[m[1]] = m[2].replace(/\\(.)/g, '$1');
  }
  return out;
}

/**
 * The code a fence stands for, from its meta: `snippet="name"`, or `file="path"` relative to the
 * repository with optionally `start="text"` (from the first line containing it) and `count="n"`
 * lines or `end="text"` (up to the first line after it that contains it). Null when the fence names
 * neither, so that it keeps what it holds.
 */
export function resolveFence(meta, where) {
  const a = attributes(meta);
  if (a.snippet) {
    const s = snippets().get(a.snippet);
    if (!s) {
      throw new DocsError(`${where}: no snippet "${a.snippet}"; mark it with // docs:start ${a.snippet} and // docs:end ${a.snippet} in ${SNIPPET_ROOTS[0]}`);
    }
    return { code: s.code, source: s.file };
  }
  if (a.file) {
    const file = path.join(ROOT, a.file);
    if (!fs.existsSync(file)) throw new DocsError(`${where}: no file ${a.file}`);
    let lines = fs.readFileSync(file, 'utf8').replace(/\s+$/, '').split('\n');
    if (a.start) {
      const i = lines.findIndex((l) => l.includes(a.start));
      if (i < 0) throw new DocsError(`${where}: no line with "${a.start}" in ${a.file}`);
      lines = lines.slice(i);
    }
    if (a.end) {
      const i = lines.findIndex((l, n) => n > 0 && l.includes(a.end));
      if (i < 0) throw new DocsError(`${where}: no line with "${a.end}" in ${a.file}`);
      lines = lines.slice(0, i + 1);
    }
    if (a.count) lines = lines.slice(0, Number(a.count));
    return { code: dedent(lines), source: a.file };
  }
  return null;
}

/** Strips the attributes the plugin reads from a fence's meta, keeping the others (`title`). */
export function cleanMeta(meta) {
  return (meta ?? '').replace(/\b(snippet|file|start|end|count)="(?:[^"\\]|\\.)*"/g, '').trim() || null;
}

/**
 * The stored image a name stands for: `kit/tree-dark-open` is
 * `kit/src/test/resources/golden/tree-dark-open.png`.
 */
export function goldenPath(name) {
  const [module, ...rest] = name.split('/');
  return path.join(ROOT, module, 'src/test/resources/golden', rest.join('/') + '.png');
}

/**
 * The images a `<Shot>` shows: `src` (with `{theme}` for `dark` and `light`), or `dark` and
 * `light`, and `rows` for a part of the kit's gallery. Each is `{ name, rows, out }`, where `out`
 * is its path under public/shots.
 */
export function shotImages(attrs, where) {
  const pick = (theme) => {
    const name = attrs[theme] ?? attrs.src?.replaceAll('{theme}', theme);
    if (!name) throw new DocsError(`${where}: a <Shot> needs src, or dark and light`);
    const rows = attrs.rows ? attrs.rows.split(',').map((r) => r.trim()) : null;
    const out = rows ? `${name}--${rows.map((r) => r.replace(/\W+/g, '-')).join('_')}.png` : `${name}.png`;
    return { name, rows, out };
  };
  return { dark: pick('dark'), light: pick('light') };
}

/**
 * The physical pixels per logical pixel of a stored image: its width over the width of the window
 * in its tree, or from its name (`-2x`) where it has no tree of its own, as the gallery.
 */
export function scaleOf(name, pixelWidth) {
  const tree = goldenPath(name).replace(/\.png$/, '.tree.txt');
  if (pixelWidth && fs.existsSync(tree)) {
    const m = fs.readFileSync(tree, 'utf8').match(/^(?:el|text|image)\b[^\n]*? 0,0 ([\d.]+)x/);
    if (m) return pixelWidth / Number(m[1]);
  }
  const m = name.match(/(?:^|[-/])(\d(?:[._]\d+)?)x(?:-|$)/);
  return m ? Number(m[1].replace('_', '.')) : 1;
}

export const MANIFEST = path.join(DOCS, '.generated/shots.json');

/** The key of a `<Shot>` in the manifest that scripts/docs.mjs writes. */
export function shotKey(attrs) {
  return JSON.stringify([attrs.src ?? null, attrs.dark ?? null, attrs.light ?? null, attrs.rows ?? null]);
}
