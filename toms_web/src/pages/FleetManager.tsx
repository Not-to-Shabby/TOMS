import { useState, useEffect } from 'react';
import { Plus, Trash2, Bus, Search, RefreshCw } from 'lucide-react';
import axios from 'axios';

const API_URL = 'http://localhost:3000/api';

interface Vehicle {
  id: string;
  plate_number: string;
  max_capacity: number;
  status: string;
  assigned_route_id?: number | null;
  assigned_conductor_id?: number | null;
  assigned_route_name?: string | null;
  assigned_conductor_name?: string | null;
}

export default function FleetManager() {
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);
  const [newVehicle, setNewVehicle] = useState({ id: '', plate_number: '', max_capacity: 20 });
  
  const [routes, setRoutes] = useState<any[]>([]);
  const [conductors, setConductors] = useState<any[]>([]);
  
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingVehicle, setEditingVehicle] = useState<Vehicle | null>(null);

  const fetchVehicles = () => {
    setLoading(true);
    axios.get(`${API_URL}/vehicles`)
      .then(res => {
        setVehicles(res.data);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to fetch vehicles", err);
        setLoading(false);
      });
  };

  const fetchDependencies = async () => {
    try {
      const [rRes, cRes] = await Promise.all([
        axios.get(`${API_URL}/routes`),
        axios.get(`${API_URL}/conductors`)
      ]);
      setRoutes(rRes.data);
      setConductors(cRes.data);
    } catch (err) {
      console.error("Failed to fetch dependencies", err);
    }
  }

  useEffect(() => {
    fetchVehicles();
    fetchDependencies();
  }, []);

  const handleAddSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await axios.post(`${API_URL}/vehicles`, newVehicle);
      setShowAddModal(false);
      setNewVehicle({ id: '', plate_number: '', max_capacity: 20 });
      fetchVehicles();
    } catch (err: any) {
      alert(err.response?.data?.error || 'Failed to add vehicle');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm(`Are you sure you want to remove ${id} from the fleet?`)) return;
    try {
      await axios.delete(`${API_URL}/vehicles/${id}`);
      fetchVehicles();
    } catch (err) {
      alert('Failed to delete vehicle');
    }
  };

  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingVehicle) return;
    try {
      await axios.put(`${API_URL}/vehicles/${editingVehicle.id}`, {
        plate_number: editingVehicle.plate_number,
        max_capacity: editingVehicle.max_capacity,
        status: editingVehicle.status,
        assigned_route_id: editingVehicle.assigned_route_id || null,
        assigned_conductor_id: editingVehicle.assigned_conductor_id || null,
      });
      setShowEditModal(false);
      setEditingVehicle(null);
      fetchVehicles();
    } catch (err: any) {
      alert(err.response?.data?.error || 'Failed to update vehicle');
    }
  };

  const filteredVehicles = vehicles.filter(v => 
    v.id.toLowerCase().includes(search.toLowerCase()) || 
    v.plate_number.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Fleet Asset Manager</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Manage bus assignments, capacities, and active hardware statuses</div>
        </div>
        <div style={{ display: 'flex', gap: '12px' }}>
          <button 
            onClick={fetchVehicles} 
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
            <RefreshCw size={16} className={loading ? 'spin-anim' : ''} /> Refresh Fleet
          </button>
          <button 
            className="btn-primary"
            onClick={() => setShowAddModal(true)}
            style={{ padding: '8px 16px', display: 'flex', alignItems: 'center', gap: '8px' }}
          >
            <Plus size={18} /> Add Vehicle
          </button>
        </div>
      </header>

      <div className="glass-panel" style={{ padding: '20px' }}>
        <div style={{ position: 'relative', width: '100%', maxWidth: '400px' }}>
          <input 
            type="text" 
            placeholder="Search by ID or Plate Number..." 
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

      <div className="glass-panel" style={{ flex: 1, overflowY: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--border)', background: 'rgba(0,0,0,0.2)', position: 'sticky', top: 0, zIndex: 1 }}>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Vehicle ID</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Plate Number</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Max Capacity</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Dispatch Assignment</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600 }}>Status</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600, textAlign: 'center' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
                  Loading fleet data...
                </td>
              </tr>
            ) : filteredVehicles.length === 0 ? (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
                  No vehicles found in the system.
                </td>
              </tr>
            ) : (
              filteredVehicles.map(vehicle => (
                <tr 
                  key={vehicle.id}
                  style={{ 
                    borderBottom: '1px solid var(--border)', 
                    transition: 'background 0.15s',
                  }}
                  onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'rgba(255, 255, 255, 0.02)'}
                  onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
                >
                  <td style={{ padding: '16px 24px', fontWeight: 700, fontSize: '15px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <Bus size={18} color="var(--accent)" />
                      {vehicle.id}
                    </div>
                  </td>
                  <td style={{ padding: '16px 24px', fontFamily: 'monospace', fontSize: '14px', color: 'var(--text-secondary)' }}>
                    {vehicle.plate_number}
                  </td>
                  <td style={{ padding: '16px 24px', fontWeight: 600 }}>
                    {vehicle.max_capacity} Seats
                  </td>
                  <td style={{ padding: '16px 24px' }}>
                    <div style={{ fontSize: '13px', color: vehicle.assigned_route_name ? 'var(--text-primary)' : 'var(--text-secondary)' }}>
                      📍 {vehicle.assigned_route_name || 'Unassigned Route'}
                    </div>
                    <div style={{ fontSize: '13px', color: vehicle.assigned_conductor_name ? 'var(--text-primary)' : 'var(--text-secondary)', marginTop: '4px' }}>
                      👤 {vehicle.assigned_conductor_name || 'Unassigned Conductor'}
                    </div>
                  </td>
                  <td style={{ padding: '16px 24px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <div style={{ 
                        width: '8px', height: '8px', borderRadius: '50%', 
                        background: vehicle.status === 'Active' ? 'var(--success)' : 'var(--text-secondary)'
                      }} />
                      <span style={{ fontSize: '13px', fontWeight: 600, color: vehicle.status === 'Active' ? 'var(--success)' : 'var(--text-secondary)' }}>
                        {vehicle.status}
                      </span>
                    </div>
                  </td>
                  <td style={{ padding: '16px 24px', textAlign: 'center' }}>
                    <button 
                      onClick={() => handleDelete(vehicle.id)}
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
                      <Trash2 size={14} style={{ display: 'inline', verticalAlign: 'text-bottom', marginRight: '4px' }} /> 
                      Remove
                    </button>
                    <button 
                      onClick={() => {
                        setEditingVehicle({ ...vehicle });
                        setShowEditModal(true);
                      }}
                      style={{
                        background: 'var(--bg-card)',
                        border: '1px solid var(--border)',
                        color: 'var(--text-primary)',
                        padding: '6px 12px',
                        borderRadius: '4px',
                        cursor: 'pointer',
                        fontSize: '12px',
                        fontWeight: 600,
                        marginLeft: '8px'
                      }}
                    >
                      Edit / Dispatch
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {showAddModal && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
          display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 9999
        }}>
          <div className="glass-panel" style={{ padding: '24px', width: '100%', maxWidth: '400px', borderRadius: '12px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px' }}>Register Vehicle</h3>
            <form onSubmit={handleAddSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Vehicle ID (e.g. BUS-101)</label>
                <input required type="text" value={newVehicle.id} onChange={e => setNewVehicle({...newVehicle, id: e.target.value.toUpperCase()})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff', fontFamily: 'monospace' }} />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Plate Number</label>
                <input required type="text" value={newVehicle.plate_number} onChange={e => setNewVehicle({...newVehicle, plate_number: e.target.value.toUpperCase()})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff', fontFamily: 'monospace' }} />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Passenger Capacity</label>
                <input required type="number" min="1" max="100" value={newVehicle.max_capacity} onChange={e => setNewVehicle({...newVehicle, max_capacity: parseInt(e.target.value)})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '16px' }}>
                <button type="button" onClick={() => setShowAddModal(false)} style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-secondary)', padding: '8px 16px', borderRadius: '8px', cursor: 'pointer' }}>Cancel</button>
                <button type="submit" className="btn-primary" style={{ padding: '8px 16px' }}>Save Vehicle</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {showEditModal && editingVehicle && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
          display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 9999
        }}>
          <div className="glass-panel" style={{ padding: '24px', width: '100%', maxWidth: '400px', borderRadius: '12px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px' }}>Dispatch & Edit {editingVehicle.id}</h3>
            <form onSubmit={handleEditSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Plate Number</label>
                <input required type="text" value={editingVehicle.plate_number} onChange={e => setEditingVehicle({...editingVehicle, plate_number: e.target.value.toUpperCase()})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff', fontFamily: 'monospace' }} />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Passenger Capacity</label>
                <input required type="number" min="1" max="100" value={editingVehicle.max_capacity} onChange={e => setEditingVehicle({...editingVehicle, max_capacity: parseInt(e.target.value)})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }} />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Status</label>
                <select value={editingVehicle.status} onChange={e => setEditingVehicle({...editingVehicle, status: e.target.value})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }}>
                  <option value="Active">Active</option>
                  <option value="Maintenance">Maintenance</option>
                  <option value="Offline">Offline</option>
                </select>
              </div>
              <div style={{ borderTop: '1px solid var(--border)', paddingTop: '16px', marginTop: '4px' }}>
                <h4 style={{ margin: '0 0 12px 0', fontSize: '14px', color: 'var(--accent)' }}>Dispatch Assignments</h4>
                <div style={{ marginBottom: '12px' }}>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Assign Route</label>
                  <select value={editingVehicle.assigned_route_id || ''} onChange={e => setEditingVehicle({...editingVehicle, assigned_route_id: e.target.value ? parseInt(e.target.value) : null})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }}>
                    <option value="">-- No Route Assigned --</option>
                    {routes.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                  </select>
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Assign Conductor</label>
                  <select value={editingVehicle.assigned_conductor_id || ''} onChange={e => setEditingVehicle({...editingVehicle, assigned_conductor_id: e.target.value ? parseInt(e.target.value) : null})} style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', border: '1px solid var(--border)', color: '#fff' }}>
                    <option value="">-- No Conductor Assigned --</option>
                    {conductors.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '16px' }}>
                <button type="button" onClick={() => setShowEditModal(false)} style={{ background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-secondary)', padding: '8px 16px', borderRadius: '8px', cursor: 'pointer' }}>Cancel</button>
                <button type="submit" className="btn-primary" style={{ padding: '8px 16px' }}>Save Changes</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
