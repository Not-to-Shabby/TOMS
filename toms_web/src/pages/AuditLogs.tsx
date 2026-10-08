import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Search, Calendar, ChevronLeft, ChevronRight, RefreshCw, Eye } from 'lucide-react';
import { api, downloadFile } from '../lib/api';
import { eventLabel, partyText, pesos, shortId, stopRange } from '../lib/format';


interface LogEvent {
  id: number;
  event_id: string;
  event_type: string;
  timestamp: string;
  clock_suspect: boolean;
  vehicle_id: string | null;
  device_id: string;
  delivery_channel: string;
  card_uuid: string | null;
  nfc_uid: string | null;
  card_state: string | null;
  boarding_stop_id: string | null;
  declared_destination_stop_id: string | null;
  actual_destination_stop_id: string | null;
  discount_category_id: string | null;
  computed_fare_centavos: number | null;
  fare_centavos: number | null;
  discount_centavos: number | null;
  fare_version: number | null;
  override_reason: string | null;
  gps_lat: number | null;
  gps_lon: number | null;
  gps_accuracy_m: number | null;
  passenger_count: number;
  passengers: { categoryId: string | null; count: number; fareCentavos: number }[];
}

interface PaginationMetadata {
  total: number;
  page: number;
  limit: number;
  totalPages: number;
}

