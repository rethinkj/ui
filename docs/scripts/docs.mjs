// `node scripts/docs.mjs prepare` runs before `next build` and `next dev`: it checks that every
// snippet, excerpt and image the pages name exists, copies the images (and cuts rows out of the
// kit's gallery), the fonts and the logo into public/, and writes the changelog page.
// `node scripts/docs.mjs check` runs after the build: it puts each page's Markdown next to the
// page (`/docs/x/` has `/docs/x.md`) and fails on internal links that lead nowhere.
import fs from 'node:fs';
import path from 'node:path';
import * as png from './png.mjs';
import {
  DOCS, ROOT, DocsError, MANIFEST, attributes, goldenPath, resolveFence, scaleOf, shotImages,
  shotKey, snippets,
} from './sources.mjs';

const CONTENT = path.join(DOCS, 'content/docs');
const PUBLIC = path.join(DOCS, 'public');
const OUT = path.join(DOCS, 'out');

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
}

function copy(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

// --- prepare ---

/** The rows of the kit's gallery: name, top and bottom in physical pixels, from its tree. */
function galleryRows(name) {
  const tree = fs.readFileSync(goldenPath(name).replace(/\.png$/, '.tree.txt'), 'utf8').split('\n');
  const scale = scaleOf(name);
  const cell = Math.round(40 * scale);
  // The first two rows, the states' names and the label's styles, are not in the tree.
  const rows = [{ name: 'header', top: 0, bottom: cell }, { name: 'label', top: cell, bottom: 2 * cell }];
  let top = 2 * cell;
  for (let i = 0; i < tree.length; i++) {
    // A row begins with its first cell, `# <name>, rest` or `# <name>, 1`. A name that comes
    // again (`toggle on`, the switch's and the toggle button's) is `<name> 2` the second time.
    const m = tree[i].match(/^# (.+), (rest|1)$/);
    if (!m) continue;
    const same = rows.filter((r) => r.base === m[1]).length;
    const height = Number(tree[i + 1].match(/^el 0,0 \d+x(\d+)/)[1]);
    const bottom = top + Math.round(height * scale);
    rows.push({ name: same ? `${m[1]} ${same + 1}` : m[1], base: m[1], top, bottom });
    top = bottom;
  }
  return rows;
}

function prepareShot(image, where, manifest) {
  const source = goldenPath(image.name);
  if (!fs.existsSync(source)) {
    throw new DocsError(`${where}: no stored image ${path.relative(ROOT, source)}`);
  }
  const target = path.join(PUBLIC, 'shots', image.out);
  if (image.rows) {
    const all = galleryRows(image.name);
    // The row of the states' names goes above rows that have states; the label's styles and the
    // forms of progress have none.
    const stateless = image.rows.every((r) => ['label', 'progress', 'running', 'labeled'].includes(r));
    const bands = [...(stateless ? [] : ['header']), ...image.rows].map((r) => {
      const row = all.find((x) => x.name === r);
      if (!row) {
        throw new DocsError(`${where}: no row "${r}" in ${image.name}; there are ${all.map((x) => x.name).join(', ')}`);
      }
      return [row.top, row.bottom];
    });
    const cut = png.stack(png.decode(fs.readFileSync(source)), bands);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, png.encode(cut));
  } else {
    copy(source, target);
  }
  const { width, height } = png.size(fs.readFileSync(target));
  const scale = image.rows ? scaleOf(image.name) : scaleOf(image.name, width);
  manifest[image.out] = { width: Math.round(width / scale), height: Math.round(height / scale) };
}

function changelog() {
  const dir = path.join(ROOT, 'changelog/unreleased');
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.md')).sort().reverse();
  const byDate = new Map();
  for (const f of files) {
    const date = f.slice(0, 10);
    if (!byDate.has(date)) byDate.set(date, []);
    byDate.get(date).push(fs.readFileSync(path.join(dir, f), 'utf8').trim());
  }
  let text = '---\ntitle: Changelog\ndescription: What changed since the last release, newest first.\n---\n\n'
    + 'Nothing is released yet: these are the changes on the way to the first release, newest first. '
    + 'Each comes from a file under [`changelog/unreleased`](https://github.com/rethinkj/ui/tree/main/changelog/unreleased) '
    + 'in the repository, which the release folds into `CHANGELOG.md`.\n';
  for (const [date, entries] of byDate) text += `\n## ${date}\n\n${entries.join('\n')}\n`;
  fs.writeFileSync(path.join(CONTENT, 'reference/changelog.md'), text);
}

function prepare() {
  const manifest = {};
  const problems = [];
  snippets();
  const pages = [...walk(CONTENT), ...walk(path.join(DOCS, 'app'))]
    .filter((f) => f.endsWith('.mdx') || f.endsWith('.tsx'));
  for (const file of pages) {
    const rel = path.relative(DOCS, file);
    const text = fs.readFileSync(file, 'utf8');
    const lines = text.split('\n');
    lines.forEach((line, i) => {
      const where = `${rel}:${i + 1}`;
      try {
        if (/^(title|description): [^"'].*: /.test(line)) {
          throw new DocsError(`${where}: quote the ${line.split(':')[0]}, which has ": " in it`);
        }
        const fence = line.match(/^\s*```(\w*)\s*(.*)$/);
        if (fence && fence[1] && file.endsWith('.mdx')) {
          const resolved = resolveFence(fence[2], where);
          if (!resolved && fence[1] === 'java') {
            throw new DocsError(`${where}: Java code comes from a snippet: write \`\`\`java snippet="name"\`\`\` and mark the code in examples' docs package`);
          }
        }
        for (const m of line.matchAll(/<(?:Shot|Tile)\b([^>]*?)\/?>/g)) {
          const attrs = attributes(m[1]);
          const images = shotImages(attrs, where);
          prepareShot(images.dark, where, manifest);
          if (images.light.out !== images.dark.out) prepareShot(images.light, where, manifest);
          manifest[shotKey(attrs)] = { dark: images.dark.out, light: images.light.out };
        }
      } catch (e) {
        if (!(e instanceof DocsError)) throw e;
        problems.push(e.message);
      }
    });
  }
  if (problems.length) {
    console.error(`docs: ${problems.length} problem(s):\n  ${problems.join('\n  ')}`);
    process.exit(1);
  }
  fs.mkdirSync(path.dirname(MANIFEST), { recursive: true });
  fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 1));

  const fonts = path.join(ROOT, 'fonts/src/main/resources/META-INF/rethinkj-ui/fonts');
  for (const f of ['Inter-Regular', 'Inter-Medium', 'Inter-SemiBold', 'Inter-Italic', 'JetBrainsMono-Regular']) {
    copy(path.join(fonts, `${f}.ttf`), path.join(PUBLIC, 'fonts', `${f}.ttf`));
  }
  copy(path.join(ROOT, 'assets/logo.svg'), path.join(PUBLIC, 'logo.svg'));
  changelog();
  const shots = Object.keys(manifest).filter((k) => k.endsWith('.png')).length;
  console.log(`docs: ${snippets().size} snippets, ${shots} images`);
}

