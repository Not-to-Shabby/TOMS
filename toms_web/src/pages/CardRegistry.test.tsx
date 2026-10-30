import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  it('shows how many printed cards are waiting and opens the pairing flow', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/vehicles') return { data: [] };
      return { data: { cards: [card(1), card(2), card(3)], metadata: { total: 3, page: 1, limit: 200, totalPages: 1 } } };
    });
    renderPage();
    const open = await screen.findByRole('button', { name: /Pair printed card \(3 waiting\)/ });
    await userEvent.click(open);
    expect(screen.getByRole('dialog', { name: 'Pair a printed card' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'TOMS-0002' })).toBeInTheDocument();
  });

  it('points people with printed cards away from the manual enroll form', async () => {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => (url === '/vehicles' ? { data: [] } : { data: { cards: [], metadata: { total: 0, page: 1, limit: 200, totalPages: 1 } } }));
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /Enroll New Card/ }));
    expect(screen.getByText(/Cards with a printed QR code\?/)).toBeInTheDocument();
    expect(screen.getByText('Pair printed card', { selector: 'strong' })).toBeInTheDocument();
  });
});

describe('CardRegistry row actions', () => {
  const active = { ...card(1), status: 'active', nfc_uid: '04A1B2C3', label: 'Seat 5', notes: 'old strap' };

  function load(rows: object[] = [active]) {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/vehicles') return { data: [] };
      if (url.endsWith('/history')) return { data: { events: [] } };
      return { data: { cards: rows, metadata: { total: rows.length, page: 1, limit: 200, totalPages: 1 } } };
    });
  }
  const openMenu = async () => userEvent.click(await screen.findByRole('button', { name: 'Actions for TOMS-0001' }));

  it('marks a card lost only after a reason is given, and sends it to the server', async () => {
    load();
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark lost' }));
    expect(post).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: 'Mark TOMS-0001 as lost?' })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Reason'), 'Left on the bus');
    await userEvent.click(screen.getByRole('button', { name: 'Mark lost' }));
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/cards/${active.card_uuid}/status`, { status: 'lost', reason: 'Left on the bus', confirm: undefined }));
  });

  it('reloads the list after a change', async () => {
    load();
    vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Suspend' }));
    const callsBefore = (api.get as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter(([u]) => u === '/cards').length;
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await waitFor(() => {
      const after = (api.get as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter(([u]) => u === '/cards').length;
      expect(after).toBeGreaterThan(callsBefore);
    });
  });

  it('activates a suspended card straight away with no confirmation', async () => {
    load([{ ...active, status: 'suspended' }]);
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Activate' }));
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/cards/${active.card_uuid}/status`, { status: 'active', reason: undefined, confirm: undefined }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows the server message when a quiet change is refused', async () => {
    load([{ ...active, status: 'lost' }]);
    vi.spyOn(api, 'post').mockRejectedValue({ response: { data: { error: 'A lost card cannot be changed to suspended.' } } });
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Found it' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A lost card cannot be changed to suspended.');
  });

  it('takes the chip off through the unpair endpoint after a confirmation', async () => {
    load();
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Take chip off' }));
    await userEvent.click(screen.getByRole('button', { name: 'Unpair' }));
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/cards/${active.card_uuid}/unpair`, { reason: undefined, confirm: undefined }));
  });

  it('opens the editor with the current label and saves a cleared note as null', async () => {
    load();
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ data: {} });
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit label and notes' }));
    expect(screen.getByLabelText('Label')).toHaveValue('Seat 5');
    await userEvent.clear(screen.getByLabelText('Notes'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(patch).toHaveBeenCalledWith(`/cards/${active.card_uuid}`, { label: 'Seat 5', notes: null }));
  });

  it('opens the history of the chosen card', async () => {
    load();
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'History' }));
    expect(await screen.findByRole('dialog', { name: 'History of TOMS-0001' })).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith(`/cards/${active.card_uuid}/history`);
  });

  it('no longer offers the old one-click Suspend button in the row', async () => {
    load();
    renderPage();
    await screen.findByRole('button', { name: 'Actions for TOMS-0001' });
    expect(screen.queryByRole('button', { name: 'Suspend' })).not.toBeInTheDocument();
  });

  it('opens the replace-chip dialog for a card that already has a chip', async () => {
    load();
    renderPage();
    await openMenu();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Replace chip' }));
    expect(screen.getByRole('dialog', { name: 'Replace chip on TOMS-0001' })).toBeInTheDocument();
    expect(screen.getByTestId('pair-heading')).toHaveTextContent('Replace the chip on TOMS-0001');
  });

  it('does not offer replace on a card with no chip yet', async () => {
    load([card(1)]);
    renderPage();
    await openMenu();
    expect(screen.queryByRole('menuitem', { name: 'Replace chip' })).not.toBeInTheDocument();
  });
});
