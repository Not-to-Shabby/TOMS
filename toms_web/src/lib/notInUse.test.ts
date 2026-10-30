import { describe, expect, it } from 'vitest';
import { notInUseMessage } from './notInUse';

describe('notInUseMessage', () => {
  it('tells a finder what to do with a lost card', () => {
    expect(notInUseMessage('lost')).toMatch(/reported lost/);
    expect(notInUseMessage('lost')).toMatch(/hand it to a conductor/);
  });

  it('has plain words for retired and suspended cards', () => {
    expect(notInUseMessage('retired')).toMatch(/retired/);
    expect(notInUseMessage('suspended')).toMatch(/temporarily out of use/);
  });

  it('says nothing for an active card or an unknown value', () => {
    expect(notInUseMessage('active')).toBeNull();
    expect(notInUseMessage(null)).toBeNull();
    expect(notInUseMessage(undefined)).toBeNull();
    expect(notInUseMessage('')).toBeNull();
  });
});
