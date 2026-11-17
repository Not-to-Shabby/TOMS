import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, KeyRound, Plus, RefreshCw } from 'lucide-react';
import { api } from '../lib/api';
import LoadError from '../components/LoadError';
import { loadErrorMessage } from '../lib/loadError';

/** What the server tells the dashboard about a machine. There is no key in it, and there never will be. */
export interface ReturnMachine {
  id: number;
  machine_id: string;
  vehicle_id: string;
  created_at: string;
  key_created_at: string;
  rotated_at: string | null;
  key_version: number;
  last_issued_at: string | null;
  last_issued_device: string | null;
  last_issued_version: number | null;
  mismatch_since: string | null;
  mismatch_reason: 'machine_has_other_key' | 'rotation_needs_reflash' | 'no_signed_messages' | null;
  in_grace: boolean;
}

interface Vehicle {
  id: string;
  plate_number: string;
}

/** The plain-language status of a machine's key, in the order the researcher cares about. */
export function keyStatus(m: Pick<ReturnMachine, 'last_issued_at' | 'last_issued_version' | 'key_version' | 'mismatch_reason'>): {
  text: string;
  tone: 'bad' | 'warn' | 'good' | 'neutral';
} {
  if (m.mismatch_reason) return { text: 'Key mismatch', tone: 'bad' };
  if (!m.last_issued_at) return { text: 'Key not yet fetched by a phone', tone: 'warn' };
  if (m.last_issued_version !== m.key_version) return { text: 'Phone has not fetched the new key', tone: 'warn' };
  return { text: 'Key issued', tone: 'good' };
}

const MISMATCH_TEXT: Record<NonNullable<ReturnMachine['mismatch_reason']>, string> = {
  machine_has_other_key:
    'The machine keeps a different key than the server issued (it was reset or re-flashed, or was provisioned from another source). Its messages cannot be verified until its key storage is cleared and the key is written again.',
  rotation_needs_reflash:
    'The key was rotated, but the machine still holds the old one and cannot replace it over the air. It must be re-flashed or have its key storage cleared.',
  no_signed_messages: 'The key was written to the machine but its messages are not arriving signed.',
};

const TONE_COLOR = { bad: 'var(--danger)', warn: 'var(--warning)', good: 'var(--success)', neutral: 'var(--text-secondary)' } as const;

function when(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'never';
}

