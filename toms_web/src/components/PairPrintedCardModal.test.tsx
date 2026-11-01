import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import * as qrScan from '../lib/qrScan';
import PairPrintedCardModal from './PairPrintedCardModal';
import PairPanel from './PairPanel';

const ID_A = '11111111-2222-4333-8444-555555555555';
const ID_B = '66666666-7777-4888-8999-000000000000';
const waiting = [
  { card_uuid: ID_A, serial: 'TOMS-0001', label: 'TOMS-0001', status: 'unpaired', nfc_uid: null },
  { card_uuid: ID_B, serial: 'TOMS-0002', label: 'TOMS-0002', status: 'unpaired', nfc_uid: null },
];

class FakeReader {
  private h: Record<string, (e: unknown) => void> = {};
  addEventListener(n: string, f: (e: unknown) => void) {
    this.h[n] = f;
  }
  scan() {
    queueMicrotask(() => this.h.reading?.({ serialNumber: '04:a1:b2:c3' }));
    return Promise.resolve();
  }
}

const renderModal = (over: Partial<Parameters<typeof PairPrintedCardModal>[0]> = {}) => {
  const props = { waiting, onClose: vi.fn(), onChanged: vi.fn(), ...over };
  render(<PairPrintedCardModal {...props} />);
  return props;
};

beforeEach(() => {
  (window as unknown as { NDEFReader: unknown }).NDEFReader = FakeReader;
  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: vi.fn() }, configurable: true });
});

afterEach(() => {
  vi.restoreAllMocks();
  delete (window as unknown as { NDEFReader?: unknown }).NDEFReader;
});

