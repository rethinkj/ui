import manifest from '@/.generated/shots.json';
import { shotKey } from '@/scripts/sources.mjs';

const shots = manifest as unknown as Record<string, { dark: string; light: string; width: number; height: number }>;

/**
 * A stored image of the tests, in the dark and the light theme: the page shows the one of its
 * theme. In MDX, lib/remark-docs.mjs fills in `dark`, `light` and the size from what the page
 * names; elsewhere `src` (and `rows`) are looked up as scripts/docs.mjs prepared them.
 */
export function Shot({
  src,
  rows,
  dark,
  light,
  width,
  height,
  alt,
  caption,
  frame = true,
  priority = false,
}: {
  src?: string;
  rows?: string;
  dark?: string;
  light?: string;
  width?: string | number;
  height?: string | number;
  alt: string;
  caption?: string;
  frame?: boolean;
  priority?: boolean;
}) {
  if (src) {
    const entry = shots[shotKey({ src, dark, light, rows })];
    if (!entry) throw new Error(`<Shot src="${src}"> not prepared; run npm run docs:prepare`);
    dark = `/shots/${entry.dark}`;
    light = `/shots/${entry.light}`;
    ({ width, height } = shots[entry.dark]);
  }
  const style = { maxWidth: '100%', height: 'auto' };
  const img = (url: string | undefined, className: string) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={url} alt={alt} width={width} height={height} style={style} className={className}
      loading={priority ? 'eager' : 'lazy'} />
  );
  return (
    <figure className={frame ? 'rj-shot rj-shot-frame' : 'rj-shot'}>
      {dark === light ? img(dark, '') : (
        <>
          {img(light, 'dark:hidden')}
          {img(dark, 'hidden dark:block')}
        </>
      )}
      {caption ? <figcaption>{caption}</figcaption> : null}
    </figure>
  );
}
