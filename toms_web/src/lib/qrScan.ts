import jsQR from 'jsqr';

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';

/**
 * Pulls the card id out of what a printed card QR contains (https://<domain>/r/<uuid>).
 * Accepts a bare UUID too, so a typed or pasted id works the same way.
 */
export function cardUuidFromQr(text: string): string | null {
  const trimmed = text.trim();
  const bare = new RegExp(`^(${UUID})$`, 'i').exec(trimmed);
  if (bare) return bare[1].toLowerCase();
  const inUrl = new RegExp(`/r/(${UUID})(?:[/?#]|$)`, 'i').exec(trimmed);
  return inUrl ? inUrl[1].toLowerCase() : null;
}

export function cameraAvailable(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
}

/** Decodes a QR code from camera pixels. Returns the text, or null when none is readable. */
export function decodeQr(image: ImageData): string | null {
  return jsQR(image.data, image.width, image.height, { inversionAttempts: 'dontInvert' })?.data ?? null;
}

/**
 * Opens the rear camera and calls onText with the first QR text it can read.
 * Returns a function that stops the camera.
 */
export async function startQrScan(
  video: HTMLVideoElement,
  onText: (text: string) => void,
  onError: (message: string) => void,
): Promise<() => void> {
  if (!cameraAvailable()) {
    onError('This browser cannot open the camera. Use HTTPS and allow camera access, or pick the card from the list.');
    return () => undefined;
  }
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
  } catch (err) {
    const name = (err as Error).name;
    onError(
      name === 'NotAllowedError'
        ? 'Camera access was blocked. Allow the camera for this site, or pick the card from the list.'
        : 'Could not open the camera. Pick the card from the list instead.',
    );
    return () => undefined;
  }

  video.srcObject = stream;
  video.setAttribute('playsinline', 'true');
  await video.play().catch(() => undefined);

  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  let stopped = false;
  let timer = 0;

  const tick = () => {
    if (stopped) return;
    if (ctx && video.readyState >= 2 && video.videoWidth > 0) {
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      ctx.drawImage(video, 0, 0);
      const text = decodeQr(ctx.getImageData(0, 0, canvas.width, canvas.height));
      if (text) {
        onText(text);
        return;
      }
    }
    timer = window.setTimeout(tick, 150);
  };
  tick();

  return () => {
    stopped = true;
    window.clearTimeout(timer);
    stream.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
  };
}
