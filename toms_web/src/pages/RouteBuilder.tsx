import { useState, useEffect } from 'react';
import Map, { Marker, Source, Layer } from 'react-map-gl/maplibre';
import type { MapMouseEvent, ViewStateChangeEvent } from 'react-map-gl/maplibre';
import { DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import { arrayMove, SortableContext, sortableKeyboardCoordinates, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Trash2, Save, Calculator, MapPin, Activity, X, Clock, ChevronDown, ChevronRight } from 'lucide-react';
import axios from 'axios';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

const API_URL = 'http://localhost:3000/api';
const INITIAL_VIEW_STATE = {
  longitude: 124.24,
  latitude: 8.22,
  zoom: 12
};

// Haversine distance helper for segment splitting
const distance = (lat1: number, lon1: number, lat2: number, lon2: number) => {
  const p = 0.017453292519943295;
  const c = Math.cos;
  const a = 0.5 - c((lat2 - lat1) * p)/2 + 
          c(lat1 * p) * c(lat2 * p) * 
          (1 - c((lon2 - lon1) * p))/2;
  return 12742 * Math.asin(Math.sqrt(a));
};

export interface TransitStop {
  id: string | number;
  name: string;
  lat: number;
  lon: number;
  radius_m: number;
}

export interface RouteConfig {
  id: string | number;
  name: string;
  base_fare: number;
  per_km_fare: number;
}

export interface RouteSchedule {
  active_days: string;
  start_time: string;
  end_time: string;
}

export interface RoutePath {
  id: string | number;
  name: string;
  color: string;
  stops: TransitStop[];
  schedule?: RouteSchedule;
}

interface SortableStopProps {
  stop: TransitStop;
  index: number;
  onRemove: (id: string | number) => void;
  onNameChange: (id: string | number, newName: string) => void;
  onRadiusChange: (id: string | number, newRadius: number) => void;
}

function SortableStop({ stop, index, onRemove, onNameChange, onRadiusChange }: SortableStopProps) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({ id: stop.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div ref={setNodeRef} className="glass-panel" style={{ padding: '12px', display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '8px', ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
        <div {...attributes} {...listeners} style={{ cursor: 'grab', color: 'var(--text-secondary)' }}>
          <GripVertical size={20} />
        </div>
        <div style={{ fontWeight: 800, color: 'var(--accent)', minWidth: '24px' }}>{index + 1}</div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '4px' }}>
          <input 
            type="text" 
            value={stop.name}
            onChange={(e) => onNameChange(stop.id, e.target.value)}
            style={{ background: 'transparent', border: 'none', color: 'var(--text-primary)', fontWeight: 600, fontSize: '16px', outline: 'none', width: '100%' }}
          />
        </div>
        <button onClick={() => onRemove(stop.id)} style={{ background: 'transparent', border: 'none', color: 'var(--danger)', cursor: 'pointer', padding: '4px' }}>
          <Trash2 size={18} />
        </button>
      </div>

      {/* Geofence Editor */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '32px' }}>
        <Activity size={14} color="var(--text-secondary)" />
        <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Alarm Radius:</span>
        <input 
          type="range" 
          min="20" max="300" step="10"
          value={stop.radius_m || 100}
          onChange={(e) => onRadiusChange(stop.id, parseInt(e.target.value))}
          style={{ flex: 1, cursor: 'pointer' }}
        />
        <span style={{ fontSize: '12px', color: 'var(--accent)', fontWeight: 700, width: '40px', textAlign: 'right' }}>
          {stop.radius_m || 100}m
        </span>
      </div>
    </div>
  );
}

const parse24hTime = (timeStr: string) => {
  const [hStr, mStr] = (timeStr || '00:00').split(':');
  let h = parseInt(hStr, 10);
  const m = parseInt(mStr, 10);
  if (isNaN(h)) h = 0;
  const period = h >= 12 ? 'PM' : 'AM';
  h = h % 12;
  if (h === 0) h = 12;
  return { hour: h, minute: isNaN(m) ? 0 : m, period };
};

const formatTo24hTime = (hour: number, minute: number, period: string) => {
  let h = hour;
  if (period === 'PM' && h < 12) h += 12;
  if (period === 'AM' && h === 12) h = 0;
  const hStr = h.toString().padStart(2, '0');
  const mStr = minute.toString().padStart(2, '0');
  return `${hStr}:${mStr}`;
};

interface TimeDropdownSelectorProps {
  label: string;
  value: string;
  onChange: (val: string) => void;
}

