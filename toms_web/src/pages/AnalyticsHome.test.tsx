import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import AnalyticsHome from './AnalyticsHome';

afterEach(() => vi.restoreAllMocks());

const SHIFT_ID = '11111111-1111-4111-8111-111111111111';
const TRIP_A = '33333333-3333-4333-8333-333333333331';
const TRIP_B = '33333333-3333-4333-8333-333333333332';

function unaccountedShift(unknownCents: number, trips: string[]) {
  return {
    shift_id: SHIFT_ID,
    conductor: 'juan',
    vehicle_id: 'v1',
    route_id: 'r1',
    started_at: '2026-11-09T01:00:00.000Z',
    ended_at: '2026-11-09T05:00:00.000Z',
    ended_by: 'juan',
    unknown_trips: trips.length,
    unknown_cents: unknownCents,
    trips: trips.map((tripId, i) => ({
      trip_id: tripId,
      fare_cents: Math.round(unknownCents / trips.length),
      nfc_uid: `6FF1AD0${i + 1}`,
      card_state: 'LOST',
      occurred_at: '2026-11-09T02:00:00.000Z',
    })),
  };
}

function mockApi(unaccounted: unknown) {
  vi.spyOn(api, 'get').mockImplementation((url: string) => {
    if (url === '/shifts/unaccounted') return Promise.resolve({ data: unaccounted });
    if (url.startsWith('/analytics/revenue')) return Promise.resolve({ data: [] });
    return Promise.reject({ response: { status: 404 } });
  });
}

describe('AnalyticsHome unaccounted fares', () => {
  it('warns the unaccounted amount per shift and links each trip to the audit log', async () => {
    mockApi([unaccountedShift(150_000, [TRIP_A, TRIP_B])]);
    render(<MemoryRouter><AnalyticsHome /></MemoryRouter>);

    expect(await screen.findByText('Unaccounted fares')).toBeInTheDocument();
    expect(screen.getByText('₱1,500.00')).toBeInTheDocument();
    expect(screen.getByText('2 trips')).toBeInTheDocument();

    // Opening the shift reveals the trips behind the amount, each linked to a filtered audit log.
    fireEvent.click(screen.getByText('2 trips'));
    expect(screen.getAllByText('audit →')).toHaveLength(2);
    const firstLink = screen.getAllByText('audit →')[0].closest('a');
    expect(firstLink?.getAttribute('href')).toBe(`/audit?search=${TRIP_A}`);
  });

  it('stays quiet when every fare is accounted for', async () => {
    mockApi([]);
    render(<MemoryRouter><AnalyticsHome /></MemoryRouter>);
    await screen.findByText('Revenue & Ridership Analytics');
    expect(screen.queryByText('Unaccounted fares')).not.toBeInTheDocument();
  });
});
