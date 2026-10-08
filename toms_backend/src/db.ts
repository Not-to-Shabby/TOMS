import fs from 'fs';
import path from 'path';
import { Pool, types } from 'pg';

// numeric and bigint arrive as strings by default. Money columns are numeric(10,2) and counts
// are bigint; both stay well inside the safe integer range for this system.
types.setTypeParser(1700, (v) => Number(v));
types.setTypeParser(20, (v) => Number(v));

export function createPool(connectionString: string): Pool {
  return new Pool({ connectionString });
}

const MIGRATION_LOCK_ID = 727274;

/**
 * Applies pending SQL files from /migrations in name order, each in its own transaction.
 * An advisory lock makes concurrent starts (two containers) safe. Applied files are never re-run.
 */
export async function migrate(
  pool: Pool,
  dir: string = path.join(__dirname, '..', 'migrations'),
): Promise<string[]> {
  const files = fs
    .readdirSync(dir)
    .filter((f) => /^\d+_.+\.sql$/.test(f))
    .sort();

  const client = await pool.connect();
  try {
    await client.query('SELECT pg_advisory_lock($1)', [MIGRATION_LOCK_ID]);
    await client.query(
      `CREATE TABLE IF NOT EXISTS schema_migrations (
         name text PRIMARY KEY,
         applied_at timestamptz NOT NULL DEFAULT now()
       )`,
    );
    const done = new Set<string>(
      (await client.query('SELECT name FROM schema_migrations')).rows.map((r) => r.name as string),
    );

    const applied: string[] = [];
    for (const file of files) {
      if (done.has(file)) continue;
      const sql = fs.readFileSync(path.join(dir, file), 'utf8');
      try {
        await client.query('BEGIN');
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (name) VALUES ($1)', [file]);
        await client.query('COMMIT');
      } catch (err) {
        await client.query('ROLLBACK');
        throw new Error(`Migration ${file} failed: ${(err as Error).message}`);
      }
      applied.push(file);
    }
    return applied;
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [MIGRATION_LOCK_ID]).catch(() => undefined);
    client.release();
  }
}
