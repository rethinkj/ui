import { llms, loader } from 'fumadocs-core/source';
import { docsRoute, markdownUrl } from './shared';
import { defineDocs } from 'fumadocs-mdx/macro';
import { applyMdxPreset } from 'fumadocs-mdx/config';
import { metaSchema, pageSchema } from 'fumadocs-core/source/schema';
import { remarkDocs } from './remark-docs.mjs';

const docs = defineDocs({
  dir: 'content/docs',
  docs: {
    schema: pageSchema,
    mdxOptions: applyMdxPreset({ remarkPlugins: [remarkDocs] }),
    postprocess: {
      includeProcessedMarkdown: true,
    },
  },
  meta: {
    schema: metaSchema,
  },
});

export const source = loader({
  baseUrl: docsRoute,
  source: docs.toFumadocsSource(),
});

export const docsLlms = llms(source, {
  // agents read the Markdown of each page, which lies beside the page
  renderUrl: (item) => {
    const url = item.url.replace(/\/$/, '');
    return url === docsRoute ? `${docsRoute}/index.md` : `${url}.md`;
  },
  renderPage: async (page) => `# ${page.data.title}

Source: https://ui.rethinkj.com${page.url} (Markdown: https://ui.rethinkj.com${markdownUrl(page)})

${page.data.description ?? ''}

${shotsAsImages(await page.data.getText('processed'))}`,
});

/** The Markdown of a `<Shot>`: its image in the dark theme. */
function shotsAsImages(markdown: string) {
  return markdown
    .replace(/<Tiles>|<\/Tiles>/g, '')
    .replace(/<Tile\b([^>]*?)\/>/g, (_, attrs: string) => {
      const get = (name: string) => attrs.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1] ?? '';
      return `- [${get('title')}](${get('href')}): ${get('description')}`;
    })
    .replace(/<Shot\b([^>]*?)\/>/g, (_, attrs: string) => {
      const alt = attrs.match(/\balt="([^"]*)"/)?.[1] ?? '';
      const src = attrs.match(/\bdark="([^"]*)"/)?.[1] ?? '';
      return `![${alt}](https://ui.rethinkj.com${src})`;
    });
}
