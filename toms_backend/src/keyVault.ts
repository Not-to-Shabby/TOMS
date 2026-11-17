import crypto from 'crypto';

/** The size of a Return Machine signing key: HMAC-SHA256 keyed with 32 random bytes (firmware TOMS_SIG_KEY_LEN). */
export const RM_KEY_LEN = 32;

const IV_LEN = 12;
const TAG_LEN = 16;

/**
 * AES-256-GCM sealing for the Return Machine keys, under a master key the operator provides in the
 * environment. The database holds only sealed blobs: `iv (12) || ciphertext (32) || tag (16)`, 60 bytes.
 * A stolen database dump therefore does not contain a usable machine key; the master key lives in the
 * server's environment, never in the database, the repo or any log.
 *
 * The record id is bound in as additional authenticated data, so a sealed key copied from one machine's
 * row to another's does not open — an attacker with write access to the table cannot make machine B
 * hand out machine A's key.
 */
export class KeyVault {
  private readonly master: Buffer;

  constructor(masterKeyHex: string) {
    this.master = parseMasterKey(masterKeyHex);
  }

  /** A new random machine key from the OS CSPRNG. */
  newKey(): Buffer {
    return crypto.randomBytes(RM_KEY_LEN);
  }

  seal(key: Buffer, recordId: string): Buffer {
    if (key.length !== RM_KEY_LEN) throw new Error('A Return Machine key must be 32 bytes');
    const iv = crypto.randomBytes(IV_LEN);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.master, iv);
    cipher.setAAD(Buffer.from(recordId, 'utf8'));
    const ciphertext = Buffer.concat([cipher.update(key), cipher.final()]);
    return Buffer.concat([iv, ciphertext, cipher.getAuthTag()]);
  }

  /** The key, or null when the blob was tampered with, belongs to another record, or the master key changed. */
  open(blob: Buffer, recordId: string): Buffer | null {
    if (blob.length !== IV_LEN + RM_KEY_LEN + TAG_LEN) return null;
    try {
      const iv = blob.subarray(0, IV_LEN);
      const tag = blob.subarray(blob.length - TAG_LEN);
      const ciphertext = blob.subarray(IV_LEN, blob.length - TAG_LEN);
      const decipher = crypto.createDecipheriv('aes-256-gcm', this.master, iv);
      decipher.setAAD(Buffer.from(recordId, 'utf8'));
      decipher.setAuthTag(tag);
      return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    } catch {
      return null;
    }
  }
}

/** A master key is 64 hex characters (32 bytes), generated with `openssl rand -hex 32`. */
export function parseMasterKey(hex: string | undefined): Buffer {
  const clean = (hex ?? '').trim();
  if (!/^[0-9a-fA-F]{64}$/.test(clean) || /^(0{64}|f{64})$/i.test(clean)) {
    throw new Error('RM_KEY_MASTER_KEY must be 64 hex characters (32 bytes). Generate one with: openssl rand -hex 32');
  }
  return Buffer.from(clean, 'hex');
}

/**
 * The vault from the environment, or undefined when RM_KEY_MASTER_KEY is not set. Without it the
 * Return Machine routes answer 503: no key is ever stored or served unencrypted, and a half-configured
 * server fails loudly instead of quietly holding plaintext keys.
 */
export function vaultFromEnv(env: NodeJS.ProcessEnv = process.env): KeyVault | undefined {
  const raw = env.RM_KEY_MASTER_KEY;
  if (raw === undefined || raw.trim() === '') return undefined;
  return new KeyVault(raw);
}
