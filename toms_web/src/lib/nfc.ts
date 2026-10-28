/** Web NFC gives colon-separated serial numbers; the backend wants plain upper-case hex. */
export function normalizeUid(raw: string): string {
  return raw.replace(/[:\s-]/g, '').toUpperCase();
}

export function hasWebNfc(): boolean {
  return typeof window !== 'undefined' && 'NDEFReader' in window;
}

/** Waits for one card tap and resolves with its normalized UID. Rejects on abort or a read error. */
export function readNfcUid(signal: AbortSignal): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!hasWebNfc()) {
      reject(new Error('Web NFC is not available. Use Chrome on Android with NFC turned on.'));
      return;
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const reader = new (window as any).NDEFReader();
    reader.addEventListener('reading', (event: { serialNumber?: string }) => {
      if (event.serialNumber) resolve(normalizeUid(String(event.serialNumber)));
    });
    reader.addEventListener('readingerror', () => {
      reject(new Error('Could not read the card. Hold it steady against the back of the phone.'));
    });
    reader.scan({ signal }).catch((err: Error) => reject(err));
  });
}
