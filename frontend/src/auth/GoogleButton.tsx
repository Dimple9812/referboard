import { useEffect, useRef } from 'react';

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (o: { client_id: string; callback: (r: { credential: string }) => void }) => void;
          renderButton: (el: HTMLElement, o: Record<string, unknown>) => void;
        };
      };
    };
  }
}

const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;

/** Google Identity Services button; hidden unless VITE_GOOGLE_CLIENT_ID is set. Sends the ID token to the API. */
export function GoogleButton({ onCredential }: { onCredential: (idToken: string) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!CLIENT_ID || !ref.current) return;
    const render = () => {
      if (!window.google || !ref.current) return;
      window.google.accounts.id.initialize({ client_id: CLIENT_ID, callback: (r) => onCredential(r.credential) });
      window.google.accounts.id.renderButton(ref.current, { theme: 'outline', size: 'large', width: 320, text: 'continue_with' });
    };
    if (window.google) {
      render();
      return;
    }
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.onload = render;
    document.head.appendChild(script);
  }, [onCredential]);
  if (!CLIENT_ID) return null;
  return <div ref={ref} className="flex justify-center" />;
}
