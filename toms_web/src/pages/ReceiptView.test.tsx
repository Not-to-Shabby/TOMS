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
    order_reference: 'LTFRB Memorandum 2024-001',
    document_url: '/uploads/fares/sample-signed.png',
    base_fare: 15.0,
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
