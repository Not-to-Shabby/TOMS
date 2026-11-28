import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import { APP_ENROL_SCREEN_READY, POLL_MILLIS, endedFrom, formatCountdown, type PairingCodeRow, type Phone } from '../lib/pairing';
import Phones from './Phones';

const NOW = new Date('2026-11-29T01:00:00.000Z');
const CODE = 'K7M2-9XQF-R4TH';
const QR = `toms-enrol://v1?server=https%3A%2F%2Ftoms.example&code=${CODE}`;

function phone(over: Partial<Phone> = {}): Phone {
  return {
    device_id: 'phone-1',
    vehicle_id: 'BUS-1',
    label: null,
    enrolled_at: '2026-11-28T01:00:00.000Z',
    revoked_at: null,
    revoked: false,
    last_seen: '2026-11-29T00:55:00.000Z',
    ...over,
  };
}

function row(over: Partial<PairingCodeRow> = {}): PairingCodeRow {
  return {
    id: 1,
    code_id: 'K7M2',
    vehicle_id: 'BUS-1',
    label: null,
    created_by: 'boss',
    created_at: '2026-11-29T00:59:00.000Z',
    expires_at: '2026-11-29T01:09:00.000Z',
    attempts: 0,
    used_at: null,
    used_device_id: null,
    status: 'open',
    ...over,
  };
}

/** The server as the page sees it. `state` is what the next read of the lists returns. */
function server(state: { phones?: Phone[]; codes?: PairingCodeRow[] } = {}) {
  const s = { phones: state.phones ?? [], codes: state.codes ?? [] };
  const get = vi.spyOn(api, 'get').mockImplementation((url: string) => {
    if (url === '/devices') return Promise.resolve({ data: s.phones });
    if (url === '/pairing-codes') return Promise.resolve({ data: s.codes });
    if (url === '/vehicles') {
      return Promise.resolve({ data: [{ id: 'BUS-1', plate_number: 'AAA 111' }, { id: 'BUS-2', plate_number: 'BBB 222' }] });
    }
    return Promise.reject({ response: { status: 404 } });
  });
  return { s, get };
}

const issuedResponse = (over: Record<string, unknown> = {}) => ({
  data: {
    id: 1,
    code: CODE,
    code_id: 'K7M2',
    vehicle_id: 'BUS-1',
    label: null,
    created_by: 'boss',
    created_at: NOW.toISOString(),
    expires_at: new Date(NOW.getTime() + 600_000).toISOString(),
    expires_in_seconds: 600,
    qr_payload: QR,
    ...over,
  },
});

async function pickVehicleAndIssue(vehicle = 'BUS-1') {
  const select = (await screen.findAllByRole('combobox'))[0] as HTMLSelectElement;
  await waitFor(() => expect(select.options.length).toBeGreaterThan(1));
  fireEvent.change(select, { target: { value: vehicle } });
  fireEvent.click(screen.getByRole('button', { name: /Add phone/ }));
}

/** React starts a panel's timers and listeners in an effect that runs just after it appears: let that happen. */
async function settleEffects() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true, now: NOW });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('formatCountdown', () => {
  it('counts down as m:ss, rounds up, and never goes below zero', () => {
    expect(formatCountdown(600)).toBe('10:00');
    expect(formatCountdown(599.2)).toBe('10:00');
    expect(formatCountdown(65)).toBe('1:05');
    expect(formatCountdown(9)).toBe('0:09');
    expect(formatCountdown(0)).toBe('0:00');
    expect(formatCountdown(-30)).toBe('0:00');
  });
});

