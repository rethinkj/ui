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
          A native, GPU-rendered UI framework for Java. It draws everything itself, from text to the
          data grid, and it runs without a window: a script drives the app, and images and element
          trees come out, so that an agent or a test can see and use what it builds.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button href="/docs/get-started" primary>Get started</Button>
          <Button href="/docs/components">Components</Button>
          <Button href={repository}>GitHub</Button>
        </div>
        <p className="mt-6 text-sm text-fd-muted-foreground">
          Early: version 0.1.0-SNAPSHOT, Java 25, Windows 11 and Linux on x64. Not on Maven Central yet.
        </p>
      </section>

      <Shot src="examples/workbench-{theme}-2x" alt="The Workbench example: a schema tree, a query editor and a result grid in cards, with a menu bar in its own title bar" priority />

      <Reason title="It draws everything itself"
        aside={<Shot src="examples/manual-{theme}-2x-top" alt="A Markdown document in the Manual example" />}>
        <p>
          Layout is flexbox, text is shaped with HarfBuzz and rasterised with FreeType, and the
          scene goes to the GPU through wgpu in an SDL window. There is no toolkit underneath:
          the same pixels on Windows and Linux, and a CPU renderer that the GPU is checked
          against, pixel by pixel.
        </p>
        <p>
          Springs, shadows, rounded clips, lists of a million rows that build only the rows in
          view, editors for texts of 100 000 lines, overlays, several windows with drags between
          them.
        </p>
      </Reason>

      <Reason title="Agents and tests drive it without a window"
        aside={
          <div className="grid gap-3">
            <Code meta='snippet="lights-script"' lang="text" title="lights.txt" />
            <Code meta='file="examples/src/test/resources/golden/docs/lights/on.tree.txt"' lang="text" title="on.tree.txt" />
          </div>
        }>
        <p>
          The same app runs under a script: press by role and label, type, wait for work, move
          the clock. Each <code>snap</code> writes a PNG and the element tree as text, with what
          is checked, selected, focused, disabled or busy.
        </p>
        <p>
          So an agent that writes a view can look at it and read it, and a test is a script with
          stored images. Every page of this site is also Markdown, and{' '}
          <a href="/llms.txt" className="underline">llms.txt</a> lists them.
        </p>
        <p><Link href="/docs/testing" className="font-medium text-fd-primary">Testing and agents →</Link></p>
      </Reason>

      <Reason title="A kit for data tools"
        aside={<Shot src="examples/browser-light" dark="examples/browser-changes" alt="A million orders in the data grid with pending changes and filters" />}>
        <p>
          A data grid for a million rows with editing, filters, frozen columns and pending
          changes; a tree that loads lazily; tabs that tear off into windows; a code editor with
          highlighting, completion and find; a command palette; menus, dialogs and forms. In a
          dark and a light theme of calm, layered cards.
        </p>
        <p><Link href="/docs/components" className="font-medium text-fd-primary">All components →</Link></p>
      </Reason>

      <Reason title="Only java.base"
        aside={<Code meta='snippet="lights-app"' lang="java" title="Lights.java" />}>
        <p>
          <code>core</code> and <code>kit</code> depend on <code>java.base</code> alone: no AWT,
          no reflection, no classes made at run time. Native code goes through the Foreign
          Function and Memory API, and the native libraries come in one JAR per platform.
        </p>
        <p>
          An app is a state object and a function from that state to a tree of elements.
          Handlers change the state; the view is built again after each input.
        </p>
      </Reason>

      <section className="py-12 border-t">
        <h2 className="text-2xl font-semibold tracking-tight mb-3">Run it</h2>
        <p className="text-fd-muted-foreground mb-4">
          With JDK 25 and Maven 3.9 (the first build fetches and builds the native libraries):
        </p>
        <ServerCodeBlock lang="sh" code={`git clone ${repository}.git && cd ui
mvn -q -pl examples -am compile exec:exec -Dexample=Workbench`} />
        <div className="mt-8 grid gap-3 sm:grid-cols-3">
          {[
            ['Get started', '/docs/get-started', 'Build it, the first app line by line, and use it in a project.'],
            ['Components', '/docs/components', 'Every component of the kit, with its image, code and tree.'],
            ['Testing and agents', '/docs/testing', 'Scripts, selectors, tree dumps and stored images.'],
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
