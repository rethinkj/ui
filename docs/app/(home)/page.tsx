import Link from 'next/link';
import type { ReactNode } from 'react';
import { ServerCodeBlock } from 'fumadocs-ui/components/codeblock.rsc';
import { Shot } from '@/components/shot';
import { resolveFence } from '@/scripts/sources.mjs';
import { repository } from '@/lib/shared';

/** Code from a real file, as a page's fence names it: `snippet="…"` or `file="…"`. */
function code(meta: string) {
  return resolveFence(meta, 'app/(home)/page.tsx')!.code;
}

function Code({ meta, lang, title }: { meta: string; lang: string; title?: string }) {
  return <ServerCodeBlock code={code(meta)} lang={lang} codeblock={{ title }} />;
}

function Reason({ title, children, aside }: { title: string; children: ReactNode; aside: ReactNode }) {
  return (
    <section className="grid gap-8 py-12 border-t md:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] md:items-center">
      <div>
        <h2 className="text-2xl font-semibold tracking-tight mb-3">{title}</h2>
        <div className="text-fd-muted-foreground leading-relaxed space-y-3">{children}</div>
      </div>
      <div className="min-w-0">{aside}</div>
    </section>
  );
}

function Button({ href, children, primary }: { href: string; children: ReactNode; primary?: boolean }) {
  const look = primary
    ? 'bg-rj-accent text-white hover:opacity-90'
    : 'border bg-fd-card hover:bg-fd-accent';
  return (
    <Link href={href} className={`inline-flex h-10 items-center rounded-md px-4 text-sm font-medium transition-colors ${look}`}>
      {children}
    </Link>
  );
}

export default function HomePage() {
  return (
    <main className="mx-auto w-full max-w-[1120px] px-4 md:px-8">
      <section className="pt-16 pb-10 md:pt-24 text-center">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/logo.svg" alt="" width={56} height={56} className="mx-auto mb-6" />
        <h1 className="text-4xl md:text-5xl font-semibold tracking-tight">rethinkj UI</h1>
        <p className="mx-auto mt-5 max-w-[640px] text-lg text-fd-muted-foreground leading-relaxed">
          A native, GPU-rendered desktop UI framework for Java 25. It renders everything itself,
          from text to a data grid, and can also run without a window so tests and AI agents can
          drive your app with scripts and inspect screenshots and element trees.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button href="/docs/get-started" primary>Get started</Button>
          <Button href="/docs/components">Components</Button>
          <Button href={repository}>GitHub</Button>
        </div>
        <p className="mt-6 text-sm text-fd-muted-foreground">
          Early preview (0.1.0-SNAPSHOT) · Java 25 · Windows 11+ and Linux (x64)
        </p>
      </section>

      <Shot src="examples/workbench-{theme}-2x" alt="The Workbench example: a schema tree, a query editor and a result grid in cards, with a menu bar in its own title bar" priority />

      <Reason title="Fast, consistent native rendering"
        aside={<Shot src="examples/manual-{theme}-2x-top" alt="A Markdown document in the Manual example" />}>
        <p>
          rethinkj UI draws every pixel itself using flexbox layout, HarfBuzz text shaping,
          FreeType font rendering, and GPU rendering via wgpu inside an SDL window. Your app looks
          and behaves the same on Windows and Linux.
        </p>
        <p>
          Built-in support for spring animations, shadows, rounded clipping, virtual lists for a
          million rows, code editors for 100,000+ lines, overlays, and multi-window drag and drop.
        </p>
      </Reason>

      <Reason title="Scriptable headless mode for tests and agents"
        aside={
          <div className="grid gap-3">
            <Code meta='snippet="lights-script"' lang="text" title="lights.txt" />
            <Code meta='file="examples/src/test/resources/golden/docs/lights/on.tree.txt"' lang="text" title="on.tree.txt" />
          </div>
        }>
        <p>
          Run your app without opening a window. A simple text script can click buttons by role and
          label, type text, wait for background tasks, and advance animations. Each{' '}
          <code>snap</code> saves a PNG screenshot and a text dump of the element tree.
        </p>
        <p>
          This makes UI testing fast and reliable, and lets AI agents see and interact with the
          interface they build. Every docs page is also available as Markdown in{' '}
          <a href="/llms.txt" className="underline">llms.txt</a>.
        </p>
        <p><Link href="/docs/testing" className="font-medium text-fd-primary">Testing and agents →</Link></p>
      </Reason>

      <Reason title="Ready-made components for data tools"
        aside={<Shot src="examples/browser-light" dark="examples/browser-changes" alt="A million orders in the data grid with pending changes and filters" />}>
        <p>
          Includes a data grid for a million rows (with editing, filters, frozen columns, and
          pending changes), a lazy-loading tree, tear-off tabs, a code editor (with syntax
          highlighting, completion, and find), a command palette, menus, dialogs, and forms. Comes
          with dark and light themes out of the box.
        </p>
        <p><Link href="/docs/components" className="font-medium text-fd-primary">All components →</Link></p>
      </Reason>

      <Reason title="Minimal dependencies and simple architecture"
        aside={<Code meta='snippet="lights-app"' lang="java" title="Lights.java" />}>
        <p>
          <code>core</code> and <code>kit</code> depend only on <code>java.base</code> — no AWT,
          no Swing, and no reflection. Native libraries are packaged in one JAR per platform and
          called through Java&apos;s Foreign Function &amp; Memory API.
        </p>
        <p>
          An app is just a state object and a view function that returns a tree of elements. Event
          handlers update the state, and rethinkj UI rebuilds the view automatically.
        </p>
      </Reason>

      <section className="py-12 border-t">
        <h2 className="text-2xl font-semibold tracking-tight mb-3">Try the examples</h2>
        <p className="text-fd-muted-foreground mb-4">
          Clone the repository and run the Workbench demo with JDK 25, Maven 3.9+, and a C/C++ toolchain (see{' '}
          <Link href="/docs/get-started" className="underline">Installation</Link> to add rethinkj
          UI to your own project):
        </p>
        <ServerCodeBlock lang="sh" code={`git clone ${repository}.git && cd ui
mvn -q -pl examples -am compile exec:exec -Dexample=Workbench`} />
        <div className="mt-8 grid gap-3 sm:grid-cols-3">
          {[
            ['Get started', '/docs/get-started', 'Set up Maven or Gradle, build your first app step by step, and package it.'],
            ['Components', '/docs/components', 'Browse every UI component with screenshots, code examples, and options.'],
            ['Testing and agents', '/docs/testing', 'Drive your app with scripts, selectors, tree dumps, and image tests.'],
          ].map(([title, href, text]) => (
            <Link key={href} href={href} className="rounded-xl border bg-fd-card p-4 hover:bg-fd-accent transition-colors">
              <div className="font-medium">{title}</div>
              <div className="mt-1 text-sm text-fd-muted-foreground">{text}</div>
            </Link>
          ))}
        </div>
      </section>
    </main>
  );
}
