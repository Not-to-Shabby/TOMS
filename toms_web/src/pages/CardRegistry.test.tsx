import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import CardRegistry from './CardRegistry';

afterEach(() => vi.restoreAllMocks());

const card = (n: number) => ({
  card_uuid: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
  nfc_uid: null,
  serial: `TOMS-${String(n).padStart(4, '0')}`,
  label: null,
  status: 'unpaired',
  enrolled_at: '2026-10-27T00:00:00Z',
  notes: null,
  vehicle_id: null,
  vehicle_plate: null,
});

const renderPage = () => render(<MemoryRouter><CardRegistry /></MemoryRouter>);

describe('CardRegistry loading', () => {
  it('fetches every page so a batch larger than one page is not cut off', async () => {
    const get = vi.spyOn(api, 'get').mockImplementation(async (url: string, config?: { params?: { page?: string } }) => {
      if (url === '/vehicles') return { data: [] };
      const page = Number(config?.params?.page ?? 1);
      const cards = page === 1 ? Array.from({ length: 200 }, (_, i) => card(i + 1)) : Array.from({ length: 50 }, (_, i) => card(201 + i));
      return { data: { cards, metadata: { total: 250, page, limit: 200, totalPages: 2 } } };
    });
    renderPage();
    expect(await screen.findByText('Spare Pool (250)')).toBeInTheDocument();
    expect(get.mock.calls.filter(([u]) => u === '/cards')).toHaveLength(2);
  });

  it('shows an error with a retry button when the card list cannot load', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/vehicles') return { data: [] };
      throw Object.assign(new Error('boom'), { response: { status: 500 } });
    });
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the card list');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
