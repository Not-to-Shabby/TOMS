import type http from 'http';
import { Server } from 'socket.io';
import type { Auth } from './auth';

/** Live dashboard updates. Only a signed-in admin may connect: the stream carries fleet positions. */
export function attachSocket(server: http.Server, auth: Auth, corsOrigins: string[]): Server {
  const io = new Server(server, { cors: { origin: corsOrigins.length ? corsOrigins : false } });

  io.use(async (socket, next) => {
    const token = (socket.handshake.auth as { token?: unknown } | undefined)?.token;
    const info = await auth.authenticate(typeof token === 'string' ? token : undefined).catch(() => null);
    if (info?.role === 'admin') return next();
    next(new Error('unauthorized'));
  });

  return io;
}
