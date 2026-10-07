import dotenv from 'dotenv';
import http from 'http';
import { createApp } from './app';
import { assertJwtSecret, makeAuth } from './auth';
import { assertTimezone } from './dashboard';
import { createPool, migrate } from './db';
import { attachSocket } from './socket';

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

  const server = http.createServer();
  const io = attachSocket(server, makeAuth({ pool, jwtSecret }), corsOrigins);
  const app = createApp({ pool, jwtSecret, corsOrigins, reportTimezone, emit: (event, payload) => io.emit(event, payload) });
  server.on('request', app);

  const port = Number(process.env.PORT) || 3000;
  server.listen(port, () => console.log(`TOMS backend listening on port ${port}`));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
