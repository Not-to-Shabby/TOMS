import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import FareSettings from './FareSettings';

afterEach(() => vi.restoreAllMocks());

/** Routes load first; the active-fare request for route 1 is answered by `activeFare`. */
function mockFareApi(activeFare: () => Promise<unknown>) {
  return vi.spyOn(api, 'get').mockImplementation((url: string) => {
    if (url === '/routes') return Promise.resolve({ data: [{ id: 1, name: 'Route A' }] });
    return activeFare();
  });
}

describe('FareSettings when the route changes', () => {
  it('clears the previous route fare, distance, discounts and rounding before showing a route with no matrix', async () => {
    const withMatrix = {
      id: 1,
      base_fare: 16,
      base_distance_km: 6,
      per_km_fare: 2.5,
      rounding_step_cents: 50,
      discounts: { student: 5, pwd: 5, senior: 5 },
      order_reference: 'Order 1',
      effective_date: '2026-10-01',
      document_url: null,
      is_active: true,
    };
    vi.spyOn(api, 'get').mockImplementation((url: string) => {
      if (url === '/routes') return Promise.resolve({ data: [{ id: 1, name: 'Route A' }, { id: 2, name: 'Route B' }] });
      if (url === '/fares/active/1') return Promise.resolve({ data: withMatrix });
      return Promise.reject({ response: { status: 404 } });
    });
    render(<FareSettings />);
    expect(await screen.findByDisplayValue('16.00')).toBeInTheDocument();

    // The first select on the page is the route picker; the rounding rule is the second.
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: '2' } });

    expect(await screen.findByText(/No fare matrix is published/)).toBeInTheDocument();
    expect(screen.queryByDisplayValue('16.00')).not.toBeInTheDocument();
    expect(screen.queryByText(/Verified under/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Base Fare/)).toHaveValue(null);
    expect(screen.getByLabelText(/Per-Km Surcharge/)).toHaveValue(null);
    expect(screen.getByLabelText(/Base Distance/)).toHaveValue(4);
    expect(screen.getByLabelText(/Rounding Rule/)).toHaveValue('100');
    expect(screen.queryByDisplayValue('5')).not.toBeInTheDocument();
  });
});

describe('FareSettings with no fare matrix on file', () => {
  it('warns that receipts show no rate when the server has no matrix for the route', async () => {
    mockFareApi(() => Promise.reject({ response: { status: 404 } }));
    render(<FareSettings />);
    expect(await screen.findByText(/No fare matrix is published/)).toBeInTheDocument();
  });

  it('does not warn while the fare is still loading', async () => {
    mockFareApi(() => new Promise(() => undefined));
    render(<FareSettings />);
    await screen.findByRole('option', { name: 'Route A' });
    expect(screen.queryByText(/No fare matrix is published/)).not.toBeInTheDocument();
  });

  it('does not claim there is no matrix when the request simply failed', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    mockFareApi(() => Promise.reject(new Error('Network Error')));
    render(<FareSettings />);
    await waitFor(() => expect(log).toHaveBeenCalled());
    expect(screen.queryByText(/No fare matrix is published/)).not.toBeInTheDocument();
  });
});