describe('PairPrintedCardModal', () => {
  it('lists the cards still waiting and filters by serial', async () => {
    renderModal();
    expect(screen.getByText('Or pick from the 2 cards still waiting')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Find a serial'), '0002');
    expect(screen.queryByRole('button', { name: 'TOMS-0001' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'TOMS-0002' })).toBeInTheDocument();
  });

  it('says when every card is already paired', () => {
    renderModal({ waiting: [] });
    expect(screen.getByText(/Every card is paired/)).toBeInTheDocument();
  });

  it('pairs a card picked from the list: tap, confirm, registry refreshes', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    const props = renderModal();
    await userEvent.click(screen.getByRole('button', { name: 'TOMS-0001' }));
    await userEvent.click(screen.getByRole('button', { name: /Tap card to scan/ }));
    expect(await screen.findByTestId('scanned-uid')).toHaveTextContent('04A1B2C3');
    await userEvent.click(screen.getByRole('button', { name: /Pair 04A1B2C3 to TOMS-0001/ }));
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/cards/${ID_A}/pair`, { nfc_uid: '04A1B2C3' }));
    expect(props.onChanged).toHaveBeenCalled();
  });

  it('lets the admin go back and pair another card', async () => {
    renderModal();
    await userEvent.click(screen.getByRole('button', { name: 'TOMS-0001' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pair another card' }));
    expect(screen.getByRole('button', { name: 'TOMS-0002' })).toBeInTheDocument();
  });

  it('turns a scanned QR into the card to pair', async () => {
    vi.spyOn(qrScan, 'startQrScan').mockImplementation(async (_v, onText) => {
      queueMicrotask(() => onText(`https://toms.example.com/r/${ID_B}`));
      return () => undefined;
    });
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: waiting[1] });
    renderModal();
    await userEvent.click(screen.getByRole('button', { name: /Scan the QR on the card/ }));
    expect(await screen.findByRole('heading', { name: /Pair card TOMS-0002/ })).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith(`/cards/${ID_B}/pairing`);
  });

  it('rejects a QR that is not a TOMS card without asking the server', async () => {
    vi.spyOn(qrScan, 'startQrScan').mockImplementation(async (_v, onText) => {
      queueMicrotask(() => onText('https://example.com/menu'));
      return () => undefined;
    });
    const get = vi.spyOn(api, 'get');
    renderModal();
    await userEvent.click(screen.getByRole('button', { name: /Scan the QR on the card/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not a TOMS card');
    expect(get).not.toHaveBeenCalled();
  });

  it('refuses a card that is already paired and says what it is paired to', async () => {
    vi.spyOn(qrScan, 'startQrScan').mockImplementation(async (_v, onText) => {
      queueMicrotask(() => onText(`https://toms.example.com/r/${ID_A}`));
      return () => undefined;
    });
    vi.spyOn(api, 'get').mockResolvedValue({ data: { ...waiting[0], status: 'active', nfc_uid: '04FFEEDD' } });
    renderModal();
    await userEvent.click(screen.getByRole('button', { name: /Scan the QR on the card/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already paired to 04FFEEDD');
    expect(screen.queryByRole('heading', { name: /Pair card/ })).not.toBeInTheDocument();
  });

  it('tells the admin when the QR belongs to no card made here', async () => {
    vi.spyOn(qrScan, 'startQrScan').mockImplementation(async (_v, onText) => {
      queueMicrotask(() => onText(`https://toms.example.com/r/${ID_A}`));
      return () => undefined;
    });
    vi.spyOn(api, 'get').mockRejectedValue({ response: { status: 404 } });
    renderModal();
    await userEvent.click(screen.getByRole('button', { name: /Scan the QR on the card/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('does not belong to any card made here');
  });

  it('disables the camera button and explains when there is no camera', () => {
    Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true });
    renderModal();
    expect(screen.getByRole('button', { name: /Scan the QR on the card/ })).toBeDisabled();
    expect(screen.getByText(/needs an HTTPS address/)).toBeInTheDocument();
  });

  it('closes from the X button', async () => {
    const props = renderModal();
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(props.onClose).toHaveBeenCalled();
  });
});

describe('PairPanel in replace mode', () => {
  const info = { card_uuid: ID_A, serial: 'TOMS-0001', label: 'TOMS-0001', status: 'lost', nfc_uid: '04OLD0001' };

  const tap = async () => {
    await userEvent.click(screen.getByRole('button', { name: /Tap card to scan/ }));
    await screen.findByTestId('scanned-uid');
  };

  it('says the old chip will stop and that the QR stays', () => {
    render(<PairPanel info={info} mode="replace" />);
    expect(screen.getByTestId('pair-heading')).toHaveTextContent('Replace the chip on TOMS-0001');
    expect(screen.getByText(/04OLD0001/)).toBeInTheDocument();
    expect(screen.getByText(/keeps its printed QR and serial/)).toBeInTheDocument();
  });

  it('sends the new chip and the reason to the replace endpoint', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    const onPaired = vi.fn();
    render(<PairPanel info={info} mode="replace" onPaired={onPaired} />);
    await tap();
    await userEvent.type(screen.getByLabelText('Reason'), 'Chip cracked');
    await userEvent.click(screen.getByRole('button', { name: /Use 04A1B2C3 for TOMS-0001/ }));
    await waitFor(() => expect(onPaired).toHaveBeenCalled());
    expect(post).toHaveBeenCalledWith(`/cards/${ID_A}/replace`, { nfc_uid: '04A1B2C3', reason: 'Chip cracked', confirm: undefined });
    expect(await screen.findByText(/now uses chip 04A1B2C3/)).toBeInTheDocument();
  });

  it('warns about an open trip and only goes ahead on a second click', async () => {
    const post = vi
      .spyOn(api, 'post')
      .mockRejectedValueOnce({ response: { status: 409, data: { open_trips: 1 } } })
      .mockResolvedValueOnce({ data: {} });
    render(<PairPanel info={info} mode="replace" />);
    await tap();
    await userEvent.click(screen.getByRole('button', { name: /Use 04A1B2C3/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('1 open trip, so a passenger may still be aboard');
    await userEvent.click(screen.getByRole('button', { name: /Replace anyway with 04A1B2C3/ }));
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post).toHaveBeenLastCalledWith(`/cards/${ID_A}/replace`, { nfc_uid: '04A1B2C3', reason: undefined, confirm: true });
  });

  it('shows the server message when the chip belongs to another card', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ response: { status: 409, data: { error: 'That NFC card is already paired to another card.' } } });
    render(<PairPanel info={info} mode="replace" />);
    await tap();
    await userEvent.click(screen.getByRole('button', { name: /Use 04A1B2C3/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already paired to another card');
  });

  it('has no Undo button, because replacing is not a first pairing', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    render(<PairPanel info={info} mode="replace" />);
    await tap();
    await userEvent.click(screen.getByRole('button', { name: /Use 04A1B2C3/ }));
    await screen.findByText(/now uses chip/);
    expect(screen.queryByRole('button', { name: /Undo/ })).not.toBeInTheDocument();
  });

  it('keeps the first-pairing flow unchanged by default', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    render(<PairPanel info={{ ...info, status: 'unpaired', nfc_uid: null }} />);
    expect(screen.getByTestId('pair-heading')).toHaveTextContent('Pair card TOMS-0001');
    expect(screen.queryByLabelText('Reason')).not.toBeInTheDocument();
    await tap();
    await userEvent.click(screen.getByRole('button', { name: /Pair 04A1B2C3 to TOMS-0001/ }));
    await waitFor(() => expect(post).toHaveBeenCalledWith(`/cards/${ID_A}/pair`, { nfc_uid: '04A1B2C3' }));
  });
});
