import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import CardDesigner from './CardDesigner';

vi.mock('../lib/cardRender', async (orig) => {
  const real = await orig<typeof import('../lib/cardRender')>();
  return {
    ...real,
    loadDesignImage: vi.fn(async () => ({ naturalWidth: 1011, naturalHeight: 638 }) as HTMLImageElement),
    buildExportZip: vi.fn(async () => new Blob(['zip'])),
    saveBlob: vi.fn(),
  };
});

import * as render_ from '../lib/cardRender';

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => 'blob:design');
  vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
    if (url === '/config') return { data: { public_base_url: 'https://toms.example.com/' } };
    return { data: [] };
  });
});

afterEach(() => vi.restoreAllMocks());

const renderPage = () => render(<MemoryRouter><CardDesigner /></MemoryRouter>);
const png = new File(['x'], 'front.png', { type: 'image/png' });

describe('CardDesigner', () => {
  it('previews the first and last serial from the rule', async () => {
    renderPage();
    expect(screen.getByTestId('serial-preview')).toHaveTextContent('TOMS-0001 → TOMS-0050');

    fireEvent.change(screen.getByLabelText('Prefix'), { target: { value: 'BUS7-' } });
    fireEvent.change(screen.getByLabelText('Digits'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('How many'), { target: { value: '12' } });
    expect(screen.getByTestId('serial-preview')).toHaveTextContent('BUS7-001 → BUS7-012');
  });

  it('uses the server public address and warns that it is permanent', async () => {
    renderPage();
    expect(await screen.findByText(/Each QR opens https:\/\/toms\.example\.com\/r\//)).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent('cannot be changed afterwards');
  });

  it('refuses an invalid batch without calling the server', async () => {
    const post = vi.spyOn(api, 'post');
    renderPage();
    fireEvent.change(screen.getByLabelText('How many'), { target: { value: '900' } });
    await userEvent.click(screen.getByRole('button', { name: /Create 900 unpaired cards/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('1 to 500');
    expect(post).not.toHaveBeenCalled();
  });

  it('creates the batch through the API and reports it', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({
      data: { cards: [{ serial: 'TOMS-0001', card_uuid: 'u1' }, { serial: 'TOMS-0002', card_uuid: 'u2' }] },
    });
    renderPage();
    fireEvent.change(screen.getByLabelText('How many'), { target: { value: '2' } });
    await userEvent.click(screen.getByRole('button', { name: /Create 2 unpaired cards/ }));
    expect(post).toHaveBeenCalledWith('/cards/batch', { count: 2, prefix: 'TOMS-', start: 1, digits: 4, vehicle_id: null });
    expect(await screen.findByRole('status')).toHaveTextContent('2 cards created: TOMS-0001 to TOMS-0002');
  });

  it('shows the server error when a serial already exists', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ response: { data: { error: 'One or more of those serials already exist.' } } });
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /Create 50 unpaired cards/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already exist');
  });

  it('shows the QR and serial boxes once a design is uploaded, and a resolution note', async () => {
    renderPage();
    await userEvent.upload(screen.getByLabelText('front design'), png);
    expect(await screen.findByTestId('qr-box-front')).toBeInTheDocument();
    expect(screen.getByTestId('text-box-front')).toHaveTextContent('TOMS-0001');
    expect(screen.getByTestId('resolution-note')).toHaveTextContent('1011 × 638 px');
  });

  it('asks for the front design and the cards before exporting', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /Download ZIP/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Upload the front design first');

    await userEvent.upload(screen.getByLabelText('front design'), png);
    await userEvent.click(screen.getByRole('button', { name: /Download ZIP/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Create the cards first');
  });

  it('exports a ZIP named after the first and last serial', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({
      data: { cards: [{ serial: 'TOMS-0001', card_uuid: 'u1' }, { serial: 'TOMS-0002', card_uuid: 'u2' }] },
    });
    renderPage();
    fireEvent.change(screen.getByLabelText('How many'), { target: { value: '2' } });
    await userEvent.click(screen.getByRole('button', { name: /Create 2 unpaired cards/ }));
    await userEvent.upload(screen.getByLabelText('front design'), png);
    await screen.findByTestId('qr-box-front');
    await userEvent.click(screen.getByRole('button', { name: /Download ZIP/ }));

    await waitFor(() => expect(render_.saveBlob).toHaveBeenCalled());
    expect(render_.saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'TOMS-0001_to_TOMS-0002.zip');
    expect(render_.buildExportZip).toHaveBeenCalledWith(expect.objectContaining({ baseUrl: 'https://toms.example.com' }));
  });
});

describe('CardDesigner reprint', () => {
  const renderWith = (state: object | null) =>
    render(
      <MemoryRouter initialEntries={[{ pathname: '/cards/designer', state }]}>
        <CardDesigner />
      </MemoryRouter>,
    );

  const existing = [
    { card_uuid: 'u1', serial: 'TOMS-0001', status: 'active', nfc_uid: '04AA' },
    { card_uuid: 'u2', serial: 'TOMS-0002', status: 'lost', nfc_uid: '04BB' },
  ];

  it('loads the chosen cards instead of offering to create a new batch', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { cards: existing, count: 2 } });
    renderWith({ reprint: { card_uuids: ['u1', 'u2'] } });
    expect(await screen.findByText(/Reprinting 2 existing cards/)).toBeInTheDocument();
    expect(post).toHaveBeenCalledWith('/cards/lookup', { card_uuids: ['u1', 'u2'] });
    expect(screen.queryByRole('button', { name: /unpaired cards/ })).not.toBeInTheDocument();
  });

  it('passes a filter through, for everything that matched in the registry', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { cards: existing, count: 2 } });
    renderWith({ reprint: { filter: { search: 'TOMS-', status: 'active' } } });
    await screen.findByText(/Reprinting 2 existing cards/);
    expect(post).toHaveBeenCalledWith('/cards/lookup', { filter: { search: 'TOMS-', status: 'active' } });
  });

  it('uses the existing card ids, so the QR codes do not change', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: { cards: existing, count: 2 } });
    renderWith({ reprint: { card_uuids: ['u1', 'u2'] } });
    await screen.findByText(/Reprinting 2 existing cards/);
    await userEvent.upload(screen.getByLabelText('front design'), png);
    await screen.findByTestId('qr-box-front');
    await userEvent.click(screen.getByRole('button', { name: /Download ZIP/ }));
    await waitFor(() => expect(render_.buildExportZip).toHaveBeenCalled());
    const call = (render_.buildExportZip as unknown as { mock: { calls: [{ cards: { serial: string; card_uuid: string }[] }][] } }).mock.calls[0][0];
    expect(call.cards).toEqual([
      { serial: 'TOMS-0001', card_uuid: 'u1' },
      { serial: 'TOMS-0002', card_uuid: 'u2' },
    ]);
  });

  it('leaves out a card that has no serial and says so', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: { cards: [...existing, { card_uuid: 'u3', serial: null, status: 'active', nfc_uid: '04CC' }], count: 3 } });
    renderWith({ reprint: { card_uuids: ['u1', 'u2', 'u3'] } });
    expect(await screen.findByText(/Reprinting 2 existing cards \(1 without a serial left out\)/)).toBeInTheDocument();
  });

  it('uses the singular for one card', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: { cards: [existing[0]], count: 1 } });
    renderWith({ reprint: { card_uuids: ['u1'] } });
    expect(await screen.findByText(/Reprinting 1 existing card\./)).toBeInTheDocument();
  });

  it('shows the server message when the lookup fails', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ response: { data: { error: 'That matches more than 500 cards. Narrow the filter and try again.' } } });
    renderWith({ reprint: { filter: {} } });
    expect(await screen.findByRole('alert')).toHaveTextContent('more than 500 cards');
  });

  it('does nothing special when opened normally', () => {
    const post = vi.spyOn(api, 'post');
    renderWith(null);
    expect(post).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Create 50 unpaired cards/ })).toBeInTheDocument();
  });
});