function TimeDropdownSelector({ label, value, onChange }: TimeDropdownSelectorProps) {
  const { hour, minute, period } = parse24hTime(value);

  const hours = Array.from({ length: 12 }, (_, i) => i + 1);
  const minutes = Array.from({ length: 60 }, (_, i) => i);

  const handleHourChange = (newHour: number) => {
    onChange(formatTo24hTime(newHour, minute, period));
  };

  const handleMinuteChange = (newMin: number) => {
    onChange(formatTo24hTime(hour, newMin, period));
  };

  const handlePeriodChange = (newPeriod: string) => {
    onChange(formatTo24hTime(hour, minute, newPeriod));
  };

  return (
    <div style={{ flex: 1, minWidth: '140px' }}>
      <label style={{ display: 'block', fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '6px' }}>{label}</label>
      <div style={{ 
        display: 'flex', 
        alignItems: 'center', 
        gap: '4px',
        padding: '8px 12px', 
        borderRadius: '6px', 
        background: 'var(--bg-dark)', 
        border: '1px solid var(--border)',
        justifyContent: 'space-between'
      }}>
        <select 
          value={hour} 
          onChange={(e) => handleHourChange(parseInt(e.target.value, 10))}
          style={{ 
            background: 'transparent', 
            color: 'var(--text-primary)', 
            border: 'none', 
            outline: 'none',
            fontSize: '14px',
            fontWeight: 600,
            cursor: 'pointer',
            padding: '2px',
            width: '42px',
            textAlign: 'center'
          }}
        >
          {hours.map(h => (
            <option key={h} value={h} style={{ background: '#1c1e22', color: '#fff' }}>
              {h.toString().padStart(2, '0')}
            </option>
          ))}
        </select>
        
        <span style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>:</span>
        
        <select 
          value={minute} 
          onChange={(e) => handleMinuteChange(parseInt(e.target.value, 10))}
          style={{ 
            background: 'transparent', 
            color: 'var(--text-primary)', 
            border: 'none', 
            outline: 'none',
            fontSize: '14px',
            fontWeight: 600,
            cursor: 'pointer',
            padding: '2px',
            width: '42px',
            textAlign: 'center'
          }}
        >
          {minutes.map(m => (
            <option key={m} value={m} style={{ background: '#1c1e22', color: '#fff' }}>
              {m.toString().padStart(2, '0')}
            </option>
          ))}
        </select>

        <select 
          value={period} 
          onChange={(e) => handlePeriodChange(e.target.value)}
          style={{ 
            background: 'transparent', 
            color: 'var(--accent)', 
            border: 'none', 
            outline: 'none',
            fontSize: '13px',
            fontWeight: 700,
            cursor: 'pointer',
            padding: '2px 4px',
            marginLeft: '4px'
          }}
        >
          <option value="AM" style={{ background: '#1c1e22', color: 'var(--accent)' }}>AM</option>
          <option value="PM" style={{ background: '#1c1e22', color: 'var(--accent)' }}>PM</option>
        </select>
      </div>
    </div>
  );
}

const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];

