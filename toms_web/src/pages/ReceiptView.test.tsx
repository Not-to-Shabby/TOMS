import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import axios from 'axios';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ReceiptView from './ReceiptView';

afterEach(() => vi.restoreAllMocks());

const sampleReceipt = {
  receipt_token: '11111111-2222-3333-4444-555555555555',
  trip_id: 'trip-123',
  status: 'PAID',
  card_state: 'RETURNED',
  boarding_stop: 'Buru-un Terminal',
  destination_stop: 'City Proper',
  declared_destination: 'City Proper',
  is_overridden: false,
  override_reason: null,
  fare_pesos: '36.00',
  discount_pesos: '0.00',
  passenger_count: 2,
  passengers: [
    { categoryId: null, count: 2, perPersonCentavos: 1800, fareCentavos: 3600, discountCentavos: 0 },
  ],
  vehicle: { id: 'BUS-101', plate: 'ABC-1234' },
  route_name: 'Buru-un to City Proper',
  conductor_name: 'John Doe',
  timestamp: '2026-10-08T08:30:00Z',
  ltfrb: {
    configured: true,
    order_reference: 'LTFRB Memorandum 2024-001',
    document_url: '/uploads/fares/sample-signed.png',
    base_fare: 15.0,
    base_distance_km: 4.0,
    per_km_fare: 2.5,
    discounts: { student: 20, pwd: 20, senior: 20 },
  },
};

describe('ReceiptView', () => {
  it('renders official trip details and passenger fare breakdown', async () => {
    vi.spyOn(axios, 'get').mockResolvedValue({ data: sampleReceipt });

    render(
      <MemoryRouter initialEntries={['/receipt/11111111-2222-3333-4444-555555555555']}>
        <Routes>
          <Route path="/receipt/:token" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('Official Passenger e-Receipt')).toBeInTheDocument();
    });

    expect(screen.getByText('PAID')).toBeInTheDocument();
    expect(screen.getByText('Buru-un Terminal')).toBeInTheDocument();
    expect(screen.getByText('City Proper')).toBeInTheDocument();
    expect(screen.getByText('ABC-1234')).toBeInTheDocument();
    expect(screen.getAllByText('₱36.00').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('2 people')).toBeInTheDocument();
  });

  it('expands official LTFRB verified fare matrix and shows signed document link', async () => {
    vi.spyOn(axios, 'get').mockResolvedValue({ data: sampleReceipt });

    render(
      <MemoryRouter initialEntries={['/receipt/11111111-2222-3333-4444-555555555555']}>
        <Routes>
          <Route path="/receipt/:token" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('LTFRB Verified Fare Matrix')).toBeInTheDocument();
    });

    // Expand accordion
    fireEvent.click(screen.getByText('LTFRB Verified Fare Matrix'));

    expect(screen.getByText(/LTFRB Memorandum 2024-001/)).toBeInTheDocument();
    expect(screen.getByText('Open full resolution certificate')).toBeInTheDocument();
    expect(screen.getByAltText('LTFRB Scanned Document')).toBeInTheDocument();
  });

  it('displays clear guidance when card has no active trip', async () => {
    vi.spyOn(axios, 'get').mockRejectedValue({ response: { status: 404 } });

    render(
      <MemoryRouter initialEntries={['/receipt/unknown-token']}>
        <Routes>
          <Route path="/receipt/:token" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText(/No active trip found for this card right now/)).toBeInTheDocument();
    });
  });
});

describe('ReceiptView card link', () => {
  it('asks the server for JSON so a card with no trip shows the inactive message, not a redirected page', async () => {
    const get = vi.spyOn(axios, 'get').mockRejectedValue({ response: { status: 404 } });
    render(
      <MemoryRouter initialEntries={['/r/11111111-2222-4333-8444-555555555555']}>
        <Routes>
          <Route path="/r/:cardUuid" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/No active trip found for this card/)).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith(expect.stringContaining('/api/r/'), { headers: { Accept: 'application/json' } });
  });
});

describe('ReceiptView with no fare matrix on file', () => {
  it('shows the passenger fare without a verified-rate section, and never prints a made-up rate', async () => {
    const noMatrix = { ...sampleReceipt, ltfrb: { configured: false } };
    vi.spyOn(axios, 'get').mockResolvedValue({ data: noMatrix });
    render(
      <MemoryRouter initialEntries={['/receipt/11111111-2222-3333-4444-555555555555']}>
        <Routes>
          <Route path="/receipt/:token" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText('Official Passenger e-Receipt')).toBeInTheDocument();
    expect(screen.getAllByText('₱36.00').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText('LTFRB Verified Fare Matrix')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Fare matrix not on file'));
    expect(screen.getByText(/does not show a verified rate/)).toBeInTheDocument();
    expect(screen.queryByText(/Base Fare/)).not.toBeInTheDocument();
  });

  it('explains a rate-limited lookup in plain words', async () => {
    vi.spyOn(axios, 'get').mockRejectedValue({ response: { status: 429 } });
    render(
      <MemoryRouter initialEntries={['/r/11111111-2222-4333-8444-555555555555']}>
        <Routes>
          <Route path="/r/:cardUuid" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/Too many lookups from this connection/)).toBeInTheDocument();
  });
});

describe('ReceiptView for a card that is out of service', () => {
  const at = (path: string) =>
    render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/receipt/inactive" element={<ReceiptView />} />
          <Route path="/r/:cardUuid" element={<ReceiptView />} />
        </Routes>
      </MemoryRouter>,
    );

  it('shows the lost-card message when the server redirected with state=lost', async () => {
    at('/receipt/inactive?card=abc&state=lost');
    expect(await screen.findByText(/reported lost/)).toBeInTheDocument();
  });

  it('shows the not-in-use message when the server answers 410', async () => {
    vi.spyOn(axios, 'get').mockRejectedValue({ response: { status: 410, data: { card_status: 'retired' } } });
    at('/r/11111111-2222-4333-8444-555555555555');
    expect(await screen.findByText(/has been retired/)).toBeInTheDocument();
  });

  it('falls back to a generic line for a 410 with no status', async () => {
    vi.spyOn(axios, 'get').mockRejectedValue({ response: { status: 410, data: {} } });
    at('/r/11111111-2222-4333-8444-555555555555');
    expect(await screen.findByText('This card is no longer in use.')).toBeInTheDocument();
  });
});
