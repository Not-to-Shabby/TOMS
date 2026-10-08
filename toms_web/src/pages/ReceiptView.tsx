import { useState, useEffect } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { ShieldCheck, CheckCircle2, Clock, Bus, MapPin, ChevronDown, ChevronUp, AlertTriangle } from 'lucide-react';
import axios from 'axios';
import { API_BASE } from '../lib/api';

interface PassengerLine {
  categoryId: string | null;
  count: number;
  perPersonCentavos: number;
  fareCentavos: number;
  discountCentavos: number;
}

interface ReceiptData {
  receipt_token: string;
  trip_id: string;
  status: 'PAID' | 'UNPAID';
  card_state: string;
  boarding_stop: string;
  destination_stop: string;
  declared_destination: string;
  is_overridden: boolean;
  override_reason: string | null;
  fare_pesos: string;
  discount_pesos: string;
  passenger_count: number;
  passengers: PassengerLine[];
  vehicle: { id: string; plate: string };
  route_name: string;
  conductor_name: string;
  timestamp: string;
  ltfrb: {
    order_reference: string;
    document_url: string | null;
    base_fare: number;
    per_km_fare: number;
    discounts: Record<string, number>;
  };
}

export default function ReceiptView() {
  const { token, cardUuid } = useParams<{ token?: string; cardUuid?: string }>();
  const [searchParams] = useSearchParams();
  const [receipt, setReceipt] = useState<ReceiptData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showLtfrbDoc, setShowLtfrbDoc] = useState(false);

  useEffect(() => {
    resolveAndLoad();
  }, [token, cardUuid]);

  async function resolveAndLoad() {
    try {
      setLoading(true);
      setError(null);

      let targetToken = token;

      // If accessed via /r/:cardUuid
      if (!targetToken && cardUuid) {
        const resolveRes = await axios.get(`${API_BASE}/api/r/${cardUuid}`);
        targetToken = resolveRes.data.receipt_token;
      }

      if (!targetToken) {
        const inactiveCard = searchParams.get('card');
        if (inactiveCard) {
          setError('This passenger card is in circulation, but has no active trip recorded right now.');
        } else {
          setError('Receipt not found or link has expired.');
        }
        return;
      }

      const res = await axios.get(`${API_BASE}/api/receipt/${targetToken}`);
      setReceipt(res.data);
    } catch (err: any) {
      if (err.response?.status === 404) {
        setError('No active trip found for this card right now. Receipts become active once your boarding is recorded.');
      } else {
        setError('Could not load receipt. Please check your internet connection.');
      }
    } finally {
      setLoading(false);
    }
  }

  if (loading) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100vh', background: '#0a0d14', color: '#fff', padding: '20px' }}>
        <div>Loading official e-Receipt...</div>
      </div>
    );
  }

  if (error || !receipt) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100vh', background: '#0a0d14', color: '#fff', padding: '20px' }}>
        <div className="glass-panel" style={{ width: '100%', maxWidth: '420px', padding: '32px', textAlign: 'center' }}>
          <AlertTriangle size={48} color="var(--warning, #f59e0b)" style={{ margin: '0 auto 16px auto' }} />
          <h2 style={{ fontSize: '20px', margin: '0 0 12px 0' }}>Notice</h2>
          <p style={{ color: 'var(--text-secondary, #94a3b8)', fontSize: '14px', lineHeight: 1.5, margin: 0 }}>
            {error || 'Receipt unavailable.'}
          </p>
        </div>
      </div>
    );
  }

  const isPaid = receipt.status === 'PAID';
  const ltfrbDocUrl = receipt.ltfrb.document_url ? `${API_BASE}${receipt.ltfrb.document_url}` : null;

  return (
    <div style={{ minHeight: '100vh', background: '#0a0d14', color: '#fff', padding: '24px 16px', display: 'flex', justifyContent: 'center' }}>
      <div style={{ width: '100%', maxWidth: '440px', display: 'flex', flexDirection: 'column', gap: '20px' }}>
        
        {/* Receipt Card */}
        <div style={{
          background: '#ffffff',
          color: '#111418',
          borderRadius: '16px',
          padding: '24px',
          boxShadow: '0 12px 32px rgba(0,0,0,0.4)',
          position: 'relative',
        }}>
          {/* Top Brand Banner */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '2px dashed #e2e8f0', paddingBottom: '16px', marginBottom: '16px' }}>
            <div>
              <div style={{ fontSize: '20px', fontWeight: 900, color: '#0B3D91', letterSpacing: '1px' }}>TOMS</div>
              <div style={{ fontSize: '11px', color: '#64748b' }}>Transportation Occupancy System</div>
              <div style={{ fontSize: '13px', fontWeight: 700, marginTop: '4px' }}>Official Passenger e-Receipt</div>
            </div>
            <div style={{
              padding: '6px 12px',
              borderRadius: '20px',
              background: isPaid ? '#dcfce7' : '#fef3c7',
              color: isPaid ? '#15803d' : '#b45309',
              fontWeight: 800,
              fontSize: '12px',
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
            }}>
              {isPaid ? <CheckCircle2 size={14} /> : <Clock size={14} />}
              <span>{receipt.status}</span>
            </div>
          </div>

          {/* Route & Vehicle Info */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '16px', fontSize: '13px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontWeight: 600 }}>
              <Bus size={15} color="#0B3D91" />
              <span>{receipt.route_name}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: '#64748b' }}>
              <span>Vehicle Plate:</span>
              <span style={{ fontWeight: 700, color: '#111418' }}>{receipt.vehicle.plate}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: '#64748b' }}>
              <span>Conductor:</span>
              <span>{receipt.conductor_name}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', color: '#64748b' }}>
              <span>Issued At:</span>
              <span>{new Date(receipt.timestamp).toLocaleString()}</span>
            </div>
          </div>

          {/* Trip Stops */}
          <div style={{ background: '#f8fafc', padding: '14px', borderRadius: '10px', marginBottom: '16px', border: '1px solid #e2e8f0' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px' }}>
              <MapPin size={18} color="#0B3D91" style={{ marginTop: '2px', flexShrink: 0 }} />
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
                <div>
                  <span style={{ color: '#64748b', fontSize: '11px', textTransform: 'uppercase' }}>Boarding</span>
                  <div style={{ fontWeight: 700 }}>{receipt.boarding_stop}</div>
                </div>
                <div style={{ height: '1px', background: '#e2e8f0', margin: '2px 0' }} />
                <div>
                  <span style={{ color: '#64748b', fontSize: '11px', textTransform: 'uppercase' }}>Destination</span>
                  <div style={{ fontWeight: 700 }}>{receipt.destination_stop}</div>
                  {receipt.is_overridden && (
                    <div style={{ fontSize: '11px', color: '#b45309' }}>
                      (Overridden from declared: {receipt.declared_destination})
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Passenger & Fare Breakdown */}
          <div style={{ marginBottom: '16px', fontSize: '13px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: 600, marginBottom: '6px' }}>
              <span>Passengers:</span>
              <span>{receipt.passenger_count} {receipt.passenger_count === 1 ? 'person' : 'people'}</span>
            </div>

            {receipt.passengers.length > 0 && (
              <div style={{ background: '#f1f5f9', borderRadius: '6px', padding: '8px 12px', marginBottom: '12px', fontSize: '12px' }}>
                {receipt.passengers.map((p, idx) => (
                  <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', padding: '2px 0' }}>
                    <span style={{ textTransform: 'capitalize' }}>
                      {p.count}× {p.categoryId || 'Regular'} (₱{(p.perPersonCentavos / 100).toFixed(2)})
                    </span>
                    <span style={{ fontWeight: 600 }}>₱{(p.fareCentavos / 100).toFixed(2)}</span>
                  </div>
                ))}
              </div>
            )}

            {Number(receipt.discount_pesos) > 0 && (
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#15803d', marginBottom: '4px' }}>
                <span>Discount Applied:</span>
                <span>-₱{receipt.discount_pesos}</span>
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '18px', fontWeight: 900, marginTop: '8px', borderTop: '2px solid #111418', paddingTop: '8px' }}>
              <span>Total Fare:</span>
              <span style={{ color: '#0B3D91' }}>₱{receipt.fare_pesos}</span>
            </div>
          </div>

          {/* Security Reference */}
          <div style={{ fontSize: '10px', color: '#94a3b8', textAlign: 'center', fontFamily: 'monospace' }}>
            Receipt Token: {receipt.receipt_token.slice(0, 18)}...
          </div>
        </div>

        {/* Official LTFRB Compliance Accordion */}
        <div className="glass-panel" style={{ padding: '16px 20px', borderRadius: '12px' }}>
          <button
            onClick={() => setShowLtfrbDoc(!showLtfrbDoc)}
            style={{
              width: '100%',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              background: 'transparent',
              border: 'none',
              color: 'var(--text-primary)',
              cursor: 'pointer',
              padding: '0',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 700, fontSize: '14px' }}>
              <ShieldCheck size={18} color="var(--success)" />
              <span>LTFRB Verified Fare Matrix</span>
            </div>
            {showLtfrbDoc ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
          </button>

          {showLtfrbDoc && (
            <div style={{ marginTop: '16px', display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '13px' }}>
              <div style={{ color: 'var(--text-secondary)' }}>
                Government Reference: <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{receipt.ltfrb.order_reference}</span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                <div>Base Fare: ₱{receipt.ltfrb.base_fare.toFixed(2)} (first 4km)</div>
                <div>Per Km: ₱{receipt.ltfrb.per_km_fare.toFixed(2)}</div>
              </div>

              {ltfrbDocUrl ? (
                <div style={{ marginTop: '8px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <div style={{ fontSize: '12px', fontWeight: 600 }}>Scanned Photocopy with Official Signature:</div>
                  <div style={{ background: '#000', borderRadius: '8px', overflow: 'hidden', border: '1px solid var(--border)', textAlign: 'center' }}>
                    {ltfrbDocUrl.endsWith('.pdf') ? (
                      <iframe src={ltfrbDocUrl} style={{ width: '100%', height: '320px', border: 'none' }} title="LTFRB Document" />
                    ) : (
                      <img src={ltfrbDocUrl} alt="LTFRB Scanned Document" style={{ width: '100%', maxHeight: '400px', objectFit: 'contain' }} />
                    )}
                  </div>
                  <a
                    href={ltfrbDocUrl}
                    target="_blank"
                    rel="noreferrer"
                    style={{ color: 'var(--accent)', fontSize: '12px', textAlign: 'center', textDecoration: 'none' }}
                  >
                    Open full resolution certificate
                  </a>
                </div>
              ) : (
                <div style={{ fontSize: '12px', color: 'var(--text-secondary)', padding: '8px 0' }}>
                  Electronic matrix recorded under LTFRB statutory schedule.
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer info */}
        <div style={{ textAlign: 'center', fontSize: '11px', color: 'var(--text-secondary)' }}>
          Powered by TOMS Transit · Offline-First Contactless Transport System
        </div>
      </div>
    </div>
  );
}