describe('endedFrom', () => {
  it('says which phone used a code, and reads anything unclear as expired', () => {
    expect(endedFrom(row({ status: 'used', used_device_id: 'phone-7' }))).toEqual({ kind: 'used', deviceId: 'phone-7', vehicleId: 'BUS-1' });
    expect(endedFrom(row({ status: 'locked' }))).toEqual({ kind: 'locked' });
    expect(endedFrom(row({ status: 'cancelled' }))).toEqual({ kind: 'cancelled' });
    expect(endedFrom(row({ status: 'expired' }))).toEqual({ kind: 'expired' });
    expect(endedFrom(row({ status: 'open' }))).toEqual({ kind: 'expired' });
    expect(endedFrom(undefined)).toEqual({ kind: 'expired' });
  });
});

describe('Phones: the app has no enrolment screen yet', () => {
  it('says so on the page, names the command that works today, and does not tell the admin to use a screen that does not exist', async () => {
    server();
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse());
    render(<Phones />);
    const note = await screen.findByRole('note');
    expect(note.textContent).toMatch(/does not have its enrolment screen yet/);
    expect(note.textContent).toContain('setup-pilot.mjs');
    expect(note.textContent).toContain('enrol-device');

    await pickVehicleAndIssue();
    const panel = await screen.findByRole('region', { name: 'New pairing code' });
    expect(within(panel).getByText(/The app cannot use this code yet/)).toBeInTheDocument();
    expect(panel.textContent).not.toMatch(/choose Scan to enrol/);
  });

  it('is shipped as not ready: the flag in lib/pairing.ts is false until the app PR sets it', () => {
    expect(APP_ENROL_SCREEN_READY).toBe(false);
  });

  it('tells the admin to scan or type the code, and shows no note, once the app has its screen', async () => {
    server();
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse());
    render(<Phones appEnrolReady />);
    await pickVehicleAndIssue();
    const panel = await screen.findByRole('region', { name: 'New pairing code' });
    expect(within(panel).getByText(/choose Scan to enrol and scan this code, or type the code by hand/)).toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
    expect(panel.textContent).not.toMatch(/cannot use this code yet/);
  });
});

