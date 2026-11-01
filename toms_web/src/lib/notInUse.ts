/** What a passenger is told when they scan a card that has been taken out of service. */
export function notInUseMessage(state: string | null | undefined): string | null {
  switch (state) {
    case 'lost':
      return 'This card has been reported lost and is no longer in use. If you found it, please hand it to a conductor or the operator.';
    case 'retired':
      return 'This card has been retired and is no longer in use.';
    case 'suspended':
      return 'This card is temporarily out of use. Please ask a conductor for help.';
    default:
      return null;
  }
}
