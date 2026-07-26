import { useEffect, useRef } from "react";

// Minimal shape of the Google Identity Services (GIS) global — just enough to initialize
// and render the button. See https://developers.google.com/identity/gsi/web/reference/js-reference
declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: {
            client_id: string;
            callback: (response: { credential: string }) => void;
          }) => void;
          renderButton: (parent: HTMLElement, options: Record<string, unknown>) => void;
        };
      };
    };
  }
}

// Loaded once and cached — every GoogleSignInButton instance (login/register tabs, any
// future page) reuses the same <script> tag and load promise rather than re-injecting it.
let scriptLoadPromise: Promise<void> | null = null;

function loadGoogleIdentityScript(): Promise<void> {
  if (window.google?.accounts?.id) return Promise.resolve();
  if (!scriptLoadPromise) {
    scriptLoadPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "https://accounts.google.com/gsi/client";
      script.async = true;
      script.defer = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error("Failed to load Google Identity Services"));
      document.head.appendChild(script);
    });
  }
  return scriptLoadPromise;
}

/**
 * Renders Google's own "Sign in with Google" button and hands the resulting ID token
 * (a signed JWT) up to the caller — verification happens server-side in
 * GoogleSignInVerifier, never in the browser. Silently renders nothing if
 * VITE_GOOGLE_CLIENT_ID isn't set (e.g. local dev without a configured OAuth client), or
 * if the Google script fails to load (offline, blocked) — email/password auth is always
 * still available either way.
 */
export function GoogleSignInButton({ onCredential }: { onCredential: (idToken: string) => void }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;

  useEffect(() => {
    if (!clientId || !containerRef.current) return;
    let cancelled = false;

    loadGoogleIdentityScript()
      .then(() => {
        if (cancelled || !containerRef.current || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => onCredential(response.credential),
        });
        window.google.accounts.id.renderButton(containerRef.current, {
          theme: "outline",
          size: "large",
          width: 372,
          text: "continue_with",
        });
      })
      .catch(() => {
        // Nothing to recover — the container just stays empty.
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientId]);

  if (!clientId) return null;

  return <div ref={containerRef} style={{ display: "flex", justifyContent: "center" }} />;
}
