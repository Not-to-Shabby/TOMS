import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import ReturnMachines, { keyStatus, type ReturnMachine } from './ReturnMachines';

afterEach(() => vi.restoreAllMocks());

function machine(over: Partial<ReturnMachine> = {}): ReturnMachine {
  return {
    id: 1,
    machine_id: 'TOMS-RM-3C48',
    vehicle_id: 'v1',
    created_at: '2026-11-16T01:00:00.000Z',
    key_created_at: '2026-11-16T01:00:00.000Z',
    rotated_at: null,
    key_version: 1,
    last_issued_at: '2026-11-16T02:00:00.000Z',
    last_issued_device: 'dev-1',
    last_issued_version: 1,
    mismatch_since: null,
    mismatch_reason: null,
    in_grace: false,
    ...over,
  };
}

function mockApi(machines: ReturnMachine[]) {
  return vi.spyOn(api, 'get').mockImplementation((url: string) => {
    if (url === '/return-machines') return Promise.resolve({ data: machines });
    if (url === '/vehicles') return Promise.resolve({ data: [{ id: 'v1', plate_number: 'AAA 111' }, { id: 'v2', plate_number: 'BBB 222' }] });
    return Promise.reject({ response: { status: 404 } });
  });
}

describe('keyStatus', () => {
  it('ranks a mismatch over everything, then an unfetched key, then a stale phone, then healthy', () => {
    expect(keyStatus({ last_issued_at: null, last_issued_version: null, key_version: 1, mismatch_reason: 'machine_has_other_key' }).tone).toBe('bad');
    expect(keyStatus({ last_issued_at: null, last_issued_version: null, key_version: 1, mismatch_reason: null }).text).toBe('Key not yet fetched by a phone');
    expect(keyStatus({ last_issued_at: 'x', last_issued_version: 1, key_version: 2, mismatch_reason: null }).text).toBe('Phone has not fetched the new key');
    expect(keyStatus({ last_issued_at: 'x', last_issued_version: 2, key_version: 2, mismatch_reason: null })).toEqual({ text: 'Key issued', tone: 'good' });
  });
});

describe('ReturnMachines panel', () => {
  it('lists a machine with its key bookkeeping and shows no key anywhere on the page', async () => {
    mockApi([machine()]);
    const { container } = render(<ReturnMachines />);
    expect(await screen.findByText('TOMS-RM-3C48')).toBeInTheDocument();
    expect(screen.getByText('Key issued')).toBeInTheDocument();
    expect(screen.getByText('dev-1', { exact: false })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/[A-Za-z0-9+/]{43}=/);
  });

  it('shows the mismatch flag with a plain explanation, in a bordered alert', async () => {
    mockApi([machine({ mismatch_since: '2026-11-16T03:00:00.000Z', mismatch_reason: 'machine_has_other_key' })]);
    render(<ReturnMachines />);
    expect(await screen.findByText('Key mismatch')).toBeInTheDocument();
    expect(screen.getByRole('alert').textContent).toContain('keeps a different key');
  });

  it('flags a rotation that the machine cannot take', async () => {
    mockApi([machine({ mismatch_since: '2026-11-16T03:00:00.000Z', mismatch_reason: 'rotation_needs_reflash' })]);
    render(<ReturnMachines />);
    expect((await screen.findByRole('alert')).textContent).toContain('re-flashed');
  });

  it('rotation asks first, then posts, then reloads', async () => {
    mockApi([machine()]);
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    render(<ReturnMachines />);
    fireEvent.click(await screen.findByText('Rotate key'));
    expect(post).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByText('Rotate key')[0]);
    await waitFor(() => expect(post).toHaveBeenCalledWith('/return-machines/TOMS-RM-3C48/rotate'));
  });

  it('registers a machine for a vehicle that has none, offering only free vehicles', async () => {
    mockApi([machine({ vehicle_id: 'v1' })]);
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} });
    render(<ReturnMachines />);
    fireEvent.click(await screen.findByText('Register machine'));
    const select = screen.getByRole('combobox') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['', 'v2']);
    fireEvent.change(select, { target: { value: 'v2' } });
    fireEvent.change(screen.getByLabelText(/Machine id/), { target: { value: 'TOMS-RM-9999' } });
    fireEvent.click(screen.getByText('Register'));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/return-machines', { machine_id: 'TOMS-RM-9999', vehicle_id: 'v2' }));
  });

  it('says so when nothing is registered', async () => {
    mockApi([]);
    render(<ReturnMachines />);
    expect(await screen.findByText(/No Return Machine is registered yet/)).toBeInTheDocument();
  });
});
