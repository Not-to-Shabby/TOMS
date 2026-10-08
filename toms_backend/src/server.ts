import http from 'http';
import type { Server as SocketServer } from 'socket.io';
import { createApp, type AppDeps } from './app';
import { makeAuth } from './auth';
import { attachSocket } from './socket';

/**
 * The HTTP server with the REST API and the live socket. The Express app is given to
 * http.createServer first and socket.io attaches afterwards, so socket.io takes the /socket.io/
 * path and hands every other request to Express. Registering Express after socket.io would let both
 * answer the same request.
 */
export function createServer(deps: Omit<AppDeps, 'emit'>): { server: http.Server; io: SocketServer } {
  let io: SocketServer | undefined;
  const app = createApp({ ...deps, emit: (event, payload) => io?.emit(event, payload) });
  const server = http.createServer(app);
  io = attachSocket(server, makeAuth({ pool: deps.pool, jwtSecret: deps.jwtSecret }), deps.corsOrigins ?? []);
  return { server, io };
}