export default function AuditLogs() {
  const [logs, setLogs] = useState<LogEvent[]>([]);
  const [metadata, setMetadata] = useState<PaginationMetadata>({ total: 0, page: 1, limit: 15, totalPages: 1 });
  const [searchParams] = useSearchParams();
  const [search, setSearch] = useState<string>(searchParams.get('search') || '');
  const [startDate, setStartDate] = useState<string>('');
  const [endDate, setEndDate] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [selectedLog, setSelectedLog] = useState<LogEvent | null>(null);

  const fetchLogs = (pageToFetch: number = 1, forceSearch?: string) => {
    setLoading(true);
    const searchQuery = forceSearch !== undefined ? forceSearch : search;
    let params: any = {
      page: pageToFetch,
      limit: metadata.limit,
      search: searchQuery
    };
    if (startDate) params.startDate = `${startDate}T00:00:00.000Z`;
    if (endDate) params.endDate = `${endDate}T23:59:59.000Z`;

    api.get(`/audit/logs`, { params })
      .then(res => {
        setLogs(res.data.logs);
        setMetadata(res.data.metadata);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to fetch audit logs", err);
        setLoading(false);
      });
  };

  useEffect(() => {
    const s = searchParams.get('search');
    if (s !== null) {
      setSearch(s);
      fetchLogs(1, s);
    } else {
      fetchLogs(1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startDate, endDate, searchParams.get('search')]);

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    fetchLogs(1);
  };

  const handlePageChange = (newPage: number) => {
    if (newPage >= 1 && newPage <= metadata.totalPages) {
      fetchLogs(newPage);
    }
  };

  const [exportNote, setExportNote] = useState<string | null>(null);
  const exportCsv = async () => {
    setExportNote(null);
    try {
      const { truncated } = await downloadFile('/export/audit', 'toms_audit_logs.csv');
      if (truncated) setExportNote('Export was cut at 50,000 rows. Narrow the data or ask for a full export.');
    } catch {
      setExportNote('Export failed. Please try again.');
    }
  };

  const getEventBadgeStyle = (type: string) => {
    switch (type) {
      case 'card_state_changed':
        return { background: 'rgba(16, 185, 129, 0.15)', color: 'var(--success)', border: '1px solid var(--success)' };
      case 'trip_created':
        return { background: 'rgba(0, 210, 255, 0.15)', color: 'var(--accent)', border: '1px solid var(--accent)' };
      case 'alarm':
        return { background: 'rgba(239, 68, 68, 0.15)', color: 'var(--danger)', border: '1px solid var(--danger)' };
      default:
        return { background: 'rgba(255, 255, 255, 0.1)', color: 'var(--text-primary)', border: '1px solid var(--border)' };
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', position: 'relative' }}>
      {exportNote && <div role="status" style={{ color: 'var(--warning)' }}>{exportNote}</div>}
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Conductor Transaction Audit Logs</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Searchable database of every physical boarding, payment, and alighting event</div>
        </div>
        <div style={{ display: 'flex', gap: '12px' }}>
          <button 
            onClick={() => exportCsv()}
            style={{ 
              background: 'transparent', 
              border: '1px solid var(--accent)', 
              borderRadius: '8px', 
              color: 'var(--accent)', 
              padding: '8px 16px',
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              cursor: 'pointer',
              fontWeight: 600
            }}
          >
            Export CSV
          </button>
          <button 
            onClick={() => fetchLogs(metadata.page)} 
            disabled={loading}
            style={{ 
              background: 'transparent', 
              border: '1px solid var(--border)', 
              borderRadius: '8px', 
              color: 'var(--text-primary)', 
              padding: '8px 16px',
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              cursor: 'pointer'
            }}
          >
            <RefreshCw size={16} className={loading ? 'spin-anim' : ''} /> Refresh
          </button>
        </div>
      </header>

      {/* Filter and Search Panel */}
      <div className="glass-panel" style={{ padding: '20px' }}>
        <form onSubmit={handleSearchSubmit} style={{ display: 'flex', flexWrap: 'wrap', gap: '16px', alignItems: 'center' }}>
          {/* Search Box */}
          <div style={{ flex: 2, minWidth: '260px', position: 'relative' }}>
            <input 
              type="text" 
              placeholder="Search by UID, Bus ID, Stop or Ticket type..." 
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{
                width: '100%',
                padding: '10px 16px 10px 40px',
                borderRadius: '8px',
                background: 'var(--bg-dark)',
                color: 'var(--text-primary)',
                border: '1px solid var(--border)',
                outline: 'none'
              }}
            />
            <Search size={18} style={{ position: 'absolute', left: '14px', top: '12px', color: 'var(--text-secondary)' }} />
          </div>

          {/* Date Range Start */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Calendar size={16} style={{ color: 'var(--text-secondary)' }} />
            <input 
              type="date" 
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              style={{
                padding: '10px 12px',
                borderRadius: '8px',
                background: 'var(--bg-dark)',
                color: 'var(--text-primary)',
                border: '1px solid var(--border)',
                outline: 'none'
              }}
            />
          </div>

          <span style={{ color: 'var(--text-secondary)' }}>to</span>

          {/* Date Range End */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Calendar size={16} style={{ color: 'var(--text-secondary)' }} />
            <input 
              type="date" 
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              style={{
                padding: '10px 12px',
                borderRadius: '8px',
                background: 'var(--bg-dark)',
                color: 'var(--text-primary)',
                border: '1px solid var(--border)',
                outline: 'none'
              }}
            />
          </div>

          <button type="submit" className="btn-primary" style={{ padding: '10px 24px', fontSize: '14px' }}>
            Search Logs
          </button>
        </form>
      </div>

      {/* Main Logs Table */}
      <div className="glass-panel" style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)', background: 'rgba(0,0,0,0.2)', position: 'sticky', top: 0, zIndex: 1 }}>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Timestamp</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Vehicle</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Device</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Event</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Card UID</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Fare</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Stop Range</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600, textAlign: 'center' }}>Details</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
                    Loading audit trail database...
                  </td>
                </tr>
              ) : logs.length === 0 ? (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
                    No matching audit log records found.
                  </td>
                </tr>
              ) : (
                logs.map(log => {
                  const dateStr = new Date(log.timestamp).toLocaleDateString(undefined, { 
                    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'
                  });
                  return (
                    <tr 
                      key={log.id} 
                      style={{ 
                        borderBottom: '1px solid var(--border)', 
                        transition: 'background 0.15s',
                        cursor: 'pointer'
                      }}
                      onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'rgba(255, 255, 255, 0.02)'}
                      onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                      onClick={() => setSelectedLog(log)}
                    >
                      <td style={{ padding: '16px 24px', fontSize: '14px', whiteSpace: 'nowrap' }}>{dateStr}</td>
                      <td style={{ padding: '16px 24px', whiteSpace: 'nowrap' }}>
                        <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{log.vehicle_id ?? 'Unassigned'}</div>
                      </td>
                      <td style={{ padding: '16px 24px', whiteSpace: 'nowrap', color: 'var(--text-secondary)' }}>
                        <div>{log.device_id}</div>
                        <div style={{ fontSize: '12px' }}>{log.delivery_channel}{log.clock_suspect ? ' · clock?' : ''}</div>
                      </td>
                      <td style={{ padding: '16px 24px' }}>
                        <span style={{ 
                          padding: '4px 8px', 
                          borderRadius: '4px', 
                          fontSize: '11px', 
                          fontWeight: 700,
                          textTransform: 'uppercase',
                          ...getEventBadgeStyle(log.event_type)
                        }}>
                          {eventLabel(log.event_type)}{log.card_state ? `: ${log.card_state}` : ''}
                        </span>
                      </td>
                      <td style={{ padding: '16px 24px', fontSize: '14px', fontFamily: 'monospace' }}>{log.nfc_uid || '-'}</td>
                      <td style={{ padding: '16px 24px', fontSize: '14px', fontWeight: 600, color: 'var(--warning)' }}>
                        {pesos(log.fare_centavos)}
                      </td>
                      <td style={{ padding: '16px 24px', fontSize: '14px', color: 'var(--text-secondary)' }}>
                        {stopRange(log.boarding_stop_id, log.declared_destination_stop_id, log.actual_destination_stop_id)}
                      </td>
                      <td style={{ padding: '16px 24px', textAlign: 'center' }}>
                        <button 
                          onClick={(e) => { e.stopPropagation(); setSelectedLog(log); }}
                          style={{ background: 'transparent', border: 'none', color: 'var(--accent)', cursor: 'pointer' }}
                        >
                          <Eye size={18} />
                        </button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination Footer */}
        <div style={{ 
          padding: '16px 24px', 
          borderTop: '1px solid var(--border)', 
          background: 'rgba(0,0,0,0.1)',
          display: 'flex', 
          justifyContent: 'space-between', 
          alignItems: 'center' 
        }}>
          <div style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>
            Showing <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
              {logs.length > 0 ? (metadata.page - 1) * metadata.limit + 1 : 0}
            </span> to <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
              {Math.min(metadata.page * metadata.limit, metadata.total)}
            </span> of <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{metadata.total}</span> records
          </div>
          
          <div style={{ display: 'flex', gap: '8px' }}>
            <button 
              disabled={metadata.page === 1}
              onClick={() => handlePageChange(metadata.page - 1)}
              style={{
                background: 'var(--bg-dark)',
                color: 'var(--text-primary)',
                border: '1px solid var(--border)',
                borderRadius: '6px',
                padding: '6px 12px',
                display: 'flex',
                alignItems: 'center',
                cursor: 'pointer',
                opacity: metadata.page === 1 ? 0.4 : 1
              }}
            >
              <ChevronLeft size={16} /> Prev
            </button>
            <button 
              disabled={metadata.page === metadata.totalPages}
              onClick={() => handlePageChange(metadata.page + 1)}
              style={{
                background: 'var(--bg-dark)',
                color: 'var(--text-primary)',
                border: '1px solid var(--border)',
                borderRadius: '6px',
                padding: '6px 12px',
                display: 'flex',
                alignItems: 'center',
                cursor: 'pointer',
                opacity: metadata.page === metadata.totalPages ? 0.4 : 1
              }}
            >
              Next <ChevronRight size={16} />
            </button>
          </div>
        </div>
      </div>

      {/* Transaction Details Modal */}
      {selectedLog && (
        <div style={{ 
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, 
          background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
          display: 'flex', justifyContent: 'center', alignItems: 'center',
          zIndex: 100
        }}>
          <div className="glass-panel" style={{ width: '100%', maxWidth: '500px', padding: '24px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px', borderBottom: '1px solid var(--border)', paddingBottom: '12px' }}>
              Transaction Log Entry Details
            </h3>
            
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '14px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Log ID</span>
                <span style={{ fontFamily: 'monospace' }}>#{selectedLog.id}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Event ID</span>
                <span style={{ fontFamily: 'monospace' }}>{shortId(selectedLog.event_id)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Time</span>
                <span>{new Date(selectedLog.timestamp).toLocaleString()}{selectedLog.clock_suspect ? ' (phone clock looked wrong; server time used)' : ''}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Vehicle</span>
                <span style={{ fontWeight: 600 }}>{selectedLog.vehicle_id ?? 'Unassigned'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Device / channel</span>
                <span>{selectedLog.device_id} · {selectedLog.delivery_channel}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Event</span>
                <span style={{ padding: '2px 8px', borderRadius: '4px', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', ...getEventBadgeStyle(selectedLog.event_type) }}>{eventLabel(selectedLog.event_type)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Card UID</span>
                <span style={{ fontFamily: 'monospace' }}>{selectedLog.nfc_uid ?? '-'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Card ID (QR)</span>
                <span style={{ fontFamily: 'monospace' }}>{shortId(selectedLog.card_uuid, 13)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Card state</span>
                <span>{selectedLog.card_state ?? '-'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Passengers</span>
                <span>{partyText(selectedLog.passengers, selectedLog.passenger_count)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Fare charged</span>
                <span style={{ fontWeight: 700, color: 'var(--warning)' }}>{pesos(selectedLog.fare_centavos)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Fare computed</span>
                <span>{pesos(selectedLog.computed_fare_centavos)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Discount</span>
                <span>{pesos(selectedLog.discount_centavos)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Fare version</span>
                <span>{selectedLog.fare_version ?? '-'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Stops</span>
                <span>{stopRange(selectedLog.boarding_stop_id, selectedLog.declared_destination_stop_id, selectedLog.actual_destination_stop_id)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Override reason</span>
                <span>{selectedLog.override_reason ?? '-'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>GPS</span>
                <span>{selectedLog.gps_lat !== null && selectedLog.gps_lon !== null ? `${selectedLog.gps_lat.toFixed(5)}, ${selectedLog.gps_lon.toFixed(5)}${selectedLog.gps_accuracy_m !== null ? ` ±${Math.round(selectedLog.gps_accuracy_m)} m` : ''}` : 'no fix'}</span>
              </div>
            </div>

            <button 
              onClick={() => setSelectedLog(null)} 
              className="btn-primary" 
              style={{ width: '100%', marginTop: '24px', padding: '10px' }}
            >
              Close Details
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
