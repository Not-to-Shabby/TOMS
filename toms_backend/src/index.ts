import dotenv from 'dotenv';
import http from 'http';
import { Server } from 'socket.io';
import { createApp } from './app';
import { createPool, migrate } from './db';

dotenv.config();

async function main() {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error('DATABASE_URL is not set. Copy .env.example to .env and fill it in.');
  }

  const pool = createPool(databaseUrl);
  const applied = await migrate(pool);
  if (applied.length > 0) console.log('Applied migrations:', applied.join(', '));

  const server = http.createServer();
  const io = new Server(server, { cors: { origin: '*' } });
  const app = createApp({ pool, emit: (event, payload) => io.emit(event, payload) });
  server.on('request', app);

  io.on('connection', (socket) => {
    console.log('Dashboard client connected:', socket.id);
    socket.on('disconnect', () => console.log('Dashboard client disconnected:', socket.id));
  });

  const port = Number(process.env.PORT) || 3000;
  server.listen(port, () => console.log(`TOMS backend listening on port ${port}`));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