// --- check ---

/** The file under out/ that serves a site path, or null. */
function served(urlPath) {
  const p = decodeURIComponent(urlPath);
  const candidates = p.endsWith('/')
    ? [path.join(OUT, p, 'index.html')]
    : [path.join(OUT, p), path.join(OUT, p, 'index.html'), path.join(OUT, p + '.html')];
  return candidates.find((c) => fs.existsSync(c) && fs.statSync(c).isFile()) ?? null;
}

function check() {
  // Each page's Markdown beside the page: /docs/components/button/ has /docs/components/button.md.
  const markdown = path.join(OUT, 'llms.mdx/docs');
  let pages = 0;
  for (const file of walk(markdown).filter((f) => f.endsWith('content.md'))) {
    const slug = path.relative(markdown, path.dirname(file));
    copy(file, path.join(OUT, 'docs', slug ? `${slug}.md` : 'index.md'));
    pages++;
  }

  const ids = new Map();
  const idsOf = (file) => {
    if (!ids.has(file)) {
      ids.set(file, new Set([...fs.readFileSync(file, 'utf8').matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
    }
    return ids.get(file);
  };
  const dead = [];
  const html = walk(OUT).filter((f) => f.endsWith('.html'));
  for (const file of html) {
    const text = fs.readFileSync(file, 'utf8');
    const page = '/' + path.relative(OUT, file).replace(/index\.html$/, '').replace(/\.html$/, '');
    for (const m of text.matchAll(/\s(?:href|src)="([^"]*)"/g)) {
      const link = m[1].replaceAll('&amp;', '&');
      if (!link.startsWith('/') && !link.startsWith('#')) continue;
      if (link.startsWith('//') || link.startsWith('/_next/')) continue;
      const [target, hash] = link.split('#');
      const pathOnly = target.split('?')[0];
      const servedFile = pathOnly ? served(pathOnly) : file;
      if (!servedFile) {
        dead.push(`${page}: ${link}`);
        continue;
      }
      if (hash && servedFile.endsWith('.html') && !idsOf(servedFile).has(decodeURIComponent(hash))) {
        dead.push(`${page}: ${link} (no #${hash} there)`);
      }
    }
  }
  for (const txt of ['llms.txt', 'llms-full.txt']) {
    const text = fs.readFileSync(path.join(OUT, txt), 'utf8');
    for (const m of text.matchAll(/\]\((\/[^)\s#]*)/g)) {
      if (!served(m[1])) dead.push(`/${txt}: ${m[1]}`);
    }
  }
  if (dead.length) {
    console.error(`docs: ${dead.length} internal link(s) lead nowhere:\n  ${[...new Set(dead)].join('\n  ')}`);
    process.exit(1);
  }
  console.log(`docs: ${pages} pages as Markdown, links of ${html.length} HTML files checked`);
}

const command = process.argv[2];
try {
  if (command === 'prepare') prepare();
  else if (command === 'check') check();
  else throw new Error('usage: node scripts/docs.mjs prepare|check');
} catch (e) {
  if (!(e instanceof DocsError)) throw e;
  console.error(`docs: ${e.message}`);
  process.exit(1);
}
