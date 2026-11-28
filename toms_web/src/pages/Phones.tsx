import { useCallback, useEffect, useEffectEvent, useState } from 'react';
import { AlertTriangle, Plus, RefreshCw, Smartphone } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { api } from '../lib/api';
import LoadError from '../components/LoadError';
import Modal from '../components/Modal';
import { lastUsedText } from '../lib/format';
import { loadErrorMessage } from '../lib/loadError';
import { APP_ENROL_SCREEN_READY, CODE_STATUS, POLL_MILLIS, endedFrom, formatCountdown, type Ended, type Phone, type PairingCodeRow } from '../lib/pairing';

interface Vehicle {
  id: string;
  plate_number: string;
}

/** The answer to issuing a code. Kept in this page's memory only, until it ends, and never written anywhere. */
interface IssuedCode {
  id: number;
  code: string;
  vehicle_id: string;
  label: string | null;
  qr_payload: string | null;
  /** When it stops working, by THIS computer's clock (now + expires_in_seconds), so a wrong clock cannot show a dead code as live. */
  deadline: number;
}

const TONE_COLOR = { bad: 'var(--danger)', warn: 'var(--warning)', good: 'var(--success)', neutral: 'var(--text-secondary)' } as const;

function when(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'never';
}

/** The server's own words when it gave a reason (a full list of open codes, say), otherwise a plain fallback. */
function actionMessage(err: unknown, fallback: string): string {
  const e = err as { response?: { status?: number; data?: { error?: string } } };
  if (e?.response?.status === 401) return 'Your session ended. Sign in again.';
  return e?.response?.data?.error ?? fallback;
}

const inputStyle = { padding: '8px', borderRadius: '8px', background: 'var(--bg-dark)', color: 'var(--text-primary)', border: '1px solid var(--border)' } as const;
const quietButton = { padding: '8px 16px', borderRadius: '8px', background: 'transparent', color: 'var(--text-secondary)', border: '1px solid var(--border)', cursor: 'pointer' } as const;

