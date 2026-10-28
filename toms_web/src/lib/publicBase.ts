import { api } from './api';

/** The address printed into QR codes. The server setting wins so every admin prints the same one. */
export async function getPublicBase(): Promise<{ url: string; fromServer: boolean }> {
  try {
    const res = await api.get<{ public_base_url: string | null }>('/config');
    const configured = res.data.public_base_url;
    if (configured) return { url: configured.replace(/\/+$/, ''), fromServer: true };
  } catch {
    // Fall through to the address the admin has open.
  }
  return { url: window.location.origin, fromServer: false };
}
