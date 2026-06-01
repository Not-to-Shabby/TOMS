import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Search, Calendar, ChevronLeft, ChevronRight, RefreshCw, Eye } from 'lucide-react';
import axios from 'axios';

const API_URL = 'http://localhost:3000/api';

interface LogEvent {
  id: number;
  event_type: string;
  timestamp: string;
  vehicle_id: string;
  conductor_name: string | null;
  route: string | null;
  slot_number: number | null;
  slave_uid: string | null;
  passenger_type: string | null;
  fare_centavos: number;
  discount_centavos: number;
  boarding_stop: string | null;
  destination_stop: string | null;
  occupancy_now: number;
  max_capacity: number;
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

    axios.get(`${API_URL}/audit/logs`, { params })
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

  const getEventBadgeStyle = (type: string) => {
    switch (type) {
      case 'payment':
        return { background: 'rgba(16, 185, 129, 0.15)', color: 'var(--success)', border: '1px solid var(--success)' };
      case 'boarding':
        return { background: 'rgba(0, 210, 255, 0.15)', color: 'var(--accent)', border: '1px solid var(--accent)' };
      case 'release':
        return { background: 'rgba(148, 163, 184, 0.15)', color: 'var(--text-secondary)', border: '1px solid var(--border)' };
      case 'alarm':
        return { background: 'rgba(239, 68, 68, 0.15)', color: 'var(--danger)', border: '1px solid var(--danger)' };
      default:
        return { background: 'rgba(255, 255, 255, 0.1)', color: 'var(--text-primary)', border: '1px solid var(--border)' };
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', position: 'relative' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Conductor Transaction Audit Logs</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Searchable database of every physical boarding, payment, and alighting event</div>
        </div>
        <div style={{ display: 'flex', gap: '12px' }}>
          <button 
            onClick={() => window.location.href = `${API_URL}/export/audit`}
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
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Conductor</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Event</th>
                <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Slave Card UID</th>
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
                        <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{log.vehicle_id}</div>
                        {log.route && <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{log.route}</div>}
                      </td>
                      <td style={{ padding: '16px 24px', whiteSpace: 'nowrap', color: 'var(--text-secondary)' }}>
                        {log.conductor_name || 'N/A'}
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
                          {log.event_type}
                        </span>
                      </td>
                      <td style={{ padding: '16px 24px', fontSize: '14px', fontFamily: 'monospace' }}>{log.slave_uid || '-'}</td>
                      <td style={{ padding: '16px 24px', fontSize: '14px', fontWeight: 600, color: 'var(--warning)' }}>
                        {log.fare_centavos > 0 ? `₱${(log.fare_centavos / 100).toFixed(2)}` : '-'}
                      </td>
                      <td style={{ padding: '16px 24px', fontSize: '14px', color: 'var(--text-secondary)' }}>
                        {log.boarding_stop || log.destination_stop ? (
                          <span>{log.boarding_stop || '?'} ➔ {log.destination_stop || '?'}</span>
                        ) : '-'}
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
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Log ID</span>
                <span style={{ fontFamily: 'monospace' }}>#{selectedLog.id}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Timestamp</span>
                <span>{new Date(selectedLog.timestamp).toLocaleString()}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Vehicle ID</span>
                <span style={{ fontWeight: 600 }}>{selectedLog.vehicle_id}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Assigned Route</span>
                <span>{selectedLog.route || 'N/A'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Event Type</span>
                <span style={{ 
                  padding: '2px 8px', borderRadius: '4px', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase',
                  ...getEventBadgeStyle(selectedLog.event_type)
                }}>{selectedLog.event_type}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Seat Slot</span>
                <span style={{ fontWeight: 700 }}>{selectedLog.slot_number || '-'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Passenger Type</span>
                <span>{selectedLog.passenger_type || 'N/A'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Fare Paid</span>
                <span style={{ fontWeight: 700, color: 'var(--warning)' }}>
                  {selectedLog.fare_centavos > 0 ? `₱${(selectedLog.fare_centavos / 100).toFixed(2)}` : 'N/A'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Discount Allowed</span>
                <span>
                  {selectedLog.discount_centavos > 0 ? `₱${(selectedLog.discount_centavos / 100).toFixed(2)}` : 'N/A'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Boarding Stop</span>
                <span>{selectedLog.boarding_stop || 'N/A'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Destination Stop</span>
                <span>{selectedLog.destination_stop || 'N/A'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-secondary)' }}>Bus Occupancy Post-Event</span>
                <span>{selectedLog.occupancy_now} / {selectedLog.max_capacity}</span>
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
