import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, tokenStore } from '../lib/api';
import CardEntry from './CardEntry';

vi.mock('./ReceiptView', () => ({ default: () => <div>passenger receipt</div> }));

const UUID = '11111111-2222-4333-8444-555555555555';
const unpaired = { card_uuid: UUID, serial: 'TOMS-0007', label: 'TOMS-0007', status: 'unpaired', nfc_uid: null };

function renderEntry() {
  return render(
    <MemoryRouter initialEntries={[`/r/${UUID}`]}>
      <Routes>
        <Route path="/r/:cardUuid" element={<CardEntry />} />
      </Routes>
    </MemoryRouter>,
  );
}

class FakeReader {
  static serial = '04:a1:b2:c3';
  private handlers: Record<string, (e: unknown) => void> = {};
  addEventListener(name: string, fn: (e: unknown) => void) {
    this.handlers[name] = fn;
  }
  scan() {
    queueMicrotask(() => this.handlers.reading?.({ serialNumber: FakeReader.serial }));
    return Promise.resolve();
  }
}

beforeEach(() => {
  (window as unknown as { NDEFReader: unknown }).NDEFReader = FakeReader;
});

afterEach(() => {
  vi.restoreAllMocks();
  delete (window as unknown as { NDEFReader?: unknown }).NDEFReader;
});

describe('CardEntry', () => {
  it('shows the passenger receipt to someone who is not signed in, without asking the admin API', async () => {
    const get = vi.spyOn(api, 'get');
    renderEntry();
    expect(await screen.findByText('passenger receipt')).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });

  it('shows the receipt to a signed-in admin when the card is already paired', async () => {
    tokenStore.set('t');
    vi.spyOn(api, 'get').mockResolvedValue({ data: { ...unpaired, status: 'active', nfc_uid: '04A1B2C3' } });
    renderEntry();
    expect(await screen.findByText('passenger receipt')).toBeInTheDocument();
  });

  it('falls back to the receipt when the pairing lookup fails', async () => {
    tokenStore.set('t');
    vi.spyOn(api, 'get').mockRejectedValue(new Error('nope'));
    renderEntry();
    expect(await screen.findByText('passenger receipt')).toBeInTheDocument();
  });

  it('lets a signed-in admin tap a card, confirm the chip and pair it', async () => {
    tokenStore.set('t');
    vi.spyOn(api, 'get').mockResolvedValue({ data: unpaired });
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    renderEntry();

    expect(await screen.findByRole('heading', { name: /Pair card TOMS-0007/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Tap card to scan/ }));
    expect(await screen.findByTestId('scanned-uid')).toHaveTextContent('04A1B2C3');
    expect(post).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /Pair 04A1B2C3 to TOMS-0007/ }));
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/cards/${UUID}/pair`, { nfc_uid: '04A1B2C3' }));
    expect(await screen.findByText(/is now paired to 04A1B2C3/)).toBeInTheDocument();
  });

  it('shows the server message when the chip already belongs to another card', async () => {
    tokenStore.set('t');
    vi.spyOn(api, 'get').mockResolvedValue({ data: unpaired });
    vi.spyOn(api, 'post').mockRejectedValue({ response: { data: { error: 'That NFC card is already paired to another card.' } } });
    renderEntry();

    await userEvent.click(await screen.findByRole('button', { name: /Tap card to scan/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Pair 04A1B2C3/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already paired to another card');
  });

  it('can undo a pairing made on the wrong card', async () => {
    tokenStore.set('t');
    vi.spyOn(api, 'get').mockResolvedValue({ data: unpaired });
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    renderEntry();

    await userEvent.click(await screen.findByRole('button', { name: /Tap card to scan/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Pair 04A1B2C3/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Undo/ }));
    expect(post).toHaveBeenLastCalledWith(`/cards/${UUID}/unpair`);
    expect(await screen.findByRole('button', { name: /Tap card to scan/ })).toBeInTheDocument();
  });

  it('explains that Web NFC is missing and disables scanning', async () => {
    tokenStore.set('t');
    delete (window as unknown as { NDEFReader?: unknown }).NDEFReader;
    vi.spyOn(api, 'get').mockResolvedValue({ data: unpaired });
    renderEntry();
    expect(await screen.findByText(/Web NFC needs Chrome on Android/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Tap card to scan/ })).toBeDisabled();
  });
});
