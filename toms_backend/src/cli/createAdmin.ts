import dotenv from 'dotenv';
import { createAdmin } from '../admins';
import { createPool, migrate } from '../db';

dotenv.config();

// Usage: ADMIN_PASSWORD='...' npm run create-admin -- <username>
// The password comes from the environment so it never appears in the command line or shell history.
async function main() {
  const username = process.argv[2];
  const password = process.env.ADMIN_PASSWORD;
  if (!username || !password) {
    console.error("Usage: ADMIN_PASSWORD='at least 12 characters' npm run create-admin -- <username>");
    process.exit(2);
  }
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is not set');

  const pool = createPool(process.env.DATABASE_URL);
  try {
    await migrate(pool);
    const id = await createAdmin(pool, username, password);
    console.log(`Created admin "${username}" (id ${id}).`);
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error((err as Error).message);
  process.exit(1);
});
