import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom';
import { LayoutDashboard, Map as MapIcon, BarChart3, History, Settings, Users, Bus } from 'lucide-react';
import RouteBuilder from './pages/RouteBuilder';
import DashboardHome from './pages/DashboardHome';
import AnalyticsHome from './pages/AnalyticsHome';
import AuditLogs from './pages/AuditLogs';
import ConductorsList from './pages/ConductorsList';
import FleetManager from './pages/FleetManager';
import './index.css';

function Sidebar() {
  const location = useLocation();

  const getLinkStyle = (path: string) => {
    const isActive = location.pathname === path;
    return {
      display: 'flex',
      alignItems: 'center',
      gap: '16px',
      padding: '16px',
      color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
      textDecoration: 'none',
      borderRadius: '8px',
      background: isActive ? 'var(--accent-glow)' : 'transparent',
      transition: 'all 0.2s ease',
      fontWeight: 600,
      fontSize: '16px'
    };
  };

  return (
    <div className="glass-panel" style={{ width: '280px', height: '100%', padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <div style={{ padding: '0 12px 24px 12px', borderBottom: '1px solid var(--border)', marginBottom: '16px' }}>
        <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--accent)', letterSpacing: '2px' }}>TOMS</h1>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Transportation Occupancy Management System</div>
      </div>
      
      <nav style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <Link to="/" style={getLinkStyle('/')}>
          <LayoutDashboard size={20} />
          <span>Fleet Dashboard</span>
        </Link>
        <Link to="/routes" style={getLinkStyle('/routes')}>
          <MapIcon size={20} />
          <span>Route Builder</span>
        </Link>
        <Link to="/vehicles" style={getLinkStyle('/vehicles')}>
          <Bus size={20} />
          <span>Fleet Manager</span>
        </Link>
        <Link to="/conductors" style={getLinkStyle('/conductors')}>
          <Users size={20} />
          <span>Conductors Directory</span>
        </Link>
        <Link to="/analytics" style={getLinkStyle('/analytics')}>
          <BarChart3 size={20} />
          <span>Revenue Analytics</span>
        </Link>
        <Link to="/audit" style={getLinkStyle('/audit')}>
          <History size={20} />
          <span>Audit Logs</span>
        </Link>
      </nav>

      <div style={{ marginTop: 'auto', paddingTop: '24px', borderTop: '1px solid var(--border)' }}>
        <Link to="/settings" style={getLinkStyle('/settings')}>
          <Settings size={20} />
          <span>Settings</span>
        </Link>
      </div>
    </div>
  );
}


function App() {
  return (
    <Router>
      <div style={{ display: 'flex', height: '100vh', padding: '16px', gap: '16px', overflow: 'hidden' }}>
        <Sidebar />
        <div style={{ flex: 1, height: '100%', overflow: 'hidden' }}>
          <Routes>
            <Route path="/" element={<DashboardHome />} />
            <Route path="/routes" element={<RouteBuilder />} />
            <Route path="/conductors" element={<ConductorsList />} />
            <Route path="/vehicles" element={<FleetManager />} />
            <Route path="/analytics" element={<AnalyticsHome />} />
            <Route path="/audit" element={<AuditLogs />} />
            <Route path="/settings" element={<div className="glass-panel" style={{height: '100%', padding: '24px'}}><h2>Settings</h2></div>} />
          </Routes>
        </div>
      </div>
    </Router>
  );
}

export default App;
