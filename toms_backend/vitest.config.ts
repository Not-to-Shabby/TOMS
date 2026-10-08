import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    globalSetup: ['test/globalSetup.ts'],
    testTimeout: 30_000,
    hookTimeout: 120_000,
    // One embedded Postgres serves every file; each file gets its own database.
    fileParallelism: false,
  },
});
