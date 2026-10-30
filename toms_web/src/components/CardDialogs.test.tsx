import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import { confirmationFor } from '../lib/cardLifecycle';
import CardActionDialog from './CardActionDialog';
import CardEditDialog from './CardEditDialog';
import CardHistoryDrawer from './CardHistoryDrawer';
import CardRowMenu from './CardRowMenu';

afterEach(() => vi.restoreAllMocks());

describe('CardRowMenu', () => {
  const open = async (status: Parameters<typeof CardRowMenu>[0]['status'], hasChip = true) => {
    const onAction = vi.fn();
    render(<CardRowMenu serial="TOMS-0001" status={status} hasChip={hasChip} onAction={onAction} />);
    await userEvent.click(screen.getByRole('button', { name: 'Actions for TOMS-0001' }));
    return onAction;
  };
  const items = () => screen.getAllByRole('menuitem').map((i) => i.textContent);

  it('lists edit, history, and only the moves an active card can make', async () => {
    await open('active');
    expect(items()).toEqual(['Edit label and notes', 'History', 'Replace chip', 'Suspend', 'Mark lost', 'Retire', 'Take chip off']);
  });

  it('offers "Found it" on a lost card and "Restore" on a retired one', async () => {
    await open('lost');
    expect(items()).toContain('Found it');
    expect(items()).not.toContain('Suspend');
  });

  it('offers no unpair for a card that has no chip', async () => {
    await open('unpaired', false);
    expect(items()).toEqual(['Edit label and notes', 'History', 'Retire']);
  });

  it('reports the action and the status it leads to, then closes', async () => {
    const onAction = await open('active');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark lost' }));
    expect(onAction).toHaveBeenCalledWith('lost', 'lost');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('closes on Escape', async () => {
    await open('active');
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});

describe('CardActionDialog', () => {
  const lost = confirmationFor('lost', 'TOMS-0001')!;
  const suspend = confirmationFor('suspend', 'TOMS-0001')!;

  it('will not go ahead without a reason when one is required', async () => {
    const run = vi.fn().mockResolvedValue({});
    render(<CardActionDialog confirmation={lost} run={run} onDone={vi.fn()} onClose={vi.fn()} />);
    const go = screen.getByRole('button', { name: 'Mark lost' });
    expect(go).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), '   ');
    expect(go).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'Left on the bus');
    expect(go).toBeEnabled();
  });

  it('sends the trimmed reason and no confirmation the first time', async () => {
    const run = vi.fn().mockResolvedValue({});
    const onDone = vi.fn();
    render(<CardActionDialog confirmation={lost} run={run} onDone={onDone} onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Reason'), '  Left on the bus  ');
    await userEvent.click(screen.getByRole('button', { name: 'Mark lost' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(run).toHaveBeenCalledWith('Left on the bus', false);
  });

  it('needs no reason box for a suspend', async () => {
    const run = vi.fn().mockResolvedValue({});
    render(<CardActionDialog confirmation={suspend} run={run} onDone={vi.fn()} onClose={vi.fn()} />);
    expect(screen.queryByLabelText('Reason')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await waitFor(() => expect(run).toHaveBeenCalledWith('', false));
  });

  it('shows the open-trip warning, then goes ahead only after a second click', async () => {
    const run = vi
      .fn()
      .mockRejectedValueOnce({ response: { status: 409, data: { error: 'open', open_trips: 2 } } })
      .mockResolvedValueOnce({});
    const onDone = vi.fn();
    render(<CardActionDialog confirmation={suspend} run={run} onDone={onDone} onClose={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('2 open trips');
    expect(onDone).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Suspend anyway' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(run).toHaveBeenLastCalledWith('', true);
  });

  it('says "1 open trip" in the singular', async () => {
    const run = vi.fn().mockRejectedValue({ response: { status: 409, data: { open_trips: 1 } } });
    render(<CardActionDialog confirmation={suspend} run={run} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/1 open trip,/);
  });

  it('shows the server message for any other refusal and stays open', async () => {
    const run = vi.fn().mockRejectedValue({ response: { status: 409, data: { error: 'A lost card cannot be changed to suspended.' } } });
    const onDone = vi.fn();
    render(<CardActionDialog confirmation={suspend} run={run} onDone={onDone} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A lost card cannot be changed to suspended.');
    expect(onDone).not.toHaveBeenCalled();
  });

  it('cancels without running anything', async () => {
    const run = vi.fn();
    const onClose = vi.fn();
    render(<CardActionDialog confirmation={suspend} run={run} onDone={vi.fn()} onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(run).not.toHaveBeenCalled();
  });
});

describe('CardEditDialog', () => {
  const card = { card_uuid: 'u1', serial: 'TOMS-0001', label: 'Seat 5', notes: 'old' };

  it('keeps Save off until something changes', async () => {
    render(<CardEditDialog card={card} onDone={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Label'), '!');
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('sends null for a box that was emptied, so the server clears it', async () => {
    const patch = vi.spyOn(api, 'patch').mockResolvedValue({ data: {} });
    const onDone = vi.fn();
    render(<CardEditDialog card={card} onDone={onDone} onClose={vi.fn()} />);
    await userEvent.clear(screen.getByLabelText('Notes'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(patch).toHaveBeenCalledWith('/cards/u1', { label: 'Seat 5', notes: null });
  });

  it('shows a save error', async () => {
    vi.spyOn(api, 'patch').mockRejectedValue({ response: { data: { error: 'Invalid update payload' } } });
    render(<CardEditDialog card={card} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Label'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid update payload');
  });
});

describe('CardHistoryDrawer', () => {
  const ev = (id: number, over: object) => ({
    id, action: 'status_changed', from_status: 'active', to_status: 'lost', from_nfc_uid: null, to_nfc_uid: null,
    reason: null, admin_username: 'boss', details: {}, at: '2026-10-30T08:00:00Z', ...over,
  });

  it('lists events with who, when and why', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: { events: [ev(2, { reason: 'Left on the bus' }), ev(1, { action: 'enrolled', from_status: null, to_status: 'active', to_nfc_uid: '04AA' })] } });
    render(<CardHistoryDrawer card={{ card_uuid: 'u1', serial: 'TOMS-0001' }} onClose={vi.fn()} />);
    const rows = await screen.findAllByTestId('history-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Status active to lost');
    expect(rows[0]).toHaveTextContent('Reason: Left on the bus');
    expect(rows[0]).toHaveTextContent('boss');
    expect(rows[1]).toHaveTextContent('Enrolled with chip 04AA');
  });

  it('explains an empty history', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: { events: [] } });
    render(<CardHistoryDrawer card={{ card_uuid: 'u1', serial: 'TOMS-0001' }} onClose={vi.fn()} />);
    expect(await screen.findByText(/Nothing recorded yet/)).toBeInTheDocument();
  });

  it('shows an error when it cannot load', async () => {
    vi.spyOn(api, 'get').mockRejectedValue({ response: { status: 500 } });
    render(<CardHistoryDrawer card={{ card_uuid: 'u1', serial: 'TOMS-0001' }} onClose={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the history');
  });

  it('asks for the history of the right card', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: { events: [] } });
    render(<CardHistoryDrawer card={{ card_uuid: 'abc-123', serial: 'X' }} onClose={vi.fn()} />);
    await screen.findByText(/Nothing recorded yet/);
    expect(get).toHaveBeenCalledWith('/cards/abc-123/history');
  });
});
