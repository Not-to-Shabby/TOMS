import dotenv from 'dotenv';
import { assertJwtSecret } from './auth';
import { assertTimezone } from './dashboard';
import { createPool, migrate } from './db';
import { vaultFromEnv } from './keyVault';
import { createServer } from './server';

dotenv.config();

async function main() {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error('DATABASE_URL is not set. Copy .env.example to .env and fill it in.');
  }
  const jwtSecret = assertJwtSecret(process.env.JWT_SECRET);
  const corsOrigins = (process.env.CORS_ORIGINS ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  // Proxies in front of this server that add to X-Forwarded-For. Production has Caddy and nginx: 2.
  const trustProxyHops = Number(process.env.TRUST_PROXY_HOPS || 0);
  if (!Number.isInteger(trustProxyHops) || trustProxyHops < 0 || trustProxyHops > 10) {
    throw new Error('TRUST_PROXY_HOPS must be a whole number from 0 to 10 (production behind Caddy and nginx: 2)');
  }

  const pool = createPool(databaseUrl);
  const applied = await migrate(pool);
  if (applied.length > 0) console.log('Applied migrations:', applied.join(', '));
  const reportTimezone = await assertTimezone(pool, process.env.REPORT_TIMEZONE ?? 'Asia/Manila');

  // Optional: without RM_KEY_MASTER_KEY the Return Machine key routes answer 503 and store nothing.
  // A set-but-invalid value throws here, so a typo cannot silently turn the feature off.
  const keyVault = vaultFromEnv();
  if (!keyVault) console.log('RM_KEY_MASTER_KEY is not set: Return Machine key issuing is disabled');

  const { server } = createServer({ pool, jwtSecret, corsOrigins, reportTimezone, trustProxyHops, keyVault });
  const port = Number(process.env.PORT) || 3000;
  server.listen(port, () => console.log(`TOMS backend listening on port ${port}`));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
