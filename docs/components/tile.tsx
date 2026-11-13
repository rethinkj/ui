import Link from 'next/link';
import type { ReactNode } from 'react';

/** A grid of tiles. */
export function Tiles({ children }: { children: ReactNode }) {
  return <div className="not-prose grid gap-4 sm:grid-cols-2 lg:grid-cols-3 my-6">{children}</div>;
}

/** A link to a page with a picture from the tests; lib/remark-docs.mjs fills in the images. */
export function Tile({ href, title, description, dark, light }: {
  href: string;
  title: string;
  description: string;
  dark?: string;
  light?: string;
}) {
  const img = (src: string | undefined, className: string) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt="" loading="lazy"
      className={`h-full w-full object-cover object-left-top ${className}`} />
  );
  return (
    <Link href={href} className="group overflow-hidden rounded-xl border bg-fd-card transition-colors hover:border-fd-primary/60">
      <div className="h-36 overflow-hidden border-b bg-rj-window">
        {dark === light ? img(dark, '') : (
          <>
            {img(light, 'dark:hidden')}
            {img(dark, 'hidden dark:block')}
          </>
        )}
      </div>
      <div className="p-3">
        <div className="font-medium">{title}</div>
        <div className="mt-1 text-sm text-fd-muted-foreground">{description}</div>
      </div>
    </Link>
  );
}
