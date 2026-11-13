// Puts the code of the examples and the stored images of the tests into the pages, as
// scripts/sources.mjs finds them, both in the HTML and in the Markdown for agents.
import fs from 'node:fs';
import { replaceSource } from 'fumadocs-core/mdx-plugins/stringifier';
import { visit } from 'unist-util-visit';
import { MANIFEST, cleanMeta, resolveFence, shotKey } from '../scripts/sources.mjs';

function literalAttributes(node) {
  const out = {};
  for (const a of node.attributes) {
    if (a.type === 'mdxJsxAttribute' && typeof a.value === 'string') out[a.name] = a.value;
  }
  return out;
}

export function remarkDocs() {
  return (tree, file) => {
    const manifest = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
    visit(tree, (node) => {
      if (node.type === 'code') {
        const where = `${file.path}:${node.position?.start.line}`;
        const resolved = resolveFence(node.meta, where);
        if (!resolved) return;
        node.value = resolved.code;
        node.meta = cleanMeta(node.meta);
        replaceSource(file, node, `\`\`\`${node.lang ?? ''}\n${resolved.code}\n\`\`\``);
      } else if (node.type === 'mdxJsxFlowElement' && node.name === 'Shot') {
        const attrs = literalAttributes(node);
        const entry = manifest[shotKey(attrs)];
        if (!entry) throw new Error(`${file.path}: <Shot> not prepared; run npm run docs:prepare`);
        const { width, height } = manifest[entry.dark];
        const keep = node.attributes.filter((a) => !['src', 'dark', 'light', 'rows'].includes(a.name));
        const set = { dark: `/shots/${entry.dark}`, light: `/shots/${entry.light}`, width: String(width), height: String(height) };
        node.attributes = [
          ...keep,
          ...Object.entries(set).map(([name, value]) => ({ type: 'mdxJsxAttribute', name, value })),
        ];
      }
    });
  };
}