describe('Phones: the lists', () => {
  it('shows each phone with its vehicle, last upload and state, and each code by its public id only', async () => {
    server({
      phones: [phone({ label: 'Front phone' }), phone({ device_id: 'phone-2', vehicle_id: 'BUS-2', revoked: true, revoked_at: '2026-11-28T09:00:00.000Z', last_seen: null })],
      codes: [row({ status: 'used', used_device_id: 'phone-1', used_at: '2026-11-29T00:59:30.000Z' }), row({ id: 2, code_id: 'ABCD', status: 'locked', attempts: 5 })],
    });
    const { container } = render(<Phones />);

    // The name shows twice: in the phones list, and in the codes list as who used the first code.
    expect(await screen.findAllByText('Front phone')).toHaveLength(2);
    expect(screen.getByText('Not seen yet')).toBeInTheDocument();
    expect(screen.getByText('Revoked')).toBeInTheDocument();
    expect(screen.getByText('Locked')).toBeInTheDocument();
    expect(screen.getByText('5 wrong tries')).toBeInTheDocument();
    // A code in the list is its public id with the secret hidden, never a whole code.
    expect(screen.getByText('K7M2-····-····')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\b[2-9A-HJKMNP-TV-Z]{4}-[2-9A-HJKMNP-TV-Z]{4}-[2-9A-HJKMNP-TV-Z]{4}\b/);
  });

  it('shows which phone used a code, with its name and a reminder to check it is the right one', async () => {
    server({
      phones: [phone({ label: 'Front phone' })],
      codes: [row({ status: 'used', used_device_id: 'phone-1' })],
    });
    render(<Phones />);
    const used = (await screen.findByText('Used')).closest('tr') as HTMLElement;
    expect(within(used).getByText('phone-1')).toBeInTheDocument();
    expect(within(used).getByText('Front phone')).toBeInTheDocument();
    expect(within(used).getByText(/Check this is the phone you meant/)).toBeInTheDocument();
    expect(within(used).getByRole('button', { name: 'Revoke this phone' })).toBeInTheDocument();
  });

  it('says so, instead of a revoke button, when the phone that used a code is already revoked', async () => {
    server({ phones: [phone({ revoked: true, revoked_at: '2026-11-29T00:58:00.000Z' })], codes: [row({ status: 'used', used_device_id: 'phone-1' })] });
    render(<Phones />);
    const used = (await screen.findByText('Used')).closest('tr') as HTMLElement;
    expect(within(used).getByText('Revoked.')).toBeInTheDocument();
    expect(within(used).queryByRole('button', { name: /Revoke/ })).not.toBeInTheDocument();
  });

  it('offers no revoke button for a phone that is already revoked, and says plainly when nothing exists yet', async () => {
    server({ phones: [phone({ revoked: true, revoked_at: '2026-11-29T00:58:00.000Z' })] });
    const first = render(<Phones />);
    await screen.findByText('Revoked');
    expect(screen.queryByRole('button', { name: /^Revoke/ })).not.toBeInTheDocument();
    first.unmount();

    server();
    render(<Phones />);
    expect(await screen.findByText('No phone is enrolled yet.')).toBeInTheDocument();
    expect(screen.getByText('No code has been issued yet.')).toBeInTheDocument();
  });

  it('shows a load error with a way to retry, and keeps working after it', async () => {
    const get = vi.spyOn(api, 'get').mockRejectedValue({ response: { status: 500 } });
    render(<Phones />);
    expect((await screen.findByRole('alert')).textContent).toMatch(/Could not load the phones/);
    get.mockReset();
    server({ phones: [phone()] });
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('phone-1')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Phones: issuing a code', () => {
  it('shows the code and its QR once, with a countdown, and sends only the vehicle and the optional name', async () => {
    server();
    const post = vi.spyOn(api, 'post').mockResolvedValue(issuedResponse({ label: 'Front phone' }));
    render(<Phones />);
    fireEvent.change(await screen.findByLabelText(/Name for this phone/), { target: { value: '  Front phone  ' } });
    await pickVehicleAndIssue();

    expect(await screen.findByLabelText('Pairing code')).toHaveTextContent(CODE);
    expect(post).toHaveBeenCalledWith('/pairing-codes', { vehicle_id: 'BUS-1', label: 'Front phone' });
    const panel = screen.getByRole('region', { name: 'New pairing code' });
    // The QR is the one svg that is not an icon (the warning below the code is a lucide icon).
    expect(panel.querySelector('svg:not(.lucide)')).not.toBeNull();
    expect(within(panel).getByRole('timer')).toHaveTextContent('10:00');
    expect(within(panel).getByText(/cannot be shown again/)).toBeInTheDocument();
  });

  it('leaves the name out of the request when it is blank', async () => {
    server();
    const post = vi.spyOn(api, 'post').mockResolvedValue(issuedResponse());
    render(<Phones />);
    await pickVehicleAndIssue('BUS-2');
    await screen.findByLabelText('Pairing code');
    expect(post).toHaveBeenCalledWith('/pairing-codes', { vehicle_id: 'BUS-2' });
  });

  it('shows no QR when the server has no public address, and says to type the code', async () => {
    server();
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse({ qr_payload: null }));
    render(<Phones appEnrolReady />);
    await pickVehicleAndIssue();
    const panel = await screen.findByRole('region', { name: 'New pairing code' });
    expect(panel.querySelector('svg:not(.lucide)')).toBeNull();
    expect(within(panel).getByText(/no public address set, so there is no QR code/)).toBeInTheDocument();
    expect(within(panel).getByLabelText('Pairing code')).toHaveTextContent(CODE);
  });

  it('does not put the code in storage, in the address, or on the page outside its own panel', async () => {
    server();
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse());
    const before = window.location.href;
    render(<Phones />);
    await pickVehicleAndIssue();
    await screen.findByLabelText('Pairing code');

    // Every way the secret half can be written: as shown (with its dash), run together, and the whole code both ways.
    // (A first version looked only for the run-together form, so a code saved WITH its dash went unnoticed.)
    const forms = [CODE, CODE.replace(/-/g, ''), '9XQF-R4TH', '9XQFR4TH'];
    const holdsCode = (text: string) => forms.some((f) => text.toUpperCase().includes(f));
    sessionStorage.setItem('probe', 'x'); // proves the loop below really reads the stores
    let seen = 0;
    for (const store of [localStorage, sessionStorage]) {
      for (let i = 0; i < store.length; i++) {
        const key = store.key(i)!;
        seen++;
        expect(holdsCode(`${key}=${store.getItem(key)}`)).toBe(false);
      }
    }
    expect(seen).toBeGreaterThan(0);
    sessionStorage.removeItem('probe');
    expect(window.location.href).toBe(before);
    expect(holdsCode(window.location.href)).toBe(false);
    const panel = screen.getByRole('region', { name: 'New pairing code' });
    const outside = document.body.textContent!.replace(panel.textContent!, '');
    expect(holdsCode(outside)).toBe(false);
  });

  it("shows the server's own message when the open-code limit is reached, and shows no code", async () => {
    server();
    vi.spyOn(api, 'post').mockRejectedValue({ response: { status: 409, data: { error: 'There are already 10 open pairing codes. Cancel one, or wait for one to expire.' } } });
    render(<Phones />);
    await pickVehicleAndIssue();
    expect((await screen.findByRole('alert')).textContent).toMatch(/already 10 open pairing codes/);
    expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument();
  });

  it('needs a vehicle, and says to add one first when there is none', async () => {
    server();
    render(<Phones />);
    const add = await screen.findByRole('button', { name: /Add phone/ });
    expect(add).toBeDisabled();

    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      Promise.resolve({ data: url === '/vehicles' ? [] : [] }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText(/There is no vehicle yet/)).toBeInTheDocument();
  });
});

