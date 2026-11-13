/**
 * A stored image of the tests, in the dark and the light theme: the page shows the one of its
 * theme. lib/remark-docs.mjs fills in `dark`, `light` and the size from what the page names.
 */
export function Shot({
  dark,
  light,
  width,
  height,
  alt,
  caption,
  frame = true,
}: {
  dark?: string;
  light?: string;
  width?: string;
  height?: string;
  alt: string;
  caption?: string;
  frame?: boolean;
}) {
  const style = { maxWidth: '100%', height: 'auto' };
  const img = (src: string | undefined, className: string) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt={alt} width={width} height={height} style={style} className={className} loading="lazy" />
  );
  return (
    <figure className={frame ? 'rj-shot rj-shot-frame' : 'rj-shot'}>
      {dark === light ? (
        img(dark, '')
      ) : (
        <>
          {img(light, 'dark:hidden')}
          {img(dark, 'hidden dark:block')}
        </>
      )}
      {caption ? <figcaption>{caption}</figcaption> : null}
    </figure>
  );
}
