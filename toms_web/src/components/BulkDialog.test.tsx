import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import BulkBar from './BulkBar';
import BulkDialog, { type BulkChoice, type BulkTarget } from './BulkDialog';

afterEach(() => vi.restoreAllMocks());

const target = (over: Partial<BulkTarget> = {}): BulkTarget => ({ ids: ['a', 'b', 'c'], count: 3, counts: { active: 3 }, ...over });
const lost: BulkChoice = { kind: 'status', status: 'lost', label: 'Mark lost' };
const suspend: BulkChoice = { kind: 'status', status: 'suspended', label: 'Suspend' };

describe('BulkDialog', () => {
  it('says what will happen and that it is all or nothing', () => {
    render(<BulkDialog choice={suspend} target={target()} onDone={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByRole('dialog', { name: 'Suspend 3 cards?' })).toBeInTheDocument();
    expect(screen.getByText(/3 cards \(3 active\)/)).toBeInTheDocument();
    expect(screen.getByText(/all or nothing/)).toBeInTheDocument();
  });

  it('uses the singular for one card', () => {
    render(<BulkDialog choice={suspend} target={target({ count: 1, ids: ['a'], counts: { active: 1 } })} onDone={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByRole('dialog', { name: 'Suspend 1 card?' })).toBeInTheDocument();
  });

  it('will not go ahead without a reason when marking lost', async () => {
    render(<BulkDialog choice={lost} target={target()} onDone={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Mark lost' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'Box lost');
    expect(screen.getByRole('button', { name: 'Mark lost' })).toBeEnabled();
  });

  it('sends the chosen cards, the status and the reason in one request', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { changed: 3 } });
    const onDone = vi.fn();
    render(<BulkDialog choice={lost} target={target()} onDone={onDone} onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Reason'), 'Box lost');
    await userEvent.click(screen.getByRole('button', { name: 'Mark lost' }));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(3));
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/cards/bulk', { card_uuids: ['a', 'b', 'c'], action: 'status', status: 'lost', reason: 'Box lost', confirm: undefined });
  });

  it('sends the registry filter instead of ids when everything that matches was selected', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { changed: 40 } });
    render(<BulkDialog choice={suspend} target={target({ ids: undefined, filter: { search: 'BB-', status: 'active' }, count: 40 })} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(post).toHaveBeenCalledWith('/cards/bulk', { filter: { search: 'BB-', status: 'active' }, action: 'status', status: 'suspended', reason: undefined, confirm: undefined });
  });

  it('moves cards to a vehicle, or to the spare pool with null', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { changed: 3 } });
    const toBus: BulkChoice = { kind: 'vehicle', vehicleId: 'BUS-1', label: 'Move to BUS-1' };
    const { unmount } = render(<BulkDialog choice={toBus} target={target()} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Move to BUS-1' }));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/cards/bulk', { card_uuids: ['a', 'b', 'c'], action: 'vehicle', vehicle_id: 'BUS-1', reason: undefined }));
    unmount();

    const toSpare: BulkChoice = { kind: 'vehicle', vehicleId: null, label: 'Move to spare pool' };
    render(<BulkDialog choice={toSpare} target={target()} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Move to spare pool' }));
    await waitFor(() => expect(post).toHaveBeenLastCalledWith('/cards/bulk', expect.objectContaining({ vehicle_id: null })));
  });

  it('lists the cards that blocked the change and stays open, since nothing was changed', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({
      response: {
        status: 409,
        data: {
          error: '2 of 3 cards could not be changed. Nothing was changed.',
          problems: [
            { card_uuid: 'b', serial: 'BK-2', error: 'A lost card cannot be changed to suspended.' },
            { card_uuid: 'c', serial: 'BK-3', error: 'A lost card cannot be changed to suspended.' },
          ],
        },
      },
    });
    const onDone = vi.fn();
    render(<BulkDialog choice={suspend} target={target()} onDone={onDone} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('2 of 3 cards could not be changed. Nothing was changed.');
    expect(alert).toHaveTextContent('BK-2: A lost card cannot be changed to suspended.');
    expect(alert).toHaveTextContent('BK-3');
    expect(onDone).not.toHaveBeenCalled();
  });

  it('caps a long list of problems and says how many more there are', async () => {
    const problems = Array.from({ length: 9 }, (_, i) => ({ card_uuid: `id${i}`, serial: `S-${i}`, error: 'no' }));
    vi.spyOn(api, 'post').mockRejectedValue({ response: { status: 409, data: { error: '9 of 9 cards could not be changed.', problems } } });
    render(<BulkDialog choice={suspend} target={target({ count: 9 })} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByText('and 4 more')).toBeInTheDocument();
  });

  it('warns about open trips across the group, then goes ahead with confirm on the second click', async () => {
    const post = vi
      .spyOn(api, 'post')
      .mockRejectedValueOnce({ response: { status: 409, data: { error: 'x', open_trips: 2, problems: [{ card_uuid: 'a', serial: 'A', error: 'open', open_trips: 2 }] } } })
      .mockResolvedValueOnce({ data: { changed: 3 } });
    const onDone = vi.fn();
    render(<BulkDialog choice={suspend} target={target()} onDone={onDone} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('2 open trips');
    expect(onDone).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Suspend anyway' }));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(3));
    expect(post).toHaveBeenLastCalledWith('/cards/bulk', expect.objectContaining({ confirm: true }));
  });

  it('shows a plain server error and a connection failure', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ response: { status: 400, data: { error: 'That matches more than 500 cards. Narrow the filter and try again.' } } });
    const { unmount } = render(<BulkDialog choice={suspend} target={target()} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('more than 500 cards');
    unmount();

    vi.spyOn(api, 'post').mockRejectedValue(new Error('Network Error'));
    render(<BulkDialog choice={suspend} target={target()} onDone={vi.fn()} onClose={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not make that change');
  });
});