export default function ReturnMachines() {
  const [machines, setMachines] = useState<ReturnMachine[]>([]);
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ machine_id: '', vehicle_id: '' });
  const [confirmRotate, setConfirmRotate] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    Promise.all([api.get('/return-machines'), api.get('/vehicles')])
      .then(([m, v]) => {
        setMachines(m.data);
        setVehicles(v.data);
        setLoading(false);
      })
      .catch((err) => {
        setLoadError(loadErrorMessage(err, 'the Return Machines'));
        setLoading(false);
      });
  }, []);

  useEffect(load, [load]);

  const register = async () => {
    setActionError(null);
    try {
      await api.post('/return-machines', form);
      setAdding(false);
      setForm({ machine_id: '', vehicle_id: '' });
      load();
    } catch (err) {
      setActionError(loadErrorMessage(err, 'that Return Machine'));
    }
  };

  const rotate = async (machineId: string) => {
    setActionError(null);
    try {
      await api.post(`/return-machines/${encodeURIComponent(machineId)}/rotate`);
      setConfirmRotate(null);
      load();
    } catch (err) {
      setActionError(loadErrorMessage(err, 'the key rotation'));
    }
  };

  const free = vehicles.filter((v) => !machines.some((m) => m.vehicle_id === v.id));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', overflowY: 'auto', paddingRight: '4px' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '16px' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0 }}>Return Machines</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>
            One machine per vehicle. Each machine signs its messages with its own key; the server issues the key to that
            vehicle&apos;s phone only. The key is never shown here.
          </div>
        </div>
        <div style={{ display: 'flex', gap: '12px' }}>
          <button onClick={load} aria-label="Refresh" style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-secondary)', borderRadius: '8px', padding: '8px 12px', cursor: 'pointer' }}>
            <RefreshCw size={16} />
          </button>
          <button onClick={() => setAdding(true)} disabled={free.length === 0} style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 16px', borderRadius: '8px', background: 'var(--accent)', color: '#000', border: 'none', fontWeight: 700, cursor: 'pointer', opacity: free.length === 0 ? 0.5 : 1 }}>
            <Plus size={16} /> Register machine
          </button>
        </div>
      </header>

      <LoadError message={loadError} onRetry={load} />
      {actionError && <div role="alert" style={{ color: 'var(--danger)' }}>{actionError}</div>}

      {adding && (
        <div className="glass-panel" style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <h3 style={{ margin: 0 }}>Register a Return Machine</h3>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
            Machine id (for example TOMS-RM-3C48, the name it advertises)
            <input value={form.machine_id} onChange={(e) => setForm({ ...form, machine_id: e.target.value })} style={{ padding: '8px', borderRadius: '8px', background: 'var(--bg-dark)', color: 'var(--text-primary)', border: '1px solid var(--border)' }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
            Vehicle
            <select value={form.vehicle_id} onChange={(e) => setForm({ ...form, vehicle_id: e.target.value })} style={{ padding: '8px', borderRadius: '8px', background: 'var(--bg-dark)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}>
              <option value="">Choose a vehicle</option>
              {free.map((v) => (
                <option key={v.id} value={v.id}>{v.id} ({v.plate_number})</option>
              ))}
            </select>
          </label>
          <div style={{ display: 'flex', gap: '12px' }}>
            <button onClick={register} disabled={!form.machine_id.trim() || !form.vehicle_id} style={{ padding: '8px 16px', borderRadius: '8px', background: 'var(--accent)', color: '#000', border: 'none', fontWeight: 700, cursor: 'pointer' }}>Register</button>
            <button onClick={() => setAdding(false)} style={{ padding: '8px 16px', borderRadius: '8px', background: 'transparent', color: 'var(--text-secondary)', border: '1px solid var(--border)', cursor: 'pointer' }}>Cancel</button>
          </div>
        </div>
      )}

      {loading ? (
        <div style={{ color: 'var(--text-secondary)' }}>Loading…</div>
      ) : machines.length === 0 ? (
        <div className="glass-panel" style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>
          No Return Machine is registered yet.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {machines.map((m) => {
            const status = keyStatus(m);
            return (
              <div key={m.machine_id} className="glass-panel" style={{ padding: '24px', border: m.mismatch_reason ? '1px solid var(--danger)' : undefined }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '16px' }}>
                  <div>
                    <h3 style={{ margin: 0, fontSize: '20px' }}>{m.machine_id}</h3>
                    <div style={{ color: 'var(--text-secondary)', fontSize: '13px', marginTop: '4px' }}>
                      Vehicle {m.vehicle_id} · registered {when(m.created_at)}
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: TONE_COLOR[status.tone], fontWeight: 700 }}>
                    {m.mismatch_reason ? <AlertTriangle size={16} /> : <KeyRound size={16} />}
                    {status.text}
                  </div>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '12px', marginTop: '16px', fontSize: '14px' }}>
                  <div><div style={{ color: 'var(--text-secondary)' }}>Key version</div><strong>{m.key_version}</strong>{m.in_grace && <span style={{ color: 'var(--warning)' }}> · old key still valid</span>}</div>
                  <div><div style={{ color: 'var(--text-secondary)' }}>Last rotated</div><strong>{when(m.rotated_at)}</strong></div>
                  <div><div style={{ color: 'var(--text-secondary)' }}>Last issued to a phone</div><strong>{when(m.last_issued_at)}</strong>{m.last_issued_device && <span style={{ color: 'var(--text-secondary)' }}> · {m.last_issued_device}</span>}</div>
                </div>

                {m.mismatch_reason && (
                  <div role="alert" style={{ marginTop: '16px', padding: '12px', borderRadius: '8px', background: 'rgba(239,68,68,0.12)', color: 'var(--danger)' }}>
                    <strong>Key mismatch since {when(m.mismatch_since)}.</strong> {MISMATCH_TEXT[m.mismatch_reason]}
                  </div>
                )}

                <div style={{ marginTop: '16px', display: 'flex', gap: '12px', alignItems: 'center' }}>
                  {confirmRotate === m.machine_id ? (
                    <>
                      <span style={{ color: 'var(--warning)' }}>
                        Rotating makes the phone fetch a new key; the old one keeps working for an hour. Rotate?
                      </span>
                      <button onClick={() => rotate(m.machine_id)} style={{ padding: '8px 16px', borderRadius: '8px', background: 'var(--warning)', color: '#000', border: 'none', fontWeight: 700, cursor: 'pointer' }}>Rotate key</button>
                      <button onClick={() => setConfirmRotate(null)} style={{ padding: '8px 16px', borderRadius: '8px', background: 'transparent', color: 'var(--text-secondary)', border: '1px solid var(--border)', cursor: 'pointer' }}>Cancel</button>
                    </>
                  ) : (
                    <button onClick={() => setConfirmRotate(m.machine_id)} style={{ padding: '8px 16px', borderRadius: '8px', background: 'transparent', color: 'var(--accent)', border: '1px solid var(--accent)', cursor: 'pointer', fontWeight: 600 }}>
                      Rotate key
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
