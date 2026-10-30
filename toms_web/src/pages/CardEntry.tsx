import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, tokenStore } from '../lib/api';
import PairPanel, { type PairingInfo } from '../components/PairPanel';
import ReceiptView from './ReceiptView';

/**
 * What /r/:cardUuid shows. Passengers get the receipt. A signed-in admin opening a card that has no
 * chip yet gets the pairing screen instead.
 */
export default function CardEntry() {
  const { cardUuid } = useParams<{ cardUuid: string }>();
  const signedIn = tokenStore.get() !== null;
  const [lookup, setLookup] = useState<{ uuid: string; info: PairingInfo | null } | null>(null);

  useEffect(() => {
    if (!cardUuid || !signedIn) return;
    let cancelled = false;
    api
      .get<PairingInfo>(`/cards/${cardUuid}/pairing`)
      .then((res) => !cancelled && setLookup({ uuid: cardUuid, info: res.data }))
      .catch(() => !cancelled && setLookup({ uuid: cardUuid, info: null }));
    return () => {
      cancelled = true;
    };
  }, [cardUuid, signedIn]);

  const checked = !signedIn || !cardUuid || lookup?.uuid === cardUuid;
  const info = lookup && lookup.uuid === cardUuid ? lookup.info : null;

  if (!checked) return <div style={{ padding: 40, textAlign: 'center' }}>Loading…</div>;
  if (info && info.status === 'unpaired') {
    return (
      <div style={{ maxWidth: 440, margin: '40px auto', padding: 24 }} className="glass-panel">
        <PairPanel info={info} />
      </div>
    );
  }
  return <ReceiptView />;
}
