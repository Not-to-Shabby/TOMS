export function loadErrorMessage(err: unknown, what: string): string {
  const e = err as { response?: { status?: number; data?: { error?: string } } };
  if (e?.response?.status === 401) return `Your session ended. Sign in again to load ${what}.`;
  if (e?.response?.data?.error) return `Could not load ${what}: ${e.response.data.error}`;
  if (e?.response) return `Could not load ${what}. The server answered with an error (${e.response.status}).`;
  return `Could not load ${what}. Check that the server is reachable and try again.`;
}
