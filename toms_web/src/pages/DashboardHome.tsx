import { useState, useEffect, useRef, useCallback } from 'react';
import { Bus, Users, MapPin, X, Activity, Battery } from 'lucide-react';
import { io } from 'socket.io-client';
import axios from 'axios';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

const API_URL = 'http://localhost:3000/api';
const SOCKET_URL = 'http://localhost:3000';

interface FleetStatus {
  vehicle_id: string;
  current_route?: string;
  occupancy_now: number;
  max_capacity: number;
  seat_map: string | null;
  last_updated: string;
  daily_revenue: number;
  current_lat?: number;
  current_lon?: number;
  current_conductor_name?: string;
  assigned_route_name?: string;
  assigned_conductor_name?: string;
  status?: string;
}

interface TickerEvent {
  id: string;
  message: string;
  timestamp: string;
}

interface SeatStatus {
  slot: number;
  uid: string;
  state: 'active' | 'paid' | 'alarming';
  dest: string;
  type: string;
}

export default function DashboardHome() {
  const [fleet, setFleet] = useState<Record<string, FleetStatus>>({});
  const [selectedBus, setSelectedBus] = useState<FleetStatus | null>(null);
  const [ticker, setTicker] = useState<TickerEvent[]>([]);

  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markersRef = useRef<Record<string, maplibregl.Marker>>({});

  // Init MapLibre map once
  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: mapContainerRef.current,
      style: 'https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json',
      center: [124.2422, 8.2280],
      zoom: 12,
    });
    map.addControl(new maplibregl.NavigationControl(), 'top-right');
    mapRef.current = map;
    return () => { map.remove(); mapRef.current = null; };
  }, []);

  // Update markers whenever fleet state changes
  const updateMarkers = useCallback((currentFleet: Record<string, FleetStatus>) => {
    const map = mapRef.current;
    if (!map) return;

    // Remove stale markers OR offline/maintenance markers
    Object.keys(markersRef.current).forEach(id => {
      if (!currentFleet[id] || currentFleet[id].status !== 'Active') {
        markersRef.current[id].remove();
        delete markersRef.current[id];
      }
    });

    // Add or move markers
    Object.values(currentFleet).forEach(bus => {
      if (bus.status !== 'Active') return;
      if (!bus.current_lat || !bus.current_lon) return;
      if (markersRef.current[bus.vehicle_id]) {
        markersRef.current[bus.vehicle_id].setLngLat([bus.current_lon, bus.current_lat]);
      } else {
        const el = document.createElement('div');
        el.innerHTML = `🚌 ${bus.vehicle_id}`;
        Object.assign(el.style, {
          background: 'var(--accent)',
          color: '#000',
          padding: '4px 8px',
          borderRadius: '20px',
          fontWeight: 'bold',
          fontSize: '12px',
          cursor: 'pointer',
          boxShadow: '0 0 10px var(--accent)',
          border: '2px solid #fff',
        });
        el.onclick = () => setSelectedBus(bus);
        const marker = new maplibregl.Marker({ element: el, anchor: 'bottom' })
          .setLngLat([bus.current_lon, bus.current_lat])
          .addTo(map);
        markersRef.current[bus.vehicle_id] = marker;
      }
    });
  }, []);

  useEffect(() => {
    // 1. Fetch initial snapshot
    axios.get(`${API_URL}/fleet/status`).then(res => {
      const initial: Record<string, FleetStatus> = {};
      res.data.forEach((status: any) => {
        initial[status.vehicle_id] = status;
      });
      setFleet(initial);
      updateMarkers(initial);
    }).catch(err => console.error("Failed to fetch fleet status", err));

    // 2. Listen for real-time updates
    const socket = io(SOCKET_URL);
    
    socket.on('fleet_update', (update: FleetStatus) => {
      setFleet(prev => {
        const next = { ...prev, [update.vehicle_id]: update };
        updateMarkers(next);
        return next;
      });

      setSelectedBus(prevSelected => {
        if (prevSelected && prevSelected.vehicle_id === update.vehicle_id) return update;
        return prevSelected;
      });
    });

    socket.on('dispatch_updated', () => {
      // Re-fetch the fleet status when assignments change
      axios.get(`${API_URL}/fleet/status`).then(res => {
        const updated: Record<string, FleetStatus> = {};
        res.data.forEach((status: any) => {
          updated[status.vehicle_id] = status;
        });
        setFleet(updated);
        updateMarkers(updated);
      }).catch(err => console.error("Failed to refresh fleet status after dispatch", err));
    });

    socket.on('new_event', (event: any) => {
      // Create a human readable ticker event
      let msg = '';
      if (event.event_type === 'boarding') msg = `🚌 ${event.vehicle_id}: Passenger Boarded at ${event.boarding_stop || 'Unknown'}`;
      else if (event.event_type === 'payment') msg = `💰 ${event.vehicle_id}: ₱${((event.fare_centavos||0)/100).toFixed(2)} Collected`;
      else if (event.event_type === 'release') msg = `🚶 ${event.vehicle_id}: Passenger Alighted at ${event.destination_stop || 'Unknown'}`;
      else msg = `⚡ ${event.vehicle_id}: ${event.event_type}`;

      const newTicker = {
        id: Math.random().toString(36).substr(2, 9),
        message: msg,
        timestamp: new Date().toLocaleTimeString()
      };
      
      setTicker(prev => [newTicker, ...prev].slice(0, 50));
    });

    return () => {
      socket.disconnect();
    };
  }, []);

  const totalActive = Object.values(fleet).filter(bus => bus.status === 'Active').length;
  const totalOccupancy = Object.values(fleet).reduce((acc, bus) => acc + bus.occupancy_now, 0);
  const totalCapacity = Object.values(fleet).reduce((acc, bus) => acc + bus.max_capacity, 0);
  const grossRevenue = Object.values(fleet).reduce((acc, bus) => acc + (bus.daily_revenue || 0), 0) / 100;

  // Parse seat map
  let seatMapData: SeatStatus[] = [];
  if (selectedBus && selectedBus.seat_map) {
    try {
      seatMapData = JSON.parse(selectedBus.seat_map);
    } catch (e) {
      console.error("Failed to parse seat map", e);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', position: 'relative' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Live Fleet Overview</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Real-time occupancy and tracking</div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--success)' }}>
          <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--success)', boxShadow: '0 0 10px var(--success)' }} />
          Live Connection Active
        </div>
      </header>

      <div style={{ display: 'flex', gap: '24px' }}>
        {/* Left Side: Metrics Grid (1/2 width) */}
        <div style={{ flex: 1, display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '24px' }}>
          <div className="glass-panel" style={{ padding: '24px', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
            <div style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Bus size={16} /> Active Vehicles
            </div>
            <div style={{ fontSize: '36px', fontWeight: 800, color: 'var(--success)' }}>{totalActive}</div>
          </div>
          
          <div className="glass-panel" style={{ padding: '24px', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
            <div style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Users size={16} /> Current System Occupancy
            </div>
            <div style={{ fontSize: '36px', fontWeight: 800, color: 'var(--accent)' }}>
              {totalOccupancy} <span style={{ fontSize: '20px', color: 'var(--text-secondary)' }}>/ {totalCapacity || '-'}</span>
            </div>
          </div>
          
          <div className="glass-panel" style={{ padding: '24px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gridColumn: 'span 2' }}>
            <div style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <MapPin size={16} /> Gross Revenue
            </div>
            <div style={{ fontSize: '36px', fontWeight: 800, color: 'var(--warning)' }}>
              ₱{grossRevenue.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </div>
          </div>
        </div>

        {/* Right Side: MapLibre GPS Tracker (1/2 width) */}
        <div className="glass-panel" style={{ flex: 1, minHeight: '200px', overflow: 'hidden', borderRadius: '12px', border: '1px solid var(--border)', position: 'relative' }}>
          <div ref={mapContainerRef} style={{ width: '100%', height: '100%' }} />
          {Object.values(fleet).filter(b => !b.current_lat).length === Object.values(fleet).length && (
            <div style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%,-50%)', color: 'rgba(255,255,255,0.4)', textAlign: 'center', pointerEvents: 'none' }}>
              <MapPin size={24} style={{ marginBottom: '4px', opacity: 0.3 }} />
              <div style={{ fontSize: '11px' }}>Waiting for GPS...</div>
            </div>
          )}
        </div>
      </div>
      <div style={{ display: 'flex', gap: '24px', flex: 1, overflow: 'hidden' }}>
        {/* Main Fleet Grid */}
        <div style={{ flex: 3, overflowY: 'auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: '20px' }}>
            {Object.values(fleet).map(bus => {
              const fillPct = bus.max_capacity > 0 ? (bus.occupancy_now / bus.max_capacity) * 100 : 0;
              const isFull = fillPct >= 100;
              // Fake battery for demo: 80% to 100%
              const battery = 80 + (bus.occupancy_now % 20);

            return (
              <div 
                key={bus.vehicle_id} 
                className="glass-panel" 
                style={{ 
                  padding: '20px', 
                  cursor: 'pointer', 
                  transition: 'transform 0.2s, box-shadow 0.2s',
                  opacity: bus.status === 'Active' ? 1 : 0.55
                }}
                onClick={() => setSelectedBus(bus)}
                onMouseEnter={(e) => { if(bus.status === 'Active') e.currentTarget.style.transform = 'translateY(-4px)'; }}
                onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '16px' }}>
                  <div>
                    <h3 style={{ margin: 0, fontSize: '20px', fontWeight: 700 }}>{bus.vehicle_id}</h3>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '4px', color: 'var(--text-secondary)', fontSize: '13px', marginTop: '4px' }}>
                      <MapPin size={12} /> {bus.current_route || bus.assigned_route_name || 'No Route'}
                    </div>
                    {(bus.current_conductor_name || bus.assigned_conductor_name) && (
                      <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                        👤 {bus.current_conductor_name || bus.assigned_conductor_name}
                      </div>
                    )}
                  </div>
                  <div style={{ 
                    padding: '4px 8px', 
                    borderRadius: '4px', 
                    fontSize: '12px', 
                    fontWeight: 700,
                    background: bus.status === 'Offline' ? 'rgba(120,120,120,0.15)' : bus.status === 'Maintenance' ? 'rgba(255,165,0,0.15)' : isFull ? 'rgba(255,107,107,0.2)' : 'rgba(46,213,115,0.2)',
                    color: bus.status === 'Offline' ? 'var(--text-secondary)' : bus.status === 'Maintenance' ? '#ffa500' : isFull ? 'var(--danger)' : 'var(--success)'
                  }}>
                    {bus.status === 'Offline' ? 'OFFLINE' : bus.status === 'Maintenance' ? 'MAINTENANCE' : isFull ? 'FULL' : 'ACTIVE'}
                  </div>
                </div>

                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', marginBottom: '4px' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Daily Revenue</span>
                    <span style={{ fontWeight: 600, color: 'var(--warning)' }}>
                      ₱{((bus.daily_revenue || 0) / 100).toLocaleString(undefined, { minimumFractionDigits: 2 })}
                    </span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', marginBottom: '8px' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Occupancy</span>
                    <span style={{ fontWeight: 600 }}>{bus.occupancy_now} / {bus.max_capacity}</span>
                  </div>
                  
                  {/* Battery Level */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', marginBottom: '8px' }}>
                    <span style={{ color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '4px' }}><Battery size={12}/> Battery Level</span>
                    <span style={{ fontWeight: 600, color: battery > 20 ? 'var(--success)' : 'var(--danger)' }}>{battery}%</span>
                  </div>

                  <div style={{ height: '8px', background: 'var(--bg-dark)', borderRadius: '4px', overflow: 'hidden' }}>
                    <div style={{ 
                      width: `${fillPct}%`, 
                      height: '100%', 
                      background: isFull ? 'var(--danger)' : 'var(--accent)',
                      transition: 'width 0.5s ease-out'
                    }} />
                  </div>
                </div>
              </div>
            );
          })}
          
          {Object.keys(fleet).length === 0 && (
            <div style={{ gridColumn: '1 / -1', textAlign: 'center', padding: '60px', color: 'var(--text-secondary)' }}>
              <Bus size={48} style={{ opacity: 0.3, marginBottom: '16px' }} />
              <h3>No active vehicles</h3>
              <p>When conductors start their shifts and sync data, vehicles will appear here live.</p>
            </div>
          )}
        </div>
        </div>

        {/* Activity Ticker Sidebar */}
        <div className="glass-panel" style={{ flex: 1, minWidth: '300px', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <div style={{ padding: '20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Activity size={18} color="var(--accent)" />
            <h3 style={{ margin: 0, fontSize: '16px' }}>Live Feed</h3>
          </div>
          <div style={{ flex: 1, overflowY: 'auto', padding: '0' }}>
            {ticker.length === 0 ? (
              <div style={{ padding: '40px 20px', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '13px' }}>
                Waiting for incoming events...
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                {ticker.map(item => (
                  <div key={item.id} style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', animation: 'fadeIn 0.3s' }}>
                    <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '4px' }}>{item.timestamp}</div>
                    <div style={{ fontSize: '13px', color: 'var(--text-primary)', lineHeight: '1.4' }}>{item.message}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Seat Map Modal */}
      {selectedBus && (
        <div style={{ 
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, 
          background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
          display: 'flex', justifyContent: 'center', alignItems: 'center',
          zIndex: 100
        }}>
          <div className="glass-panel" style={{ width: '100%', maxWidth: '800px', maxHeight: '90%', display: 'flex', flexDirection: 'column' }}>
            <div style={{ padding: '24px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <h2 style={{ margin: 0, fontSize: '24px' }}>Seat Map: Bus {selectedBus.vehicle_id}</h2>
                <div style={{ color: 'var(--text-secondary)', fontSize: '14px', marginTop: '4px' }}>
                  Last synced: {selectedBus.last_updated ? new Date(selectedBus.last_updated).toLocaleTimeString() : 'Never synced'}
                </div>
              </div>
              <button onClick={() => setSelectedBus(null)} style={{ background: 'transparent', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer' }}>
                <X size={24} />
              </button>
            </div>

            <div style={{ padding: '24px', overflowY: 'auto', flex: 1 }}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: '16px' }}>
                {/* Render full capacity grid */}
                {Array.from({ length: selectedBus.max_capacity }).map((_, i) => {
                  const slotIndex = i + 1;
                  const passenger = seatMapData.find(s => s.slot === slotIndex);
                  
                  let bg = 'var(--bg-dark)';
                  let border = '1px solid var(--border)';
                  let title = 'Empty';
                  
                  if (passenger) {
                    if (passenger.state === 'paid') {
                      bg = 'rgba(46,213,115,0.15)';
                      border = '1px solid var(--success)';
                      title = 'Paid';
                    } else if (passenger.state === 'alarming') {
                      bg = 'rgba(255,107,107,0.15)';
                      border = '1px solid var(--danger)';
                      title = 'ALARM';
                    } else {
                      bg = 'rgba(255,159,67,0.15)';
                      border = '1px solid var(--warning)';
                      title = 'Unpaid';
                    }
                  }

                  return (
                    <div key={slotIndex} style={{ 
                      background: bg, border: border, borderRadius: '8px', padding: '16px',
                      display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', gap: '8px'
                    }}>
                      <div style={{ fontSize: '20px', fontWeight: 800, color: passenger ? 'var(--text-primary)' : 'var(--text-secondary)' }}>
                        {slotIndex}
                      </div>
                      <div style={{ fontSize: '12px', fontWeight: 600, color: passenger ? (passenger.state === 'alarming' ? 'var(--danger)' : passenger.state === 'paid' ? 'var(--success)' : 'var(--warning)') : 'var(--text-secondary)' }}>
                        {title}
                      </div>
                      {passenger && (
                        <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                          {passenger.dest || 'Unknown'}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