describe('BulkBar', () => {
  const props = (over: object = {}): React.ComponentProps<typeof BulkBar> => ({
    count: 3, counts: { active: 3 }, moves: ['suspended', 'lost', 'retired'], vehicles: [{ id: 'BUS-1', plate_number: 'AAA-111' }],
    onChoose: vi.fn(), onReprint: vi.fn(), onClear: vi.fn(), ...over,
  });

  it('shows nothing when no card is selected', () => {
    const { container } = render(<BulkBar {...props({ count: 0 })} moves={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the count and what is selected, and offers only the shared moves', () => {
    render(<BulkBar {...props({ counts: { active: 2, lost: 1 } })} moves={['retired']} />);
    expect(screen.getByText('3 selected')).toBeInTheDocument();
    expect(screen.getByText('(2 active, 1 lost)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retire' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Suspend' })).not.toBeInTheDocument();
  });

  it('names a return to active "Activate" and a chipless restore "Restore"', () => {
    render(<BulkBar {...props()} moves={['active', 'unpaired']} />);
    expect(screen.getByRole('button', { name: 'Activate' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Restore' })).toBeInTheDocument();
  });

  it('reports the chosen status change, vehicle move, reprint and clear', async () => {
    const p = props();
    render(<BulkBar {...p} moves={['suspended']} />);
    await userEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    expect(p.onChoose).toHaveBeenLastCalledWith({ kind: 'status', status: 'suspended', label: 'Suspend' });

    await userEvent.selectOptions(screen.getByLabelText('Move to vehicle'), 'BUS-1');
    expect(p.onChoose).toHaveBeenLastCalledWith({ kind: 'vehicle', vehicleId: 'BUS-1', label: 'Move to BUS-1' });

    await userEvent.selectOptions(screen.getByLabelText('Move to vehicle'), '__spare__');
    expect(p.onChoose).toHaveBeenLastCalledWith({ kind: 'vehicle', vehicleId: null, label: 'Move to spare pool' });

    await userEvent.click(screen.getByRole('button', { name: /Reprint/ }));
    expect(p.onReprint).toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Clear selection' }));
    expect(p.onClear).toHaveBeenCalled();
  });

  it('offers to select everything that matches, only when it is given', async () => {
    const onSelect = vi.fn();
    const { rerender } = render(<BulkBar {...props()} allMatching={null} />);
    expect(screen.queryByRole('button', { name: /Select all/ })).not.toBeInTheDocument();
    rerender(<BulkBar {...props()} allMatching={{ total: 120, onSelect }} />);
    await userEvent.click(screen.getByRole('button', { name: 'Select all 120 that match' }));
    expect(onSelect).toHaveBeenCalled();
  });
});