export default function RouteBuilder() {
  const [routes, setRoutes] = useState<RouteConfig[]>([]);
  const [activeRouteId, setActiveRouteId] = useState<string | number>('');
  
  const [stops, setStops] = useState<TransitStop[]>([]);
  const [baseFare, setBaseFare] = useState<number>(15.0);
  const [perKmFare, setPerKmFare] = useState<number>(2.5);

  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [activeDays, setActiveDays] = useState<string>('mon,tue,wed,thu,fri,sat,sun');
  const [startTime, setStartTime] = useState('00:00');
  const [endTime, setEndTime] = useState('23:59');
  const [scheduleCollapsed, setScheduleCollapsed] = useState(false);
  const [fareCollapsed, setFareCollapsed] = useState(false);

  // Custom Glassmorphic Dialog Modal states
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogType, setDialogType] = useState<'prompt' | 'confirm'>('prompt');
  const [dialogTitle, setDialogTitle] = useState('');
  const [dialogPlaceholder, setDialogPlaceholder] = useState('');
  const [dialogValue, setDialogValue] = useState('');
  const [dialogOnConfirm, setDialogOnConfirm] = useState<((val: string) => void) | null>(null);

  const openPromptDialog = (title: string, placeholder: string, defaultValue: string, onConfirm: (val: string) => void) => {
    setDialogTitle(title);
    setDialogPlaceholder(placeholder);
    setDialogValue(defaultValue);
    setDialogType('prompt');
    setDialogOnConfirm(() => onConfirm);
    setDialogOpen(true);
  };

  const openConfirmDialog = (title: string, onConfirm: () => void) => {
    setDialogTitle(title);
    setDialogType('confirm');
    setDialogOnConfirm(() => (val: string) => onConfirm());
    setDialogOpen(true);
  };

  const handleDialogSubmit = () => {
    if (dialogOnConfirm) {
      dialogOnConfirm(dialogValue);
    }
    setDialogOpen(false);
    setDialogValue('');
  };

  const toggleDay = (day: string) => {
    const daysArray = activeDays ? activeDays.split(',').map(d => d.trim()).filter(Boolean) : [];
    let newDays;
    if (daysArray.includes(day)) {
      newDays = daysArray.filter(d => d !== day);
    } else {
      newDays = [...daysArray, day].sort((a, b) => WEEKDAYS.indexOf(a) - WEEKDAYS.indexOf(b));
    }
    setActiveDays(newDays.join(','));
  };

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [viewState, setViewState] = useState(INITIAL_VIEW_STATE);
  
  const [paths, setPaths] = useState<RoutePath[]>([]);
  const [activePathId, setActivePathId] = useState<string | number>('');
  const [pathGeoJSONs, setPathGeoJSONs] = useState<Record<string, any>>({});
  
  const [osrmGeoJSON, setOsrmGeoJSON] = useState<any>(null);
  const [enableSnapping, setEnableSnapping] = useState<boolean>(true);
  const [cursor, setCursor] = useState('grab');
  const [ghostMarker, setGhostMarker] = useState<{lat: number, lon: number, index: number} | null>(null);
  const [isDraggingGhost, setIsDraggingGhost] = useState(false);

  useEffect(() => {
    fetchRoutes();
  }, []);

  useEffect(() => {
    if (activeRouteId) {
      const activeRoute = routes.find(r => r.id.toString() === activeRouteId.toString());
      if (activeRoute) {
        setBaseFare(activeRoute.base_fare || 15.0);
        setPerKmFare(activeRoute.per_km_fare || 2.5);
      }
      fetchPaths(activeRouteId);
    } else {
      setPaths([]);
      setActivePathId('');
      setStops([]);
      setOsrmGeoJSON(null);
      setPathGeoJSONs({});
    }
  }, [activeRouteId, routes]);

  // Fetch snapped polyline from OSRM when stops change
  useEffect(() => {
    if (stops.length < 2 || !enableSnapping || !activePathId) {
      setOsrmGeoJSON(null);
      return;
    }
    
    // Throttle OSRM requests using a simple timeout
    const timer = setTimeout(async () => {
      try {
        const coords = stops.map(s => `${s.lon},${s.lat}`).join(';');
        const res = await axios.get(`http://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson`);
        if (res.data && res.data.routes && res.data.routes[0]) {
          const geo = res.data.routes[0].geometry;
          setOsrmGeoJSON(geo);
          setPathGeoJSONs(prev => ({ ...prev, [activePathId.toString()]: geo }));
        }
      } catch (err) {
        console.error("OSRM Routing failed", err);
        // Fallback to straight lines if OSRM is down
        setOsrmGeoJSON(null);
      }
    }, 800);

    return () => clearTimeout(timer);
  }, [stops, enableSnapping, activePathId]);

  const fetchRoutes = async () => {
    try {
      setLoading(true);
      const response = await axios.get(`${API_URL}/routes`);
      setRoutes(response.data);
      if (response.data.length > 0) {
        setActiveRouteId(response.data[0].id);
      }
    } catch (error) {
      console.error('Failed to fetch routes', error);
      alert('Failed to connect to backend API');
    } finally {
      setLoading(false);
    }
  };

  const fetchPaths = async (routeId: string | number) => {
    try {
      setLoading(true);
      const response = await axios.get(`${API_URL}/routes/${routeId}/paths`);
      const data = response.data;
      setPaths(data);
      if (data.length > 0) {
        setActivePathId(data[0].id);
        setStops(data[0].stops.map((s: any) => ({ ...s, id: s.id.toString() })));
        if (data[0].schedule) {
          setScheduleEnabled(true);
          setActiveDays(data[0].schedule.active_days);
          setStartTime(data[0].schedule.start_time);
          setEndTime(data[0].schedule.end_time);
        } else {
          setScheduleEnabled(false);
          setActiveDays('mon,tue,wed,thu,fri,sat,sun');
          setStartTime('00:00');
          setEndTime('23:59');
        }
      } else {
        setActivePathId('');
        setStops([]);
        setScheduleEnabled(false);
      }

      // Pre-fetch OSRM for all paths
      const newGeoJSONs: Record<string, any> = {};
      await Promise.all(data.map(async (p: any) => {
        if (p.stops && p.stops.length >= 2) {
           const coords = p.stops.map((s: any) => `${s.lon},${s.lat}`).join(';');
           try {
             const res = await axios.get(`http://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson`);
             if (res.data?.routes?.[0]) newGeoJSONs[p.id.toString()] = res.data.routes[0].geometry;
           } catch (e) {}
        }
      }));
      setPathGeoJSONs(newGeoJSONs);
    } catch (error) {
      console.error('Failed to fetch paths', error);
    } finally {
      setLoading(false);
    }
  };

  const handlePathSwitch = (pathId: string | number, currentPaths?: RoutePath[]) => {
    const pathList = currentPaths ?? paths;
    // Save current stops to paths state before switching
    const updatedPaths = [...pathList];
    const pathIdx = updatedPaths.findIndex(p => p.id === activePathId);
    if (pathIdx !== -1) {
      updatedPaths[pathIdx].stops = stops;
      if (scheduleEnabled) {
        updatedPaths[pathIdx].schedule = { active_days: activeDays, start_time: startTime, end_time: endTime };
      } else {
        updatedPaths[pathIdx].schedule = undefined;
      }
      setPaths(updatedPaths);
    }
    
    setActivePathId(pathId);
    const newPath = updatedPaths.find(p => p.id === pathId);
    if (newPath) {
      setStops(newPath.stops);
      if (newPath.schedule) {
        setScheduleEnabled(true);
        setActiveDays(newPath.schedule.active_days);
        setStartTime(newPath.schedule.start_time);
        setEndTime(newPath.schedule.end_time);
      } else {
        setScheduleEnabled(false);
        setActiveDays('mon,tue,wed,thu,fri,sat,sun');
        setStartTime('00:00');
        setEndTime('23:59');
      }
    }
  };

  const handleAddPath = () => {
    openPromptDialog('Create New Path', 'Enter path name (e.g., Alternative, Inbound)', '', async (name) => {
      if (!name.trim()) return;
      const color = '#' + Math.floor(Math.random()*16777215).toString(16).padStart(6, '0');
      try {
        const response = await axios.post(`${API_URL}/routes/${activeRouteId}/paths`, { name: name.trim(), color });
        const newPath: RoutePath = { ...response.data, stops: [] };
        // Build the new paths array synchronously so handlePathSwitch sees it
        const updatedPaths = [...paths, newPath];
        setPaths(updatedPaths);
        // Pass updated paths explicitly to avoid stale closure
        handlePathSwitch(newPath.id, updatedPaths);
      } catch (e) {
        console.error(e);
        alert('Failed to create path');
      }
    });
  };

  const handleDeletePath = (pathId: string | number) => {
    const pathToDelete = paths.find(p => p.id === pathId);
    if (pathToDelete?.name === 'Primary') {
      alert("Cannot delete the Primary path.");
      return;
    }
    if (paths.length <= 1) {
      alert("Cannot delete the last path.");
      return;
    }
    openConfirmDialog('Are you sure you want to delete this path?', async () => {
      try {
        await axios.delete(`${API_URL}/routes/${activeRouteId}/paths/${pathId}`);
        const updatedPaths = paths.filter(p => p.id !== pathId);
        setPaths(updatedPaths);
        if (activePathId === pathId) {
          handlePathSwitch(updatedPaths[0].id, updatedPaths);
        }
      } catch (e) {
        console.error(e);
        alert('Failed to delete path');
      }
    });
  };

  const handleCreateRoute = () => {
    openPromptDialog('Create New Route', 'Enter new route name', '', async (name) => {
      if (!name.trim()) return;
      try {
        const routeResponse = await axios.post(`${API_URL}/routes`, { name: name.trim() });
        const newRoute = routeResponse.data;
        setRoutes(prev => [...prev, newRoute]);
        setActiveRouteId(newRoute.id);

        // Auto-create Primary path for the new route
        const pathResponse = await axios.post(`${API_URL}/routes/${newRoute.id}/paths`, {
          name: 'Primary',
          color: '#00d4ff'
        });
        const primaryPath: RoutePath = { ...pathResponse.data, stops: [] };
        setPaths([primaryPath]);
        handlePathSwitch(primaryPath.id, [primaryPath]);
      } catch (error) {
        console.error('Failed to create route', error);
        alert('Failed to create route');
      }
    });
  };

  const handleDeleteRoute = () => {
    if (!activeRouteId) return;
    openConfirmDialog('Are you sure you want to permanently delete this ENTIRE route and all its paths?', async () => {
      try {
        await axios.delete(`${API_URL}/routes/${activeRouteId}`);
        const updatedRoutes = routes.filter(r => r.id.toString() !== activeRouteId.toString());
        setRoutes(updatedRoutes);
        if (updatedRoutes.length > 0) {
          setActiveRouteId(updatedRoutes[0].id);
        } else {
          setActiveRouteId('');
        }
      } catch (e) {
        console.error('Failed to delete route', e);
        alert('Failed to delete route');
      }
    });
  };

  const handleSave = async () => {
    if (!activeRouteId || !activePathId) return;
    try {
      setSaving(true);
      // Sync current local stops to paths state
      const currentPaths = [...paths];
      const pathIdx = currentPaths.findIndex(p => p.id === activePathId);
      if (pathIdx !== -1) {
        currentPaths[pathIdx].stops = stops;
        if (scheduleEnabled) {
          currentPaths[pathIdx].schedule = { active_days: activeDays, start_time: startTime, end_time: endTime };
        } else {
          currentPaths[pathIdx].schedule = undefined;
        }
      }

      // Save all paths
      for (const path of currentPaths) {
        await axios.post(`${API_URL}/routes/${activeRouteId}/paths/${path.id}/stops`, path.stops);
      }
      
      // Save schedules
      const schedules = currentPaths
        .filter(p => p.schedule)
        .map(p => ({
          path_id: p.id,
          active_days: p.schedule!.active_days,
          start_time: p.schedule!.start_time,
          end_time: p.schedule!.end_time,
        }));
      await axios.post(`${API_URL}/routes/${activeRouteId}/schedules`, schedules);

      // Save Fare Matrix
      await axios.post(`${API_URL}/routes/${activeRouteId}/fare`, { base_fare: baseFare, per_km_fare: perKmFare });
      alert('All Paths, Schedules, Geofences, and Fare Matrix saved successfully!');
    } catch (error) {
      console.error('Failed to save route', error);
      alert('Failed to save route configuration');
    } finally {
      setSaving(false);
    }
  };

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      setStops((items) => {
        const oldIndex = items.findIndex((i) => i.id === active.id);
        const newIndex = items.findIndex((i) => i.id === over.id);
        return arrayMove(items, oldIndex, newIndex);
      });
    }
  };

  const getInsertionIndex = (lat: number, lon: number) => {
    if (osrmGeoJSON && osrmGeoJSON.coordinates && osrmGeoJSON.coordinates.length > 0) {
      const coords = osrmGeoJSON.coordinates;
      
      // 1. Find indices of stops on the polyline
      const stopIndices = stops.map(stop => {
        let closestIdx = 0;
        let minD = Infinity;
        for (let i = 0; i < coords.length; i++) {
          const d = distance(stop.lat, stop.lon, coords[i][1], coords[i][0]);
          if (d < minD) {
            minD = d;
            closestIdx = i;
          }
        }
        return closestIdx;
      });

      // 2. Find closest coordinate on polyline to the cursor
      let closestClickIdx = 0;
      let minClickD = Infinity;
      for (let i = 0; i < coords.length; i++) {
        const d = distance(lat, lon, coords[i][1], coords[i][0]);
        if (d < minClickD) {
          minClickD = d;
          closestClickIdx = i;
        }
      }

      // 3. Find correct interval
      for (let i = 0; i < stopIndices.length - 1; i++) {
        if (closestClickIdx >= stopIndices[i] && closestClickIdx <= stopIndices[i+1]) {
          return i + 1;
        }
      }
      if (closestClickIdx < stopIndices[0]) return 1;
      return stops.length;
    }

    // Fallback: Straight line calculation
    let bestIndex = 1;
    let minDiff = Infinity;
    for (let i = 0; i < stops.length - 1; i++) {
      const A = stops[i];
      const B = stops[i + 1];
      const distAC = distance(A.lat, A.lon, lat, lon);
      const distCB = distance(lat, lon, B.lat, B.lon);
      const distAB = distance(A.lat, A.lon, B.lat, B.lon);
      const diff = (distAC + distCB) - distAB;
      if (diff < minDiff) {
        minDiff = diff;
        bestIndex = i + 1;
      }
    }
    return bestIndex;
  };

  const handleMapClick = (event: MapMouseEvent) => {
    // If we're dragging a ghost marker, map clicks should be ignored
    if (isDraggingGhost) return;
    
    const { lngLat } = event;
    const newStop: TransitStop = {
      id: `new-${Date.now()}`,
      name: `Waypoint`,
      lat: lngLat.lat,
      lon: lngLat.lng,
      radius_m: 100
    };
    setStops([...stops, newStop]);
  };

  const handleMapMouseMove = (event: MapMouseEvent) => {
    if (isDraggingGhost || stops.length < 2) return;

    const { lngLat, features } = event;
    const isLineHover = features && features.some(f => f.layer.id === 'route-line-hitbox');

    if (isLineHover) {
      setCursor('crosshair');
      const bestIndex = getInsertionIndex(lngLat.lat, lngLat.lng);
      setGhostMarker({ lat: lngLat.lat, lon: lngLat.lng, index: bestIndex });
    } else {
      setCursor('grab');
      setGhostMarker(null);
    }
  };

  const handleMarkerDragEnd = (id: string | number, event: any) => {
    const { lngLat } = event;
    setStops(stops.map(stop => stop.id === id ? { ...stop, lat: lngLat.lat, lon: lngLat.lng } : stop));
  };

  const removeStop = (id: string | number) => {
    setStops(stops.filter(s => s.id !== id));
  };

  const updateStopName = (id: string | number, newName: string) => {
    setStops(stops.map(stop => stop.id === id ? { ...stop, name: newName } : stop));
  };

  const updateStopRadius = (id: string | number, newRadius: number) => {
    setStops(stops.map(stop => stop.id === id ? { ...stop, radius_m: newRadius } : stop));
  };

  // Fallback straight line if OSRM fails or has < 2 stops
  const straightLineGeoJSON = {
    type: 'Feature' as const,
    properties: {},
    geometry: {
      type: 'LineString' as const,
      coordinates: stops.map(stop => [stop.lon, stop.lat])
    }
  };

  const activeLineGeoJSON = osrmGeoJSON || straightLineGeoJSON;

  // Elastic line connecting adjacent stops to the mouse while dragging
  const elasticLineGeoJSON = isDraggingGhost && ghostMarker ? {
    type: 'Feature' as const,
    properties: {},
    geometry: {
      type: 'LineString' as const,
      coordinates: [
        [stops[ghostMarker.index - 1].lon, stops[ghostMarker.index - 1].lat],
        [ghostMarker.lon, ghostMarker.lat],
        stops[ghostMarker.index] 
          ? [stops[ghostMarker.index].lon, stops[ghostMarker.index].lat] 
          : [ghostMarker.lon, ghostMarker.lat]
      ]
    }
  } : null;

  // Generate GeoJSON points for drawing visual Geofence circles
  const geofencePoints = {
    type: 'FeatureCollection' as const,
    features: stops.map(stop => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [stop.lon, stop.lat] },
      properties: { radius_m: stop.radius_m || 100 }
    }))
  };

  return (
    <div style={{ display: 'flex', height: '100%', gap: '16px' }}>
      
      {/* Sidebar List */}
      <div className="glass-panel" style={{ width: '420px', height: '100%', display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: '24px', borderBottom: '1px solid var(--border)' }}>
          <h2 style={{ fontSize: '24px', fontWeight: 700, margin: 0 }}>Route Builder</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px', fontSize: '14px', marginBottom: '16px' }}>Configure stops, fares, and alarm radii.</div>
          
          <div style={{ display: 'flex', gap: '8px' }}>
            <select 
              value={activeRouteId} 
              onChange={(e) => setActiveRouteId(e.target.value)}
              style={{ flex: 1, padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
            >
              {routes.map(r => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
            <button className="btn-primary" style={{ padding: '8px 16px', fontSize: '14px' }} onClick={handleCreateRoute}>New</button>
            <button 
              onClick={handleDeleteRoute}
              style={{ 
                padding: '8px', 
                borderRadius: '4px', 
                background: 'transparent', 
                border: '1px solid var(--danger)', 
                color: 'var(--danger)', 
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}
              title="Delete Route"
            >
              <Trash2 size={16} />
            </button>
          </div>
        </div>

        {/* --- Path Switcher Tabs --- */}
        {activeRouteId && (
          <div style={{ display: 'flex', gap: '8px', padding: '16px 24px', borderBottom: '1px solid var(--border)', overflowX: 'auto' }}>
            {paths.map(p => (
              <div
                key={p.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '4px',
                  padding: '4px 12px',
                  borderRadius: '16px',
                  border: `2px solid ${p.color}`,
                  background: p.id === activePathId ? p.color : 'transparent',
                  color: p.id === activePathId ? '#000' : p.color,
                  fontWeight: 600,
                  fontSize: '12px',
                }}
              >
                <button 
                  onClick={() => handlePathSwitch(p.id)}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: 'inherit',
                    fontWeight: 'inherit',
                    fontSize: 'inherit',
                    cursor: 'pointer',
                    whiteSpace: 'nowrap',
                    padding: 0
                  }}
                >
                  {p.name}
                </button>
                {p.id === activePathId && paths.length > 1 && p.name !== 'Primary' && (
                  <button 
                    onClick={() => handleDeletePath(p.id)}
                    style={{
                      background: 'transparent',
                      border: 'none',
                      color: 'inherit',
                      cursor: 'pointer',
                      padding: '2px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      opacity: 0.6
                    }}
                    onMouseEnter={(e) => e.currentTarget.style.opacity = '1'}
                    onMouseLeave={(e) => e.currentTarget.style.opacity = '0.6'}
                  >
                    <X size={14} />
                  </button>
                )}
              </div>
            ))}
            <button onClick={handleAddPath} style={{ padding: '6px 12px', borderRadius: '16px', border: '1px dashed var(--text-secondary)', background: 'transparent', color: 'var(--text-secondary)', fontSize: '12px', cursor: 'pointer', whiteSpace: 'nowrap' }}>+ Add Path</button>
          </div>
        )}

        {/* Path Schedule Panel */}
        {activePathId && (
          <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--border)', background: 'rgba(0,0,0,0.1)' }}>
            <div style={{ 
              display: 'flex', 
              alignItems: 'center', 
              justifyContent: 'space-between', 
              gap: '16px',
              width: '100%',
              marginBottom: (scheduleCollapsed || !scheduleEnabled) ? '0' : '12px' 
            }}>
              <div 
                onClick={() => {
                  if (scheduleEnabled) {
                    setScheduleCollapsed(!scheduleCollapsed);
                  }
                }}
                style={{ 
                  display: 'flex', 
                  alignItems: 'center', 
                  gap: '6px', 
                  cursor: scheduleEnabled ? 'pointer' : 'default', 
                  userSelect: 'none',
                  flex: 1,
                  minWidth: 0
                }}
              >
                {scheduleEnabled ? (
                  scheduleCollapsed ? <ChevronRight size={16} color="var(--text-secondary)" /> : <ChevronDown size={16} color="var(--text-secondary)" />
                ) : (
                  <div style={{ width: '16px' }} />
                )}
                
                <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <Clock size={16} color="var(--accent)" style={{ flexShrink: 0 }} />
                    <span style={{ fontWeight: 600, fontSize: '14px', color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      Schedule Restrictions
                    </span>
                  </div>
                  {scheduleCollapsed && scheduleEnabled && (
                    <div style={{ fontSize: '11px', color: 'var(--accent)', marginTop: '2px', fontWeight: 500, opacity: 0.9 }}>
                      {activeDays.split(',').length === 7 ? 'Everyday' : activeDays.split(',').map(d => d.charAt(0).toUpperCase() + d.slice(1)).join(', ')} ({parse24hTime(startTime).hour}:{parse24hTime(startTime).minute.toString().padStart(2, '0')} {parse24hTime(startTime).period} - {parse24hTime(endTime).hour}:{parse24hTime(endTime).minute.toString().padStart(2, '0')} {parse24hTime(endTime).period})
                    </div>
                  )}
                </div>
              </div>
              
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)', userSelect: 'none' }}>Limit</span>
                <div 
                  onClick={(e) => {
                    e.stopPropagation();
                    const nextVal = !scheduleEnabled;
                    setScheduleEnabled(nextVal);
                    if (nextVal) {
                      setScheduleCollapsed(false);
                    }
                  }}
                  style={{
                    width: '36px',
                    height: '20px',
                    borderRadius: '10px',
                    background: scheduleEnabled ? 'var(--accent)' : 'rgba(255,255,255,0.1)',
                    position: 'relative',
                    cursor: 'pointer',
                    transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
                    display: 'flex',
                    alignItems: 'center',
                    padding: '2px',
                    border: '1px solid ' + (scheduleEnabled ? 'var(--accent)' : 'rgba(255,255,255,0.15)')
                  }}
                >
                  <div style={{
                    width: '14px',
                    height: '14px',
                    borderRadius: '50%',
                    background: '#fff',
                    transform: scheduleEnabled ? 'translateX(16px)' : 'translateX(0px)',
                    transition: 'transform 0.25s cubic-bezier(0.4, 0, 0.2, 1)',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.4)'
                  }} />
                </div>
              </div>
            </div>
            
            <div style={{
              maxHeight: (scheduleCollapsed || !scheduleEnabled) ? '0' : '220px',
              opacity: (scheduleCollapsed || !scheduleEnabled) ? 0 : 1,
              overflow: 'hidden',
              transition: 'max-height 0.35s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.25s ease-in-out',
              marginTop: (scheduleCollapsed || !scheduleEnabled) ? '0' : '12px'
            }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                <div>
                  <label style={{ display: 'block', fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '8px' }}>Active Days</label>
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                    {WEEKDAYS.map(day => {
                      const isSelected = activeDays.split(',').map(d => d.trim()).includes(day);
                      return (
                        <button
                          key={day}
                          type="button"
                          onClick={() => toggleDay(day)}
                          style={{
                            padding: '6px 12px',
                            borderRadius: '20px',
                            fontSize: '12px',
                            fontWeight: 600,
                            border: '1px solid ' + (isSelected ? 'var(--accent)' : 'var(--border)'),
                            background: isSelected ? 'var(--accent-glow)' : 'transparent',
                            color: isSelected ? 'var(--accent)' : 'var(--text-secondary)',
                            cursor: 'pointer',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          {day.charAt(0).toUpperCase() + day.slice(1)}
                        </button>
                      );
                    })}
                  </div>
                </div>
                
                <div style={{ display: 'flex', gap: '16px' }}>
                  <TimeDropdownSelector 
                    label="Start Time" 
                    value={startTime} 
                    onChange={setStartTime} 
                  />
                  <TimeDropdownSelector 
                     label="End Time" 
                     value={endTime} 
                     onChange={setEndTime} 
                  />
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Fare Matrix Editor Panel */}
        <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--border)', background: 'rgba(0,0,0,0.2)' }}>
          <div 
            onClick={() => setFareCollapsed(!fareCollapsed)}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer', userSelect: 'none', marginBottom: fareCollapsed ? '0' : '12px' }}
          >
            {fareCollapsed ? <ChevronRight size={16} color="var(--text-secondary)" /> : <ChevronDown size={16} color="var(--text-secondary)" />}
            <Calculator size={16} color="var(--accent)" />
            <span style={{ fontWeight: 600, fontSize: '14px', color: 'var(--text-primary)' }}>Dynamic Fare Matrix</span>
          </div>
          
          <div style={{
            maxHeight: fareCollapsed ? '0' : '100px',
            opacity: fareCollapsed ? 0 : 1,
            overflow: 'hidden',
            transition: 'max-height 0.35s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.25s ease-in-out',
            marginTop: fareCollapsed ? '0' : '12px'
          }}>
            <div style={{ display: 'flex', gap: '16px' }}>
              <div style={{ flex: 1 }}>
                <label style={{ display: 'block', fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Base Fare (₱)</label>
                <input 
                  type="number" 
                  value={baseFare}
                  onChange={(e) => setBaseFare(parseFloat(e.target.value))}
                  style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
                />
              </div>
              <div style={{ flex: 1 }}>
                <label style={{ display: 'block', fontSize: '11px', color: 'var(--text-secondary)', marginBottom: '4px' }}>Per-KM Fare (₱)</label>
                <input 
                  type="number" 
                  value={perKmFare}
                  onChange={(e) => setPerKmFare(parseFloat(e.target.value))}
                  style={{ width: '100%', padding: '8px', borderRadius: '4px', background: 'var(--bg-dark)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
                />
              </div>
            </div>
          </div>
        </div>

        <div style={{ 
          padding: '16px 24px 8px', 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'space-between',
          gap: '16px',
          width: '100%'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', minWidth: 0 }}>
            <MapPin size={16} color="var(--accent)" style={{ flexShrink: 0 }} />
            <span style={{ fontWeight: 600, fontSize: '14px', color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              Waypoints & Geo-fences
            </span>
          </div>
          
          <div 
            onClick={() => setEnableSnapping(!enableSnapping)}
            style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0, cursor: 'pointer' }}
          >
            <span style={{ fontSize: '12px', color: 'var(--text-secondary)', userSelect: 'none' }}>Snap</span>
            <div 
              style={{
                width: '36px',
                height: '20px',
                borderRadius: '10px',
                background: enableSnapping ? 'var(--accent)' : 'rgba(255,255,255,0.1)',
                position: 'relative',
                transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
                display: 'flex',
                alignItems: 'center',
                padding: '2px',
                border: '1px solid ' + (enableSnapping ? 'var(--accent)' : 'rgba(255,255,255,0.15)')
              }}
            >
              <div style={{
                width: '14px',
                height: '14px',
                borderRadius: '50%',
                background: '#fff',
                transform: enableSnapping ? 'translateX(16px)' : 'translateX(0px)',
                transition: 'transform 0.25s cubic-bezier(0.4, 0, 0.2, 1)',
                boxShadow: '0 1px 3px rgba(0,0,0,0.4)'
              }} />
            </div>
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '16px', paddingTop: '0' }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-secondary)' }}>Loading configuration...</div>
          ) : stops.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-secondary)' }}>No stops yet. Click the map to add one!</div>
          ) : (
            <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
              <SortableContext items={stops.map(s => s.id)} strategy={verticalListSortingStrategy}>
                {stops.map((stop, idx) => (
                  <SortableStop 
                    key={stop.id} 
                    stop={stop} 
                    index={idx} 
                    onRemove={removeStop}
                    onNameChange={updateStopName}
                    onRadiusChange={updateStopRadius}
                  />
                ))}
              </SortableContext>
            </DndContext>
          )}
        </div>

        <div style={{ padding: '24px', borderTop: '1px solid var(--border)' }}>
          <button 
            className="btn-primary" 
            style={{ width: '100%', display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '8px' }}
            onClick={handleSave}
            disabled={saving || loading}
          >
            <Save size={20} />
            {saving ? 'Saving...' : 'Deploy Route & Rules to Fleet'}
          </button>
        </div>
      </div>

      {/* Mapbox Area */}
      <div className="glass-panel" style={{ flex: 1, height: '100%', overflow: 'hidden', position: 'relative' }}>
        <Map
          {...viewState}
          mapLib={maplibregl}
          onMove={(evt: ViewStateChangeEvent) => setViewState(evt.viewState)}
          onClick={handleMapClick}
          onMouseMove={handleMapMouseMove}
          mapStyle="https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
          interactiveLayerIds={['route-line-hitbox']}
          cursor={cursor}
          onMouseEnter={() => setCursor('crosshair')}
          onMouseLeave={() => setCursor('grab')}
        >
          {/* Geo-fence Radius Visualizer */}
          {stops.length > 0 && (
            <Source id="geofences" type="geojson" data={geofencePoints}>
              <Layer
                id="geofence-circles"
                type="circle"
                paint={{
                  'circle-radius': ['*', ['get', 'radius_m'], 0.5], // Visual approximation multiplier
                  'circle-color': 'rgba(0, 210, 255, 0.15)',
                  'circle-stroke-width': 1,
                  'circle-stroke-color': 'rgba(0, 210, 255, 0.5)',
                }}
              />
            </Source>
          )}

          {/* OSRM Road-Snapped Polyline (Active Path) */}
          {stops.length > 1 && (
            <Source id="route-active" type="geojson" data={activeLineGeoJSON}>
              {/* Invisible wide hitbox for easy clicking */}
              <Layer
                id="route-line-hitbox"
                type="line"
                paint={{
                  'line-color': 'transparent',
                  'line-width': 20,
                }}
              />
              <Layer
                id="route-line-active"
                type="line"
                paint={{
                  'line-color': paths.find(p => p.id === activePathId)?.color || '#00d2ff',
                  'line-width': 4,
                  'line-opacity': 1,
                }}
              />
            </Source>
          )}

          {/* Inactive Paths */}
          {paths.filter(p => p.id !== activePathId).map(p => {
            const geo = pathGeoJSONs[p.id.toString()] || {
              type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: p.stops.map(s => [s.lon, s.lat]) }
            };
            if (!geo.coordinates || geo.coordinates.length < 2) return null;
            return (
              <Source key={`path-source-${p.id}`} id={`route-inactive-${p.id}`} type="geojson" data={geo}>
                <Layer
                  id={`route-line-inactive-${p.id}`}
                  type="line"
                  paint={{
                    'line-color': p.color || '#888',
                    'line-width': 4,
                    'line-opacity': 0.4,
                    'line-dasharray': [2, 2],
                  }}
                />
              </Source>
            );
          })}

          {/* Elastic Preview Line */}
          {elasticLineGeoJSON && (
            <Source id="elastic-route" type="geojson" data={elasticLineGeoJSON}>
              <Layer
                id="elastic-route-line"
                type="line"
                paint={{
                  'line-color': '#ffeb3b', // Yellow elastic preview
                  'line-width': 3,
                  'line-dasharray': [2, 2],
                }}
              />
            </Source>
          )}

          {/* Ghost Marker for dragging line */}
          {ghostMarker && (
            <Marker
              longitude={ghostMarker.lon}
              latitude={ghostMarker.lat}
              draggable
              onDragStart={() => setIsDraggingGhost(true)}
              onDragEnd={(e) => {
                setIsDraggingGhost(false);
                const newStops = [...stops];
                newStops.splice(ghostMarker.index, 0, {
                  id: `new-${Date.now()}`,
                  name: `Waypoint`,
                  lat: e.lngLat.lat,
                  lon: e.lngLat.lng,
                  radius_m: 100
                });
                setStops(newStops);
                setGhostMarker(null);
              }}
            >
              <div style={{ 
                background: '#fff', 
                border: '3px solid var(--accent)',
                width: '14px', 
                height: '14px', 
                borderRadius: '50%', 
                boxShadow: '0 0 10px rgba(0,0,0,0.5)',
                cursor: 'grab'
              }} />
            </Marker>
          )}

          {/* Draggable Markers */}
          {stops.map((stop) => (
            <Marker
              key={stop.id}
              longitude={stop.lon}
              latitude={stop.lat}
              draggable
              onDragEnd={(e) => handleMarkerDragEnd(stop.id, e)}
            >
              <div style={{ 
                background: 'var(--accent)', 
                border: '3px solid #000',
                width: '16px', 
                height: '16px', 
                borderRadius: '50%', 
                boxShadow: '0 0 8px var(--accent-glow)'
              }} />
            </Marker>
          ))}
        </Map>
      </div>

      {/* Premium Glassmorphic Dialog Modal */}
      {dialogOpen && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0, 0, 0, 0.65)',
          backdropFilter: 'blur(8px)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 10000
        }}>
          <div style={{
            background: 'rgba(20, 24, 33, 0.95)',
            border: '1px solid var(--border)',
            borderRadius: '12px',
            padding: '24px',
            width: '400px',
            boxShadow: '0 8px 32px rgba(0, 0, 0, 0.5)',
            display: 'flex',
            flexDirection: 'column'
          }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '16px', fontWeight: 600, color: 'var(--text-primary)' }}>
              {dialogTitle}
            </h3>
            
            {dialogType === 'prompt' && (
              <input 
                type="text"
                value={dialogValue}
                onChange={(e) => setDialogValue(e.target.value)}
                placeholder={dialogPlaceholder}
                autoFocus
                style={{
                  width: '100%',
                  padding: '10px 12px',
                  borderRadius: '6px',
                  background: 'var(--bg-dark)',
                  color: 'var(--text-primary)',
                  border: '1px solid var(--border)',
                  marginBottom: '20px',
                  fontSize: '14px',
                  outline: 'none',
                  boxShadow: 'inset 0 1px 2px rgba(0,0,0,0.2)'
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleDialogSubmit();
                  if (e.key === 'Escape') setDialogOpen(false);
                }}
              />
            )}
            
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px' }}>
              <button 
                onClick={() => setDialogOpen(false)}
                style={{
                  padding: '8px 16px',
                  borderRadius: '6px',
                  background: 'transparent',
                  color: 'var(--text-secondary)',
                  border: '1px solid var(--border)',
                  fontSize: '13px',
                  cursor: 'pointer',
                  fontWeight: 500,
                  transition: 'all 0.15s ease'
                }}
              >
                Cancel
              </button>
              <button 
                onClick={handleDialogSubmit}
                style={{
                  padding: '8px 16px',
                  borderRadius: '6px',
                  background: 'var(--accent)',
                  color: '#fff',
                  border: 'none',
                  fontSize: '13px',
                  cursor: 'pointer',
                  fontWeight: 600,
                  boxShadow: '0 2px 8px var(--accent-glow)',
                  transition: 'all 0.15s ease'
                }}
              >
                Confirm
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
