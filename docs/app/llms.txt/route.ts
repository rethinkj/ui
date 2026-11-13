import { docsLlms, source } from '@/lib/source';

export const revalidate = false;

export async function GET() {
  const tree = source.getPageTree();
  const sections = await Promise.all(tree.children.map((node) => docsLlms.indexNode(node)));
  return new Response(`# rethinkj UI

> A native, GPU-rendered UI framework for Java 25 on Windows and Linux. It draws everything itself, and it runs without a window: a script drives the app, and PNG images and element trees come out, so that an agent can build a view and check it.

Every page below is Markdown: the links lead to it. The pages as HTML are at the same address without \`.md\`, and [/llms-full.txt](/llms-full.txt) is all pages in one file. To find a component in a script, read the "In a tree dump" section of its page.

${sections.join('\n')}
`);
}