describe('Phones: the code leaves the screen', () => {
  async function showCode(state: { codes?: PairingCodeRow[]; phones?: Phone[] } = {}) {
    const srv = server({ ...state, codes: state.codes ?? [row()] });
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse());
    render(<Phones />);
    await pickVehicleAndIssue();
    await screen.findByLabelText('Pairing code');
    return srv;
  }

  it('counts down every second, and turns to a warning in the last minute', async () => {
    await showCode();
    const timer = screen.getByRole('timer');
    expect(timer).toHaveTextContent('10:00');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(timer).toHaveTextContent('9:55');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(535_000);
    });
    expect(timer).toHaveTextContent('1:00');
    expect(timer.style.color).toBe('var(--warning)');
  });

  it('removes the code the moment the countdown ends, and says it expired', async () => {
    const srv = await showCode();
    srv.s.codes = [row({ status: 'expired' })];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600_000);
    });
    await waitFor(() => expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument());
    expect(screen.queryByRole('region', { name: 'New pairing code' })).not.toBeInTheDocument();
    expect(await screen.findByText(/This code expired and no longer works/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(CODE);
  });

  it('counts from this computer clock, so a server clock that is wrong cannot keep a dead code on screen', async () => {
    server();
    // The server says the code expires in a day by ITS clock, but only 600 seconds are granted.
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse({ expires_at: '2099-01-01T00:00:00.000Z' }));
    render(<Phones />);
    await pickVehicleAndIssue();
    await screen.findByLabelText('Pairing code');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600_000);
    });
    await waitFor(() => expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument());
  });

  it('checks the clock again when the tab comes back, because a background tab slows its timers', async () => {
    await showCode();
    await settleEffects();
    // The computer slept: the clock jumped past the deadline without a single timer firing.
    vi.setSystemTime(new Date(NOW.getTime() + 11 * 60_000));
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await waitFor(() => expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument());
    delete (document as unknown as Record<string, unknown>).visibilityState;
  });

  it('notices that a phone used the code, removes it, and names the phone', async () => {
    const srv = await showCode({ phones: [phone({ device_id: 'phone-9' })] });
    srv.s.codes = [row({ status: 'used', used_device_id: 'phone-9', used_at: '2026-11-29T01:00:20.000Z' })];
    srv.s.phones = [phone({ device_id: 'phone-9', label: 'Front phone' })];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MILLIS + 100);
    });
    expect(await screen.findByText(/Paired: phone phone-9 is now enrolled on BUS-1/)).toBeInTheDocument();
    expect(screen.getByText(/Check that this is the phone you meant/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(CODE);
  });

  it('removes a code that locked after wrong tries, and says so', async () => {
    const srv = await showCode();
    srv.s.codes = [row({ status: 'locked', attempts: 5 })];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MILLIS + 100);
    });
    expect(await screen.findByText(/locked after too many wrong tries/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument();
  });

  it('keeps showing the code and keeps watching when a read fails', async () => {
    const srv = await showCode();
    const get = vi.mocked(api.get);
    get.mockRejectedValueOnce({ response: { status: 500 } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MILLIS + 100);
    });
    expect(screen.getByLabelText('Pairing code')).toHaveTextContent(CODE);

    srv.s.codes = [row({ status: 'used', used_device_id: 'phone-3' })];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MILLIS + 100);
    });
    expect(await screen.findByText(/Paired: phone phone-3/)).toBeInTheDocument();
  });

  it('cancels from the code panel: asks the server, removes the code, and says it was cancelled', async () => {
    await showCode();
    const del = vi.spyOn(api, 'delete').mockResolvedValue({ data: { status: 'cancelled' } });
    fireEvent.click(within(screen.getByRole('region', { name: 'New pairing code' })).getByRole('button', { name: 'Cancel this code' }));
    await waitFor(() => expect(del).toHaveBeenCalledWith('/pairing-codes/1'));
    await waitFor(() => expect(screen.queryByLabelText('Pairing code')).not.toBeInTheDocument());
    expect(await screen.findByText('This code was cancelled and no longer works.')).toBeInTheDocument();
  });

  it('stops watching and leaves nothing running when the page is left with the code still on screen', async () => {
    server({ codes: [row()] });
    vi.spyOn(api, 'post').mockResolvedValue(issuedResponse());
    const view = render(<Phones />);
    await pickVehicleAndIssue();
    await screen.findByLabelText('Pairing code');
    await settleEffects();

    // Positive control: while the code is on screen the lists are read on every tick, and timers are running.
    const get = vi.mocked(api.get);
    const beforePolling = get.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MILLIS * 3 + 100);
    });
    expect(get.mock.calls.length - beforePolling).toBe(3 * 2);
    expect(vi.getTimerCount()).toBeGreaterThan(0);

    // Leaving the page unmounts it: the code is gone from the document and nothing keeps running.
    view.unmount();
    expect(document.body.textContent).not.toContain(CODE);
    expect(vi.getTimerCount()).toBe(0);
    const afterUnmount = get.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MILLIS * 5);
    });
    expect(get.mock.calls.length).toBe(afterUnmount);
  });
});

