import fs from 'fs';
import os from 'os';
import path from 'path';
import EmbeddedPostgres from 'embedded-postgres';

const PORT = 54330;

let server: EmbeddedPostgres | undefined;
let dir: string | undefined;

/**
 * Starts one throwaway PostgreSQL for the whole run. Set TEST_DATABASE_ADMIN_URL to use an
 * existing server instead (for example the Postgres service in CI).
 */
export async function setup() {
  if (process.env.TEST_DATABASE_ADMIN_URL) return;

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
