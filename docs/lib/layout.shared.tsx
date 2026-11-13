import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { appName, repository } from './shared';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: (
        <span className="inline-flex items-center gap-2 font-semibold">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/logo.svg" alt="" width={22} height={22} />
          {appName}
        </span>
      ),
    },
    githubUrl: repository,
    links: [
      { text: 'Get started', url: '/docs/get-started', active: 'nested-url' },
      { text: 'Components', url: '/docs/components', active: 'nested-url' },
      { text: 'Testing and agents', url: '/docs/testing', active: 'nested-url' },
    ],
  };
}