describe('Phones: cancelling a code from the list', () => {
  it('cancels an open code and reads the list again', async () => {
    const srv = server({ codes: [row({ id: 7 })] });
    const del = vi.spyOn(api, 'delete').mockImplementation(async () => {
      srv.s.codes = [row({ id: 7, status: 'cancelled' })];
      return { data: { status: 'cancelled' } };
    });
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel code' }));
    await waitFor(() => expect(del).toHaveBeenCalledWith('/pairing-codes/7'));
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel code' })).not.toBeInTheDocument();
  });

  it('shows the reason when the server refuses', async () => {
    server({ codes: [row({ id: 7 })] });
    vi.spyOn(api, 'delete').mockRejectedValue({ response: { status: 404, data: { error: 'Pairing code not found, or it is already used or cancelled' } } });
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel code' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/already used or cancelled/);
  });
});

describe('Phones: revoking needs a confirmation', () => {
  it('sends nothing on the first click: it names the phone and warns that it cannot be undone', async () => {
    server({ phones: [phone({ label: 'Front phone' })] });
    const del = vi.spyOn(api, 'delete').mockResolvedValue({ data: { status: 'revoked' } });
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));

    const dialog = await screen.findByRole('dialog', { name: 'Revoke this phone?' });
    expect(within(dialog).getByText('phone-1')).toBeInTheDocument();
    expect(within(dialog).getByText(/Front phone/)).toBeInTheDocument();
    expect(within(dialog).getByText(/This cannot be undone/)).toBeInTheDocument();
    expect(within(dialog).getByText(/can never be enrolled again/)).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();
  });

  it('sends nothing when it is cancelled, closed with Escape, or dismissed by clicking outside', async () => {
    server({ phones: [phone()] });
    const del = vi.spyOn(api, 'delete').mockResolvedValue({ data: { status: 'revoked' } });
    render(<Phones />);

    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }));
    fireEvent.mouseDown(screen.getByRole('dialog').parentElement!);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    expect(del).not.toHaveBeenCalled();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('revokes only after the confirm button, for that phone, and then shows it as revoked', async () => {
    const srv = server({ phones: [phone(), phone({ device_id: 'phone-2', vehicle_id: 'BUS-2' })] });
    const del = vi.spyOn(api, 'delete').mockImplementation(async () => {
      srv.s.phones = [phone({ revoked: true, revoked_at: '2026-11-29T01:00:05.000Z' }), phone({ device_id: 'phone-2', vehicle_id: 'BUS-2' })];
      return { data: { status: 'revoked' } };
    });
    render(<Phones />);
    const row2 = (await screen.findByText('phone-2')).closest('tr') as HTMLElement;
    fireEvent.click(within(row2).getByRole('button', { name: 'Revoke' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke phone' }));

    await waitFor(() => expect(del).toHaveBeenCalledTimes(1));
    expect(del).toHaveBeenCalledWith('/devices/phone-2');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Revoked')).toBeInTheDocument();
  });

  it('encodes the Device ID in the address it revokes, as defence in depth (the server only issues plain IDs)', async () => {
    // A plain ID such as phone-1 encodes to itself, so only an ID with characters that mean something in an address
    // can show whether the encoding is there. The server's IDs are letters, digits, dash and underscore.
    server({ phones: [phone({ device_id: 'a b/c?d' })] });
    const del = vi.spyOn(api, 'delete').mockResolvedValue({ data: {} });
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke phone' }));
    await waitFor(() => expect(del).toHaveBeenCalledWith('/devices/a%20b%2Fc%3Fd'));
  });

  it('is offered from the codes list too, behind the same dialog', async () => {
    server({ phones: [phone()], codes: [row({ status: 'used', used_device_id: 'phone-1' })] });
    const del = vi.spyOn(api, 'delete').mockResolvedValue({ data: {} });
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke this phone' }));
    expect(await screen.findByRole('dialog', { name: 'Revoke this phone?' })).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();
  });

  it('stays open with the reason when the server refuses, and lets the person try again', async () => {
    server({ phones: [phone()] });
    const del = vi.spyOn(api, 'delete').mockRejectedValueOnce({ response: { status: 404, data: { error: 'Device not found or already revoked' } } }).mockResolvedValue({ data: {} });
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke phone' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/already revoked/);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke phone' }));
    await waitFor(() => expect(del).toHaveBeenCalledTimes(2));
  });

  it('cannot be double-submitted while the request is out', async () => {
    server({ phones: [phone()] });
    let release: () => void = () => undefined;
    const del = vi.spyOn(api, 'delete').mockImplementation(() => new Promise((resolve) => { release = () => resolve({ data: {} }); }));
    render(<Phones />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));
    const confirm = within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke phone' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(del).toHaveBeenCalledTimes(1);
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Working…' })).toBeDisabled();
    await act(async () => release());
  });
});
