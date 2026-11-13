import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
  // A static site for GitHub Pages: `next build` writes `out/`, which needs no server.
  output: 'export',
  // Folders with an index.html, which GitHub Pages serves without a redirect.
  trailingSlash: true,
  images: { unoptimized: true },
  reactStrictMode: true,
};

export default withMDX(config);
