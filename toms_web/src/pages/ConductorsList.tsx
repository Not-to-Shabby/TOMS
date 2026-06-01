import { useState, useEffect } from 'react';
import { Search, Phone, Mail, Clock, ArrowRight, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import axios from 'axios';

const API_URL = 'http://localhost:3000/api';

interface Conductor {
  id: string;
  username: string;
  name: string;
  contact_number?: string;
  address?: string;
  status: 'Active' | 'Offline';
  assigned_vehicle: string;
  total_sales_today: number;
  last_sync: string;
}

export default function ConductorsList() {
  const [conductors, setConductors] = useState<Conductor[]>([]);
  const [search, setSearch] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [newStaff, setNewStaff] = useState({ name: '', username: '', password: '', assigned_vehicle: '', contact_number: '', address: '' });
  
  const [vehicles, setVehicles] = useState<any[]>([]);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingConductor, setEditingConductor] = useState<Conductor | null>(null);

  const fetchConductors = () => {
    setLoading(true);
    axios.get(`${API_URL}/conductors`)
      .then(res => {
        setConductors(res.data);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to fetch conductors staff list", err);
        setLoading(false);
      });
  };

  const fetchDependencies = async () => {
    try {
      const vRes = await axios.get(`${API_URL}/vehicles`);
      setVehicles(vRes.data);
    } catch (err) {
      console.error("Failed to fetch vehicles for assignment", err);
    }
  }

  useEffect(() => {
    fetchConductors();
    fetchDependencies();
  }, []);

  const filteredConductors = conductors.filter(c => 
    c.name.toLowerCase().includes(search.toLowerCase()) ||
    c.assigned_vehicle.toLowerCase().includes(search.toLowerCase()) ||
    c.username.toLowerCase().includes(search.toLowerCase()) ||
    c.id.toString().includes(search.toLowerCase())
  );

  const handleAddSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await axios.post(`${API_URL}/conductors`, newStaff);
      setShowAddModal(false);
      setNewStaff({ name: '', username: '', password: '', assigned_vehicle: '', contact_number: '', address: '' });
      fetchConductors();
    } catch (err: any) {
      alert(err.response?.data?.error || 'Failed to add conductor');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to remove this conductor account?')) return;
    try {
      await axios.delete(`${API_URL}/conductors/${id}`);
      fetchConductors();
    } catch (err) {
      alert('Failed to delete conductor');
    }
  };

  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingConductor) return;
    try {
      // Find the old vehicle that was assigned to this conductor, if any
      const oldVehicle = vehicles.find(v => v.assigned_conductor_id === editingConductor.id);
      
      // If there's a newly selected vehicle, update it
      if (editingConductor.assigned_vehicle && editingConductor.assigned_vehicle !== 'N/A') {
        const newVehicleId = editingConductor.assigned_vehicle;
        const targetVehicle = vehicles.find(v => v.id === newVehicleId);
        if (targetVehicle) {
          await axios.put(`${API_URL}/vehicles/${newVehicleId}`, {
            ...targetVehicle,
            assigned_conductor_id: editingConductor.id
          });
        }
      } else if (oldVehicle) {
        // Unassigning: just update the old vehicle to have no conductor
        await axios.put(`${API_URL}/vehicles/${oldVehicle.id}`, {
          ...oldVehicle,
          assigned_conductor_id: null
        });
      }
      
      setShowEditModal(false);
      setEditingConductor(null);
      fetchConductors();
      fetchDependencies();
    } catch (err: any) {
      alert(err.response?.data?.error || 'Failed to update assignment');
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Conductor Staff Directory</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Active shifts, dynamic daily sales performance, and contact details</div>
        </div>
        <div style={{ display: 'flex', gap: '12px' }}>
          <button 
            onClick={fetchConductors} 
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
            <RefreshCw size={16} className={loading ? 'spin-anim' : ''} /> Refresh Staff
          </button>
          <button 
            className="btn-primary"
            onClick={() => setShowAddModal(true)}
            style={{ padding: '8px 16px', display: 'flex', alignItems: 'center', gap: '8px' }}
          >
            + Add Staff
          </button>
        </div>
      </header>

      {/* Search and Filters */}
      <div className="glass-panel" style={{ padding: '20px' }}>
        <div style={{ position: 'relative', width: '100%', maxWidth: '400px' }}>
          <input 
            type="text" 
            placeholder="Search by Conductor Name, ID, or Vehicle..." 
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
      </div>

      {/* Staff Grid/Table */}
      <div className="glass-panel" style={{ flex: 1, overflowY: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--border)', background: 'rgba(0,0,0,0.2)', position: 'sticky', top: 0, zIndex: 1 }}>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Staff Name</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Status</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Vehicle</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Total Collected Today</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Last Synced</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600, textAlign: 'center' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={7} style={{ textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
                  Loading registered conductor list...
                </td>
              </tr>
            ) : filteredConductors.length === 0 ? (
              <tr>
                <td colSpan={7} style={{ textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
                  No matching conductors found.
                </td>
              </tr>
            ) : (
              filteredConductors.map(conductor => {
                const formattedSync = conductor.last_sync !== 'N/A' 
                  ? new Date(conductor.last_sync).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
                  : 'Never';

                return (
                  <tr 
                    key={conductor.id} 
                    style={{ 
                      borderBottom: '1px solid var(--border)', 
                      transition: 'background 0.15s'
                    }}
                    onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'rgba(255, 255, 255, 0.02)'}
                    onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                  >
                    {/* Staff Name, ID & Contact */}
                    <td style={{ padding: '16px 24px' }}>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
                        <span style={{ fontWeight: 700, fontSize: '15px' }}>{conductor.name}</span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-secondary)' }}>
                          <span style={{ fontSize: '11px', fontFamily: 'monospace' }}>ID: {conductor.id}</span>
                          {conductor.contact_number && (
                            <>
                              <span style={{ fontSize: '10px' }}>•</span>
                              <span style={{ fontSize: '12px', display: 'flex', alignItems: 'center', gap: '4px' }}>
                                <Phone size={10} /> {conductor.contact_number}
                              </span>
                            </>
                          )}
                        </div>
                      </div>
                    </td>

                    {/* Status Dot */}
                    <td style={{ padding: '16px 24px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <div style={{ 
                          width: '8px', 
                          height: '8px', 
                          borderRadius: '50%', 
                          background: conductor.status === 'Active' ? 'var(--success)' : 'var(--text-secondary)'
                        }} />
                        <span style={{ 
                          fontSize: '13px', 
                          fontWeight: 600, 
                          color: conductor.status === 'Active' ? 'var(--success)' : 'var(--text-secondary)'
                        }}>
                          {conductor.status === 'Active' ? 'On Shift' : 'Off Shift'}
                        </span>
                      </div>
                    </td>

                    {/* Assigned Vehicle */}
                    <td style={{ padding: '16px 24px', fontWeight: 600, fontSize: '14px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        {conductor.assigned_vehicle !== 'N/A' ? (
                          <span style={{ color: 'var(--accent)' }}>{conductor.assigned_vehicle}</span>
                        ) : (
                          <span style={{ color: 'var(--text-secondary)' }}>-</span>
                        )}
                        <button
                          onClick={() => {
                            setEditingConductor({ ...conductor });
                            setShowEditModal(true);
                          }}
                          style={{
                            background: 'transparent',
                            border: '1px solid var(--border)',
                            color: 'var(--text-secondary)',
                            borderRadius: '4px',
                            padding: '2px 6px',
                            fontSize: '11px',
                            cursor: 'pointer'
                          }}
                        >
                          Assign
                        </button>
                      </div>
                    </td>

                    {/* Dynamic sales collected */}
                    <td style={{ padding: '16px 24px', fontWeight: 700, color: 'var(--warning)', fontSize: '15px' }}>
                      ₱{conductor.total_sales_today.toLocaleString(undefined, { minimumFractionDigits: 2 })}
                    </td>

                    {/* Last Sync */}
                    <td style={{ padding: '16px 24px', fontSize: '13px', color: 'var(--text-secondary)' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <Clock size={14} />
                        <span>{formattedSync}</span>
                      </div>
                    </td>

                    {/* Action Links */}
                    <td style={{ padding: '16px 24px', textAlign: 'center' }}>
                      <div style={{ display: 'flex', gap: '8px', justifyContent: 'center' }}>
                        <Link 
                          to={`/audit?search=${encodeURIComponent(conductor.name)}`}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            color: 'var(--accent)',
                            fontSize: '12px',
                            fontWeight: 600,
                            textDecoration: 'none',
                            background: 'rgba(0, 210, 255, 0.1)',
                            padding: '4px 8px',
                            borderRadius: '4px'
                          }}
                        >
                          Logs <ArrowRight size={12} />
                        </Link>
                        <button 
                          onClick={() => handleDelete(conductor.id)}
                          style={{
                            background: 'transparent',
                            border: '1px solid var(--danger)',
                            color: 'var(--danger)',
                            padding: '6px 12px',
                            borderRadius: '4px',
                            cursor: 'pointer',
                            fontSize: '12px',
                            fontWeight: 600
                          }}
                        >
                          Revoke
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Add Conductor Modal */}
      {showAddModal && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
          display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 9999
        }}>
          <div className="glass-panel" style={{ padding: '24px', width: '100%', maxWidth: '400px', borderRadius: '12px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px' }}>Add New Conductor</h3>
            <form onSubmit={handleAddSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Full Name</label>
                <input required type="text" value={newStaff.name} onChange={e => setNewStaff({...newStaff, name: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
              </div>
              <div style={{ display: 'flex', gap: '12px' }}>
                <div style={{ flex: 1 }}>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Contact Number</label>
                  <input type="text" placeholder="Optional" value={newStaff.contact_number} onChange={e => setNewStaff({...newStaff, contact_number: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
                </div>
                <div style={{ flex: 1 }}>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Login Username</label>
                  <input required type="text" value={newStaff.username} onChange={e => setNewStaff({...newStaff, username: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
                </div>
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Home Address</label>
                <input type="text" placeholder="Optional" value={newStaff.address} onChange={e => setNewStaff({...newStaff, address: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Password</label>
                <input required type="password" value={newStaff.password} onChange={e => setNewStaff({...newStaff, password: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Assigned Vehicle (Optional)</label>
                <input type="text" placeholder="e.g. BUS-101" value={newStaff.assigned_vehicle} onChange={e => setNewStaff({...newStaff, assigned_vehicle: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
              </div>
              <div style={{ display: 'flex', gap: '12px', marginTop: '8px' }}>
                <button type="button" onClick={() => setShowAddModal(false)} style={{ flex: 1, padding: '10px', borderRadius: '4px', border: '1px solid var(--border)', background: 'transparent', color: '#fff', cursor: 'pointer' }}>Cancel</button>
                <button type="submit" className="btn-primary" style={{ flex: 1, padding: '10px', borderRadius: '4px', cursor: 'pointer' }}>Create Account</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {showEditModal && editingConductor && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
          display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 9999
        }}>
          <div className="glass-panel" style={{ padding: '24px', width: '100%', maxWidth: '400px', borderRadius: '12px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px' }}>Assign Vehicle to {editingConductor.name}</h3>
            <form onSubmit={handleEditSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Assigned Vehicle</label>
                <select 
                  value={editingConductor.assigned_vehicle === 'N/A' ? '' : editingConductor.assigned_vehicle} 
                  onChange={e => setEditingConductor({...editingConductor, assigned_vehicle: e.target.value || 'N/A'})} 
                  style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }}
                >
                  <option value="">-- No Vehicle Assigned --</option>
                  {vehicles.map(v => (
                    <option key={v.id} value={v.id}>
                      {v.id} {v.assigned_conductor_id === editingConductor.id ? '(Currently Assigned)' : v.assigned_conductor_id ? '(Assigned to someone else)' : '(Available)'}
                    </option>
                  ))}
                </select>
                <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '8px', lineHeight: 1.4 }}>
                  Selecting a vehicle that is currently assigned to another conductor will automatically reassign it to {editingConductor.name}.
                </div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '16px' }}>
                <button type="button" onClick={() => setShowEditModal(false)} style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-secondary)', padding: '8px 16px', borderRadius: '8px', cursor: 'pointer' }}>Cancel</button>
                <button type="submit" className="btn-primary" style={{ padding: '8px 16px' }}>Save Assignment</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
