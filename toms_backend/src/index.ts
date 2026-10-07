import dotenv from 'dotenv';
import { assertJwtSecret } from './auth';
import { assertTimezone } from './dashboard';
import { createPool, migrate } from './db';
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

  const pool = createPool(databaseUrl);
  const applied = await migrate(pool);
  if (applied.length > 0) console.log('Applied migrations:', applied.join(', '));
  const reportTimezone = await assertTimezone(pool, process.env.REPORT_TIMEZONE ?? 'Asia/Manila');

  const { server } = createServer({ pool, jwtSecret, corsOrigins, reportTimezone });
  const port = Number(process.env.PORT) || 3000;
  server.listen(port, () => console.log(`TOMS backend listening on port ${port}`));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