/** `appEnrolReady` defaults to the flag in lib/pairing.ts; a test can pass it to see the page as it will read once the app has its screen. */
export default function Phones({ appEnrolReady = APP_ENROL_SCREEN_READY }: { appEnrolReady?: boolean } = {}) {
  const [phones, setPhones] = useState<Phone[]>([]);
  const [codes, setCodes] = useState<PairingCodeRow[]>([]);
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [form, setForm] = useState({ vehicle_id: '', label: '' });
  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<IssuedCode | null>(null);
  const [ended, setEnded] = useState<Ended | null>(null);
  const [revoking, setRevoking] = useState<Phone | null>(null);

  const load = useCallback(() => {
    return Promise.all([api.get('/devices'), api.get('/pairing-codes'), api.get('/vehicles')])
      .then(([d, c, v]) => {
        setLoadError(null);
        setPhones(d.data);
        setCodes(c.data);
        setVehicles(v.data);
        setLoading(false);
      })
      .catch((err) => {
        setLoadError(loadErrorMessage(err, 'the phones'));
        setLoading(false);
      });
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // While a code is on screen, watch the list: when a phone uses it (or it locks or is cancelled elsewhere) the
  // code leaves the screen at once, and the page says which phone used it.
  useEffect(() => {
    if (!issued) return;
    let stopped = false;
    const watching = issued;
    const timer = setInterval(async () => {
      try {
        const [c, d] = await Promise.all([api.get<PairingCodeRow[]>('/pairing-codes'), api.get<Phone[]>('/devices')]);
        if (stopped) return;
        setCodes(c.data);
        setPhones(d.data);
        const row = c.data.find((r) => r.id === watching.id);
        if (row && row.status !== 'open') {
          setIssued(null);
          setEnded(endedFrom(row));
        }
      } catch {
        // Keep watching: the next tick tries again, and the countdown ends the code in any case.
      }
    }, POLL_MILLIS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [issued]);

  const issue = async () => {
    setActionError(null);
    setEnded(null);
    setIssuing(true);
    try {
      const body: { vehicle_id: string; label?: string } = { vehicle_id: form.vehicle_id };
      if (form.label.trim()) body.label = form.label.trim();
      const res = await api.post('/pairing-codes', body);
      setIssued({
        id: res.data.id,
        code: res.data.code,
        vehicle_id: res.data.vehicle_id,
        label: res.data.label ?? null,
        qr_payload: res.data.qr_payload ?? null,
        deadline: Date.now() + Number(res.data.expires_in_seconds) * 1000,
      });
      setForm({ vehicle_id: form.vehicle_id, label: '' });
      void load();
    } catch (err) {
      setActionError(actionMessage(err, 'Could not issue a code. Try again.'));
    } finally {
      setIssuing(false);
    }
  };

  const cancelCode = async (id: number) => {
    setActionError(null);
    try {
      await api.delete(`/pairing-codes/${id}`);
      if (issued?.id === id) {
        setIssued(null);
        setEnded({ kind: 'cancelled' });
      }
    } catch (err) {
      setActionError(actionMessage(err, 'Could not cancel that code.'));
    }
    void load();
  };

  /** The countdown ended. The code leaves the screen at once; what to say about it comes from the server's record. */
  const settle = async (watching: IssuedCode) => {
    setIssued(null);
    let row: PairingCodeRow | undefined;
    try {
      const c = await api.get<PairingCodeRow[]>('/pairing-codes');
      setCodes(c.data);
      row = c.data.find((r) => r.id === watching.id);
    } catch {
      // The note falls back to "expired", which is also what the countdown says.
    }
    setEnded(endedFrom(row));
    void load();
  };

  const phoneById = new Map(phones.map((p) => [p.device_id, p]));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', overflowY: 'auto', paddingRight: '4px' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '16px' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0 }}>Phones</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>
            Enrol a conductor phone with a one-time code. The phone sends its own Device ID and gets its token directly, so nobody
            reads out an ID or carries a token. A code works once and lasts 10 minutes.
          </div>
        </div>
        <button onClick={() => void load()} aria-label="Refresh" style={{ ...quietButton, padding: '8px 12px' }}>
          <RefreshCw size={16} />
        </button>
      </header>

      {!appEnrolReady && (
        <div role="note" className="glass-panel" style={{ padding: '14px 20px', display: 'flex', gap: '10px', alignItems: 'flex-start', borderColor: 'var(--warning)', color: 'var(--warning)' }}>
          <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span>
            The conductor app does not have its enrolment screen yet, so a code issued here cannot be used from a phone. Until
            it does, enrol a phone with <code>node scripts/setup-pilot.mjs --server &lt;address&gt; enrol-device &lt;device-id&gt; &lt;vehicle-id&gt;</code>.
            Everything else on this page already works: the phone list, revoking, and the code list.
          </span>
        </div>
      )}

      <LoadError message={loadError} onRetry={() => void load()} />
      {actionError && <div role="alert" style={{ color: 'var(--danger)' }}>{actionError}</div>}

      <section className="glass-panel" style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
        <h3 style={{ margin: 0 }}>Add a phone</h3>
        {vehicles.length === 0 && !loading ? (
          <div style={{ color: 'var(--text-secondary)' }}>There is no vehicle yet. Add one in Fleet Manager first: a code is issued for one vehicle.</div>
        ) : (
          <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              Vehicle
              <select value={form.vehicle_id} onChange={(e) => setForm({ ...form, vehicle_id: e.target.value })} style={inputStyle}>
                <option value="">Choose a vehicle</option>
                {vehicles.map((v) => (
                  <option key={v.id} value={v.id}>{v.id} ({v.plate_number})</option>
                ))}
              </select>
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              Name for this phone (optional)
              <input value={form.label} maxLength={100} onChange={(e) => setForm({ ...form, label: e.target.value })} style={inputStyle} />
            </label>
            <button
              onClick={() => void issue()}
              disabled={!form.vehicle_id || issuing}
              style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 16px', borderRadius: '8px', background: 'var(--accent)', color: '#000', border: 'none', fontWeight: 700, cursor: 'pointer', opacity: !form.vehicle_id || issuing ? 0.5 : 1 }}
            >
              <Plus size={16} /> {issuing ? 'Issuing…' : 'Add phone'}
            </button>
          </div>
        )}
      </section>

      {issued && <IssuedCodePanel issued={issued} appEnrolReady={appEnrolReady} onExpired={() => void settle(issued)} onCancel={() => void cancelCode(issued.id)} />}

      {ended && (
        <div role="status" className="glass-panel" style={{ padding: '16px 20px', borderColor: ended.kind === 'used' ? 'var(--success)' : 'var(--border)' }}>
          {ended.kind === 'used' ? (
            <>
              <strong>Paired: phone {ended.deviceId} is now enrolled on {ended.vehicleId}.</strong>{' '}
              Check that this is the phone you meant: its Device ID is shown on the phone at More › Server Sync. If it is not, revoke it below.
            </>
          ) : ended.kind === 'locked' ? (
            <>This code was locked after too many wrong tries and no longer works. Issue a new one if the phone still needs enrolling.</>
          ) : ended.kind === 'cancelled' ? (
            <>This code was cancelled and no longer works.</>
          ) : (
            <>This code expired and no longer works. Issue a new one if the phone still needs enrolling.</>
          )}
        </div>
      )}

      <section className="glass-panel" style={{ padding: '24px' }}>
        <h3 style={{ margin: '0 0 12px' }}>Codes</h3>
        {loading ? (
          <div style={{ color: 'var(--text-secondary)' }}>Loading…</div>
        ) : codes.length === 0 ? (
          <div style={{ color: 'var(--text-secondary)' }}>No code has been issued yet.</div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '14px' }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--text-secondary)' }}>
                <th style={{ padding: '8px' }}>Code</th>
                <th style={{ padding: '8px' }}>Vehicle</th>
                <th style={{ padding: '8px' }}>Issued</th>
                <th style={{ padding: '8px' }}>Status</th>
                <th style={{ padding: '8px' }}>Used by</th>
                <th style={{ padding: '8px' }} />
              </tr>
            </thead>
            <tbody>
              {codes.map((c) => {
                const status = CODE_STATUS[c.status];
                const user = c.used_device_id ? phoneById.get(c.used_device_id) : undefined;
                return (
                  <tr key={c.id} style={{ borderTop: '1px solid var(--border)', verticalAlign: 'top' }}>
                    <td style={{ padding: '8px', fontFamily: 'monospace' }}>{c.code_id}-····-····</td>
                    <td style={{ padding: '8px' }}>{c.vehicle_id}{c.label ? ` · ${c.label}` : ''}</td>
                    <td style={{ padding: '8px' }}>{when(c.created_at)}<div style={{ color: 'var(--text-secondary)', fontSize: '12px' }}>by {c.created_by}</div></td>
                    <td style={{ padding: '8px', color: TONE_COLOR[status.tone], fontWeight: 700 }}>
                      {status.text}
                      {c.status === 'locked' && <div style={{ fontWeight: 400, fontSize: '12px' }}>{c.attempts} wrong tries</div>}
                      {c.status === 'open' && <div style={{ fontWeight: 400, fontSize: '12px', color: 'var(--text-secondary)' }}>until {when(c.expires_at)}</div>}
                    </td>
                    <td style={{ padding: '8px' }}>
                      {c.used_device_id ? (
                        <>
                          <div style={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>{c.used_device_id}</div>
                          {user?.label && <div style={{ fontSize: '12px' }}>{user.label}</div>}
                          <div style={{ color: 'var(--text-secondary)', fontSize: '12px' }}>
                            {user?.revoked ? 'Revoked.' : 'Check this is the phone you meant (its Device ID is on the phone at More › Server Sync).'}
                          </div>
                          {user && !user.revoked && (
                            <button onClick={() => setRevoking(user)} style={{ marginTop: '6px', padding: '4px 10px', borderRadius: '6px', background: 'transparent', color: 'var(--danger)', border: '1px solid var(--danger)', cursor: 'pointer' }}>
                              Revoke this phone
                            </button>
                          )}
                        </>
                      ) : (
                        <span style={{ color: 'var(--text-secondary)' }}>-</span>
                      )}
                    </td>
                    <td style={{ padding: '8px', textAlign: 'right' }}>
                      {c.status === 'open' && (
                        <button onClick={() => void cancelCode(c.id)} style={{ padding: '4px 10px', borderRadius: '6px', background: 'transparent', color: 'var(--text-secondary)', border: '1px solid var(--border)', cursor: 'pointer' }}>
                          Cancel code
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>

      <section className="glass-panel" style={{ padding: '24px' }}>
        <h3 style={{ margin: '0 0 12px', display: 'flex', alignItems: 'center', gap: '8px' }}><Smartphone size={18} /> Enrolled phones</h3>
        {loading ? (
          <div style={{ color: 'var(--text-secondary)' }}>Loading…</div>
        ) : phones.length === 0 ? (
          <div style={{ color: 'var(--text-secondary)' }}>No phone is enrolled yet.</div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '14px' }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--text-secondary)' }}>
                <th style={{ padding: '8px' }}>Device ID</th>
                <th style={{ padding: '8px' }}>Vehicle</th>
                <th style={{ padding: '8px' }}>Enrolled</th>
                <th style={{ padding: '8px' }}>Last upload</th>
                <th style={{ padding: '8px' }}>Status</th>
                <th style={{ padding: '8px' }} />
              </tr>
            </thead>
            <tbody>
              {phones.map((p) => (
                <tr key={p.device_id} style={{ borderTop: '1px solid var(--border)', verticalAlign: 'top', opacity: p.revoked ? 0.6 : 1 }}>
                  <td style={{ padding: '8px', fontFamily: 'monospace', overflowWrap: 'anywhere' }}>
                    {p.device_id}
                    {p.label && <div style={{ fontFamily: 'inherit', fontSize: '12px', color: 'var(--text-secondary)' }}>{p.label}</div>}
                  </td>
                  <td style={{ padding: '8px' }}>{p.vehicle_id ?? '-'}</td>
                  <td style={{ padding: '8px' }}>{when(p.enrolled_at)}</td>
                  <td style={{ padding: '8px' }}>{p.last_seen ? lastUsedText(p.last_seen) : 'Not seen yet'}</td>
                  <td style={{ padding: '8px', fontWeight: 700, color: p.revoked ? 'var(--danger)' : 'var(--success)' }}>
                    {p.revoked ? 'Revoked' : 'Active'}
                    {p.revoked && <div style={{ fontWeight: 400, fontSize: '12px', color: 'var(--text-secondary)' }}>{when(p.revoked_at)}</div>}
                  </td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>
                    {!p.revoked && (
                      <button onClick={() => setRevoking(p)} style={{ padding: '4px 10px', borderRadius: '6px', background: 'transparent', color: 'var(--danger)', border: '1px solid var(--danger)', cursor: 'pointer' }}>
                        Revoke
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {revoking && (
        <RevokeDialog
          phone={revoking}
          onClose={() => setRevoking(null)}
          onDone={() => {
            setRevoking(null);
            void load();
          }}
        />
      )}
    </div>
  );
}

/**
 * The code, shown once. It lives in this component's memory only: it is not put in the address, in storage or in
 * a log, and it goes when the countdown ends, when a phone uses it, when it is cancelled, or when the page is left.
 */
function IssuedCodePanel({ issued, appEnrolReady, onExpired, onCancel }: { issued: IssuedCode; appEnrolReady: boolean; onExpired: () => void; onCancel: () => void }) {
  const [left, setLeft] = useState(() => (issued.deadline - Date.now()) / 1000);
  // Always the latest callback, without restarting the countdown when the page re-renders.
  const expire = useEffectEvent(onExpired);

  useEffect(() => {
    const tick = () => {
      const s = (issued.deadline - Date.now()) / 1000;
      setLeft(s);
      if (s <= 0) expire();
    };
    const timer = setInterval(tick, 1000);
    // A tab in the background slows its timers: look at the clock again as soon as it is shown.
    const onVisible = () => {
      if (document.visibilityState === 'visible') tick();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [issued]);

  return (
    <section className="glass-panel" aria-label="New pairing code" style={{ padding: '24px', display: 'flex', gap: '24px', flexWrap: 'wrap', alignItems: 'center', border: '1px solid var(--accent)' }}>
      {issued.qr_payload ? (
        <div style={{ background: '#fff', padding: '12px', borderRadius: '8px' }}>
          <QRCodeSVG value={issued.qr_payload} size={200} level="M" />
        </div>
      ) : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', flex: '1 1 260px' }}>
        <div style={{ color: 'var(--text-secondary)' }}>
          For {issued.vehicle_id}{issued.label ? ` · ${issued.label}` : ''}
        </div>
        <div aria-label="Pairing code" style={{ fontFamily: 'monospace', fontSize: '34px', fontWeight: 800, letterSpacing: '2px' }}>{issued.code}</div>
        <div role="timer" style={{ fontWeight: 700, color: left <= 60 ? 'var(--warning)' : 'var(--text-primary)' }}>
          Works for {formatCountdown(left)} more, and once only
        </div>
        <div style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>
          {!appEnrolReady
            ? 'The app cannot use this code yet (see the note at the top of the page).'
            : issued.qr_payload
              ? 'On the phone, choose Scan to enrol and scan this code, or type the code by hand.'
              : 'This server has no public address set, so there is no QR code. On the phone, type the code by hand.'}
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', fontSize: '14px', color: 'var(--warning)' }}>
          <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span>Anyone who sees this code before the phone uses it can enrol a phone of their own. Show it only to the person holding the phone, and cancel it if you change your mind. It cannot be shown again.</span>
        </div>
        <div>
          <button onClick={onCancel} style={quietButton}>Cancel this code</button>
        </div>
      </div>
    </section>
  );
}

/** Revoking cannot be undone, so it never happens on the first click: this dialog names the phone and asks. */
function RevokeDialog({ phone, onClose, onDone }: { phone: Phone; onClose: () => void; onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.delete(`/devices/${encodeURIComponent(phone.device_id)}`);
      onDone();
    } catch (err) {
      setError(actionMessage(err, 'Could not revoke that phone. Try again.'));
      setBusy(false);
    }
  };

  return (
    <Modal title="Revoke this phone?" onClose={onClose}>
      <p style={{ margin: '0 0 8px', fontSize: 14 }}>
        <strong style={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>{phone.device_id}</strong>
        {phone.label ? ` (${phone.label})` : ''}
        {phone.vehicle_id ? `, on vehicle ${phone.vehicle_id}` : ''}
      </p>
      <p style={{ margin: '0 0 8px', fontSize: 14, color: 'var(--text-secondary)' }}>
        Last upload: {phone.last_seen ? lastUsedText(phone.last_seen) : 'not seen yet'}.
      </p>
      <p style={{ margin: '0 0 14px', fontSize: 14, color: 'var(--warning)' }}>
        This cannot be undone. The phone stops uploading as soon as you confirm, and events it has not uploaded yet will be
        refused. A revoked Device ID can never be enrolled again, so a phone that has to be used again needs a new Device ID.
      </p>
      {error && <div role="alert" style={{ color: 'var(--danger)', fontSize: 13, marginBottom: 12 }}>{error}</div>}
      <div style={{ display: 'flex', gap: 10 }}>
        <button type="button" onClick={onClose} style={{ ...quietButton, flex: 1 }}>Cancel</button>
        <button
          type="button"
          onClick={() => void confirm()}
          disabled={busy}
          style={{ flex: 1, padding: 10, borderRadius: 6, border: 'none', fontWeight: 700, background: 'var(--danger)', color: '#fff', cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? 0.5 : 1 }}
        >
          {busy ? 'Working…' : 'Revoke phone'}
        </button>
      </div>
    </Modal>
  );
}
