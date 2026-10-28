import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import LoadError from './LoadError';
import { loadErrorMessage } from '../lib/loadError';

describe('loadErrorMessage', () => {
  it('says the session ended on a 401', () => {
    expect(loadErrorMessage({ response: { status: 401 } }, 'the fleet')).toMatch(/session ended/);
  });

  it('shows the server message when there is one', () => {
    expect(loadErrorMessage({ response: { status: 400, data: { error: 'bad filter' } } }, 'cards')).toBe('Could not load cards: bad filter');
  });

  it('mentions the status code for an error with no message', () => {
    expect(loadErrorMessage({ response: { status: 500 } }, 'cards')).toMatch(/\(500\)/);
  });

  it('points at the connection when nothing came back', () => {
    expect(loadErrorMessage(new Error('Network Error'), 'cards')).toMatch(/reachable/);
  });
});

describe('LoadError', () => {
  it('renders nothing without a message', () => {
    const { container } = render(<LoadError message={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the message and calls retry', async () => {
    const retry = vi.fn();
    render(<LoadError message="Could not load cards." onRetry={retry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load cards.');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledOnce();
  });
});
