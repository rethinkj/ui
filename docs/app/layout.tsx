import type { Metadata } from 'next';
import { Provider } from '@/components/provider';
import './global.css';

export const metadata: Metadata = {
  metadataBase: new URL('https://ui.rethinkj.com'),
  title: { default: 'rethinkj UI', template: '%s · rethinkj UI' },
  description:
    'A native, GPU-rendered UI framework for Java that draws everything itself and runs without a window for agents and tests.',
  icons: { icon: '/logo.svg' },
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="flex flex-col min-h-screen">
        <Provider>{children}</Provider>
      </body>
    </html>
  );
}
