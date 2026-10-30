import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as apiModule from '../lib/api';
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

/** Stands in for the designer, so a test can see what the registry sent it. */
function DesignerStub() {
  const state = useLocation().state as { reprint?: { card_uuids?: string[] } } | null;
  return (
    <div>
      designer opened
      <div>{`reprint:${state?.reprint?.card_uuids?.join(',') ?? ''}`}</div>
    </div>
  );
}

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/cards']}>
      <Routes>
        <Route path="/cards" element={<CardRegistry />} />
        <Route path="/cards/designer" element={<DesignerStub />} />
      </Routes>
    </MemoryRouter>,
  );

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

describe('CardRegistry selection, bulk and export', () => {
  const act = (n: number, status = 'active') => ({ ...card(n), status, nfc_uid: `04AA00${String(n).padStart(2, '0')}` });
  const rows = [act(1), act(2), act(3)];

  function load(list: object[] = rows) {
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/vehicles') return { data: [{ id: 'BUS-1', plate_number: 'AAA-111', max_capacity: 20, status: 'ok' }] };
      return { data: { cards: list, metadata: { total: list.length, page: 1, limit: 200, totalPages: 1 } } };
    });
  }
  const tick = (serial: string) => userEvent.click(screen.getByRole('checkbox', { name: `Select ${serial}` }));
  /** The confirm button inside the bulk dialog, not the same-named button in the bar behind it. */
  const confirmInDialog = (name: string) => userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name }));

  it('shows no bulk bar until a card is ticked, then the count and what they are', async () => {
    load();
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    expect(screen.queryByRole('region', { name: 'Selected cards' })).not.toBeInTheDocument();
    await tick('TOMS-0001');
    await tick('TOMS-0002');
    expect(screen.getByText('2 selected')).toBeInTheDocument();
    expect(screen.getByText('(2 active)')).toBeInTheDocument();
  });

  it('offers only the changes every ticked card can make', async () => {
    load([act(1), act(2, 'lost'), act(3)]);
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await tick('TOMS-0001');
    expect(screen.getByRole('button', { name: 'Suspend' })).toBeInTheDocument();
    await tick('TOMS-0002'); // lost: can only be found again or retired
    expect(screen.queryByRole('button', { name: 'Suspend' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retire' })).toBeInTheDocument();
  });

  it('selects and clears a whole group with its header checkbox', async () => {
    load();
    renderPage();
    const all = await screen.findByRole('checkbox', { name: /Select all in/ });
    await userEvent.click(all);
    expect(screen.getByText('3 selected')).toBeInTheDocument();
    await userEvent.click(all);
    expect(screen.queryByText(/selected/)).not.toBeInTheDocument();
  });

  it('suspends the ticked cards in one request, then reloads and reports', async () => {
    load();
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { changed: 2 } });
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await tick('TOMS-0001');
    await tick('TOMS-0003');
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await confirmInDialog('Suspend');
    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(post).toHaveBeenCalledWith('/cards/bulk', expect.objectContaining({ action: 'status', status: 'suspended', card_uuids: [rows[0].card_uuid, rows[2].card_uuid] }));
    expect(await screen.findByText('Changed 2 cards.')).toBeInTheDocument();
    expect(screen.queryByText(/selected/)).not.toBeInTheDocument();
  });

  it('can switch to everything that matches the filter, and sends the filter, not the ids', async () => {
    load();
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { changed: 3 } });
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await tick('TOMS-0001');
    await userEvent.click(screen.getByRole('button', { name: 'Select all 3 that match' }));
    expect(screen.getByText('3 selected')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await confirmInDialog('Suspend');
    await waitFor(() => expect(post).toHaveBeenCalled());
    const sent = post.mock.calls[0][1] as Record<string, unknown>;
    expect(sent.filter).toEqual({});
    expect(sent.card_uuids).toBeUndefined();
  });

  it('unticking one card after "all that match" keeps the others selected', async () => {
    load();
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await tick('TOMS-0001');
    await userEvent.click(screen.getByRole('button', { name: 'Select all 3 that match' }));
    await tick('TOMS-0002');
    expect(screen.getByText('2 selected')).toBeInTheDocument();
  });

  it('hands the ticked cards to the designer when Reprint is chosen', async () => {
    load();
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await tick('TOMS-0002');
    await userEvent.click(screen.getByRole('button', { name: /Reprint/ }));
    expect(await screen.findByText('designer opened')).toBeInTheDocument();
    expect(await screen.findByText(`reprint:${rows[1].card_uuid}`)).toBeInTheDocument();
  });

  it('downloads the list with the filters currently set', async () => {
    load();
    const down = vi.spyOn(apiModule, 'downloadFile').mockResolvedValue({ truncated: false });
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await userEvent.type(screen.getByPlaceholderText(/Search by NFC UID/), 'TOMS');
    await userEvent.click(screen.getByRole('button', { name: 'active' }));
    await userEvent.click(screen.getByRole('button', { name: /Export CSV/ }));
    await waitFor(() => expect(down).toHaveBeenCalled());
    expect(down).toHaveBeenLastCalledWith('/cards/export?search=TOMS&status=active', 'toms_cards.csv');
  });

  it('warns when the export was cut short, and reports a failed export', async () => {
    load();
    const down = vi.spyOn(apiModule, 'downloadFile').mockResolvedValueOnce({ truncated: true }).mockRejectedValueOnce(new Error('x'));
    renderPage();
    await screen.findByRole('checkbox', { name: 'Select TOMS-0001' });
    await userEvent.click(screen.getByRole('button', { name: /Export CSV/ }));
    expect(await screen.findByText(/cut at 50,000 rows/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Export CSV/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not export the card list.');
    expect(down).toHaveBeenCalledTimes(2);
  });
});
