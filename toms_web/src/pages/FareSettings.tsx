import { useState, useEffect } from 'react';
import { FileText, Upload, CheckCircle2, AlertCircle, ExternalLink, ShieldCheck } from 'lucide-react';
import { api, API_BASE } from '../lib/api';

interface RouteOption {
  id: number;
  name: string;
}

interface ActiveFareMatrix {
  id: number;
  route_id: number | null;
  base_fare: number;
  base_distance_km: number;
  per_km_fare: number;
  rounding_step_cents: number;
  discounts: Record<string, number>;
  order_reference: string | null;
  effective_date: string;
  document_url: string | null;
  is_active: boolean;
  is_default_fallback?: boolean;
}

export default function FareSettings() {
  const [routes, setRoutes] = useState<RouteOption[]>([]);
  const [selectedRouteId, setSelectedRouteId] = useState<number | null>(null);
  const [activeMatrix, setActiveMatrix] = useState<ActiveFareMatrix | null>(null);

  // Form State
  const [baseFare, setBaseFare] = useState('15.00');
  const [baseDistanceKm, setBaseDistanceKm] = useState('4.0');
  const [perKmFare, setPerKmFare] = useState('2.50');
  const [roundingStep, setRoundingStep] = useState('100');
  const [studentDiscount, setStudentDiscount] = useState('20');
  const [pwdDiscount, setPwdDiscount] = useState('20');
  const [seniorDiscount, setSeniorDiscount] = useState('20');
  const [orderReference, setOrderReference] = useState('');
  const [effectiveDate, setEffectiveDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [documentFile, setDocumentFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    loadRoutes();
  }, []);

  useEffect(() => {
    loadActiveFare(selectedRouteId);
  }, [selectedRouteId]);

  async function loadRoutes() {
    try {
      const res = await api.get('/routes');
      setRoutes(res.data || []);
      if (res.data && res.data.length > 0 && selectedRouteId === null) {
        setSelectedRouteId(res.data[0].id);
      }
    } catch (err) {
      console.error('Failed to load routes:', err);
    }
  }

  async function loadActiveFare(routeId: number | null) {
    try {
      const url = routeId ? `/fares/active/${routeId}` : '/fares/active';
      const res = await api.get(url);
      const data: ActiveFareMatrix = res.data;
      setActiveMatrix(data);

      // Prepopulate form with active settings
      setBaseFare(Number(data.base_fare).toFixed(2));
      setBaseDistanceKm(Number(data.base_distance_km).toFixed(1));
      setPerKmFare(Number(data.per_km_fare).toFixed(2));
      setRoundingStep(String(data.rounding_step_cents || 100));
      setStudentDiscount(String(data.discounts?.student ?? 20));
      setPwdDiscount(String(data.discounts?.pwd ?? 20));
      setSeniorDiscount(String(data.discounts?.senior ?? 20));
      setOrderReference(data.order_reference || '');
      setEffectiveDate(data.effective_date ? data.effective_date.slice(0, 10) : new Date().toISOString().slice(0, 10));
      setPreviewUrl(data.document_url ? `${API_BASE}${data.document_url}` : null);
      setDocumentFile(null);
    } catch (err) {
      console.error('Failed to load active fare matrix:', err);
    }
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      setDocumentFile(file);
      setPreviewUrl(URL.createObjectURL(file));
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setSuccessMsg(null);
    setErrorMsg(null);

    try {
      const formData = new FormData();
      if (selectedRouteId) formData.append('route_id', String(selectedRouteId));
      formData.append('base_fare', baseFare);
      formData.append('base_distance_km', baseDistanceKm);
      formData.append('per_km_fare', perKmFare);
      formData.append('rounding_step_cents', roundingStep);
      formData.append(
        'discounts',
        JSON.stringify({
          student: Number(studentDiscount) || 20,
          pwd: Number(pwdDiscount) || 20,
          senior: Number(seniorDiscount) || 20,
        }),
      );
      if (orderReference.trim()) formData.append('order_reference', orderReference.trim());
      formData.append('effective_date', effectiveDate);
      if (documentFile) {
        formData.append('document', documentFile);
      }

      const res = await api.post('/fares', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      setSuccessMsg('Verified fare matrix updated successfully with official LTFRB documentation!');
      setActiveMatrix(res.data);
      if (res.data.document_url) {
        setPreviewUrl(`${API_BASE}${res.data.document_url}`);
      }
    } catch (err: any) {
      setErrorMsg(err.response?.data?.error || 'Failed to save fare matrix.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', overflow: 'auto' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Verified Fare Settings & Compliance</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>
            Set government-mandated fare matrices, configure discounts, and attach scanned LTFRB signed approval certificates
          </div>
        </div>
      </header>

      {/* Route Switcher */}
      <div className="glass-panel" style={{ padding: '16px 24px', display: 'flex', alignItems: 'center', gap: '16px' }}>
        <span style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-secondary)' }}>Route Selection:</span>
        <select
          value={selectedRouteId ?? ''}
          onChange={(e) => setSelectedRouteId(e.target.value ? Number(e.target.value) : null)}
          style={{ padding: '8px 16px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)', fontSize: '14px' }}
        >
          {routes.map((r) => (
            <option key={r.id} value={r.id}>{r.name}</option>
          ))}
        </select>
        {activeMatrix?.order_reference && (
          <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: '6px', color: 'var(--success)', fontSize: '13px', fontWeight: 600 }}>
            <ShieldCheck size={16} /> Verified under: {activeMatrix.order_reference}
          </span>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(400px, 1fr) minmax(360px, 1fr)', gap: '24px' }}>
        {/* Fare Matrix Configuration Form */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <h3 style={{ margin: '0 0 16px 0', fontSize: '18px', borderBottom: '1px solid var(--border)', paddingBottom: '12px' }}>
            Configure Fare Matrix Parameters
          </h3>

          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
                Base Fare (PHP) *
                <input
                  type="number"
                  step="0.25"
                  value={baseFare}
                  onChange={(e) => setBaseFare(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                  required
                />
              </label>

              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
                Base Distance (km) *
                <input
                  type="number"
                  step="0.1"
                  value={baseDistanceKm}
                  onChange={(e) => setBaseDistanceKm(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                  required
                />
              </label>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
                Per-Km Surcharge (PHP) *
                <input
                  type="number"
                  step="0.25"
                  value={perKmFare}
                  onChange={(e) => setPerKmFare(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                  required
                />
              </label>

              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
                Rounding Rule
                <select
                  value={roundingStep}
                  onChange={(e) => setRoundingStep(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                >
                  <option value="100">Nearest Whole Peso (₱1.00)</option>
                  <option value="50">Nearest 50 Centavos (₱0.50)</option>
                  <option value="25">Nearest 25 Centavos (₱0.25)</option>
                </select>
              </label>
            </div>

            <div style={{ borderTop: '1px solid var(--border)', paddingTop: '12px', marginTop: '4px' }}>
              <h4 style={{ margin: '0 0 10px 0', fontSize: '14px', color: 'var(--text-secondary)' }}>Statutory Discounts (%)</h4>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
                <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px' }}>
                  Student
                  <input
                    type="number"
                    value={studentDiscount}
                    onChange={(e) => setStudentDiscount(e.target.value)}
                    style={{ padding: '8px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                  />
                </label>
                <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px' }}>
                  PWD
                  <input
                    type="number"
                    value={pwdDiscount}
                    onChange={(e) => setPwdDiscount(e.target.value)}
                    style={{ padding: '8px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                  />
                </label>
                <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px' }}>
                  Senior Citizen
                  <input
                    type="number"
                    value={seniorDiscount}
                    onChange={(e) => setSeniorDiscount(e.target.value)}
                    style={{ padding: '8px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                  />
                </label>
              </div>
            </div>

            <div style={{ borderTop: '1px solid var(--border)', paddingTop: '12px' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
                LTFRB Memorandum / Order Reference
                <input
                  type="text"
                  placeholder="e.g. LTFRB Memorandum Circular No. 2023-045"
                  value={orderReference}
                  onChange={(e) => setOrderReference(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                />
              </label>
            </div>

            <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '13px' }}>
              Upload Scanned Official LTFRB Photocopy (JPEG, PNG, PDF)
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp,application/pdf"
                onChange={handleFileChange}
                style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px dashed var(--border)', borderRadius: '6px', color: 'var(--text-primary)', cursor: 'pointer' }}
              />
            </label>

            {successMsg && (
              <div style={{ color: 'var(--success)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                <CheckCircle2 size={16} /> {successMsg}
              </div>
            )}
            {errorMsg && (
              <div style={{ color: 'var(--danger)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                <AlertCircle size={16} /> {errorMsg}
              </div>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="btn-primary"
              style={{ marginTop: '8px', padding: '12px', borderRadius: '8px', fontWeight: 700 }}
            >
              {submitting ? 'Uploading & Verifying...' : 'Publish Verified Fare Matrix'}
            </button>
          </form>
        </div>

        {/* Official Document Preview Panel */}
        <div className="glass-panel" style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <h3 style={{ margin: 0, fontSize: '18px', borderBottom: '1px solid var(--border)', paddingBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <FileText size={20} color="var(--accent)" />
            <span>Official LTFRB Document Scan</span>
          </h3>

          {previewUrl ? (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                This signed document is embedded in passenger digital receipts for legal fare compliance:
              </div>
              <div style={{ flex: 1, minHeight: '340px', background: 'var(--bg-dark)', borderRadius: '8px', border: '1px solid var(--border)', overflow: 'hidden', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
                {previewUrl.endsWith('.pdf') ? (
                  <iframe src={previewUrl} style={{ width: '100%', height: '100%', minHeight: '340px', border: 'none' }} title="LTFRB PDF" />
                ) : (
                  <img src={previewUrl} alt="LTFRB Scanned Photocopy" style={{ maxWidth: '100%', maxHeight: '420px', objectFit: 'contain' }} />
                )}
              </div>
              <a
                href={previewUrl}
                target="_blank"
                rel="noreferrer"
                style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', color: 'var(--accent)', fontSize: '13px', textDecoration: 'none' }}
              >
                <ExternalLink size={14} /> Open document in new tab
              </a>
            </div>
          ) : (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', color: 'var(--text-secondary)', padding: '40px', textAlign: 'center', gap: '12px' }}>
              <Upload size={36} />
              <div>No scanned LTFRB signed photocopy uploaded for this route yet. Upload a scan above to make it visible on passenger e-receipts.</div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
