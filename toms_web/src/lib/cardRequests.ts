import { api } from './api';

/** The request for a status change. */
export function changeStatus(cardUuid: string, status: string, reason: string, confirm: boolean) {
  return api.post(`/cards/${cardUuid}/status`, { status, reason: reason || undefined, confirm: confirm || undefined });
}
