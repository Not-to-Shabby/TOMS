import fs from 'fs';
import net from 'net';
import os from 'os';
import path from 'path';
import EmbeddedPostgres from 'embedded-postgres';

/**
 * Finds a TCP port that is currently free on loopback. A hardcoded port breaks whenever Windows
 * (Hyper-V/WSL/Docker) reserves the range it sits in: postgres then fails to bind with "Permission
 * denied" and the whole suite cannot start.
 */
function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as net.AddressInfo;
      server.close(() => resolve(port));
    });
  });
}

let server: EmbeddedPostgres | undefined;
let dir: string | undefined;

/**
 * Starts one throwaway PostgreSQL for the whole run. Set TEST_DATABASE_ADMIN_URL to use an
 * existing server instead (for example the Postgres service in CI).
 */
export async function setup() {
  if (process.env.TEST_DATABASE_ADMIN_URL) return;

  const PORT = await findFreePort();
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'toms-pg-'));
  server = new EmbeddedPostgres({
    databaseDir: dir,
    user: 'postgres',
    password: 'test',
    port: PORT,
    persistent: false,
    onLog: () => undefined,
    onError: () => undefined,
  });
  await server.initialise();
  await server.start();
  process.env.TEST_DATABASE_ADMIN_URL = `postgres://postgres:test@localhost:${PORT}/postgres`;
}

export async function teardown() {
  await server?.stop();
  if (dir) fs.rmSync(dir, { recursive: true, force: true });
}
