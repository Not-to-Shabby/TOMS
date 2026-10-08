import argon2 from 'argon2';
import type { Pool } from 'pg';

export const MIN_ADMIN_PASSWORD = 12;

export async function createAdmin(pool: Pool, username: string, password: string): Promise<number> {
  if (!/^[a-zA-Z0-9._-]{3,64}$/.test(username)) {
    throw new Error('Username must be 3 to 64 characters: letters, digits, dot, dash or underscore');
  }
  if (password.length < MIN_ADMIN_PASSWORD || password.length > 200) {
    throw new Error(`Admin password must be ${MIN_ADMIN_PASSWORD} to 200 characters`);
  }
  const hash = await argon2.hash(password, { type: argon2.argon2id });
  try {
    const { rows } = await pool.query('INSERT INTO admins (username, password_hash) VALUES ($1, $2) RETURNING id', [
      username,
      hash,
    ]);
    return rows[0].id as number;
  } catch (err) {
    if ((err as { code?: string }).code === '23505') throw new Error('An admin with that username already exists');
    throw err;
  }
}
