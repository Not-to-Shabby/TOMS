import { useState, useEffect } from 'react';
import { TrendingUp, Users, Award, Calendar } from 'lucide-react';
import axios from 'axios';

const API_URL = 'http://localhost:3000/api';

interface DailyRevenue {
  date: string;
  regular_revenue: number;
  student_revenue: number;
  senior_revenue: number;
  total_revenue: number;
  total_payments: number;
  total_boardings: number;
}

export default function AnalyticsHome() {
  const [days, setDays] = useState<number>(7);
  const [data, setData] = useState<DailyRevenue[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);

  useEffect(() => {
    setLoading(true);
    axios.get(`${API_URL}/analytics/revenue?days=${days}`)
      .then(res => {
        setData(res.data);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to fetch analytics data", err);
        setLoading(false);
      });
  }, [days]);

  // Aggregate stats
  const totalRevenue = data.reduce((acc, curr) => acc + curr.total_revenue, 0);
  const totalRidership = data.reduce((acc, curr) => acc + curr.total_boardings, 0);
  const totalRegular = data.reduce((acc, curr) => acc + curr.regular_revenue, 0);
  const totalDiscounted = data.reduce((acc, curr) => acc + curr.student_revenue + curr.senior_revenue, 0);
  const avgDailyRevenue = data.length > 0 ? totalRevenue / data.length : 0;

  // Chart computations
  const maxRevenue = Math.max(...data.map(d => d.total_revenue), 100);
  
  // SVG coordinates generators
  const width = 800;
  const height = 300;
  const padding = 40;
  const chartWidth = width - padding * 2;
  const chartHeight = height - padding * 2;

  const points = data.map((d, index) => {
    const x = padding + (index / (data.length - 1 || 1)) * chartWidth;
    const y = padding + chartHeight - (d.total_revenue / maxRevenue) * chartHeight;
    return { x, y, data: d };
  });

  const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');
  const areaPath = points.length > 0 
    ? `${linePath} L ${points[points.length - 1].x} ${height - padding} L ${points[0].x} ${height - padding} Z`
    : '';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', overflowY: 'auto', paddingRight: '4px' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Revenue & Ridership Analytics</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Historical aggregates, ticket sales, and demographic split</div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <button 
            onClick={() => window.location.href = `${API_URL}/export/audit`}
            style={{ 
              padding: '8px 16px', 
              borderRadius: '8px', 
              background: 'transparent', 
              border: '1px solid var(--accent)', 
              color: 'var(--accent)', 
              cursor: 'pointer',
              fontWeight: 600,
              fontSize: '14px'
            }}
          >
            Export CSV
          </button>
          <span style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>Timeframe:</span>
          <select 
            value={days} 
            onChange={(e) => setDays(Number(e.target.value))}
            style={{ 
              padding: '8px 16px', 
              borderRadius: '8px', 
              background: 'var(--bg-dark)', 
              color: 'var(--text-primary)', 
              border: '1px solid var(--border)',
              cursor: 'pointer',
              fontWeight: 600
            }}
          >
            <option value={7}>Last 7 Days</option>
            <option value={14}>Last 14 Days</option>
            <option value={30}>Last 30 Days</option>
          </select>
        </div>
      </header>

      {/* Summary KPI Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '24px' }}>
        <div className="glass-panel" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '16px' }}>
          <div style={{ width: '48px', height: '48px', borderRadius: '12px', background: 'rgba(0, 210, 255, 0.15)', display: 'flex', alignItems: 'center', justifyItems: 'center', justifyContent: 'center', color: 'var(--accent)' }}>
            <span style={{ fontSize: '24px', fontWeight: 700 }}>₱</span>
          </div>
          <div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '13px' }}>Gross Revenue</div>
            <div style={{ fontSize: '24px', fontWeight: 800 }}>₱{totalRevenue.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
          </div>
        </div>

        <div className="glass-panel" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '16px' }}>
          <div style={{ width: '48px', height: '48px', borderRadius: '12px', background: 'rgba(16, 185, 129, 0.15)', display: 'flex', alignItems: 'center', justifyItems: 'center', justifyContent: 'center', color: 'var(--success)' }}>
            <TrendingUp size={24} />
          </div>
          <div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '13px' }}>Average Daily</div>
            <div style={{ fontSize: '24px', fontWeight: 800 }}>₱{avgDailyRevenue.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
          </div>
        </div>

        <div className="glass-panel" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '16px' }}>
          <div style={{ width: '48px', height: '48px', borderRadius: '12px', background: 'rgba(245, 158, 11, 0.15)', display: 'flex', alignItems: 'center', justifyItems: 'center', justifyContent: 'center', color: 'var(--warning)' }}>
            <Users size={24} />
          </div>
          <div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '13px' }}>Total Ridership</div>
            <div style={{ fontSize: '24px', fontWeight: 800 }}>{totalRidership} <span style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>trips</span></div>
          </div>
        </div>

        <div className="glass-panel" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '16px' }}>
          <div style={{ width: '48px', height: '48px', borderRadius: '12px', background: 'rgba(239, 68, 68, 0.15)', display: 'flex', alignItems: 'center', justifyItems: 'center', justifyContent: 'center', color: 'var(--danger)' }}>
            <Award size={24} />
          </div>
          <div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '13px' }}>Discount Absorption</div>
            <div style={{ fontSize: '24px', fontWeight: 800 }}>
              {totalRevenue > 0 ? ((totalDiscounted / totalRevenue) * 100).toFixed(1) : 0}%
            </div>
          </div>
        </div>
      </div>

      {/* Main Revenue Trend Chart */}
      <div className="glass-panel" style={{ padding: '24px', position: 'relative' }}>
        <h3 style={{ margin: '0 0 16px 0', fontSize: '18px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Calendar size={18} /> Revenue Trend Over Time
        </h3>

        {loading ? (
          <div style={{ height: '300px', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-secondary)' }}>
            Loading historical data...
          </div>
        ) : data.length === 0 ? (
          <div style={{ height: '300px', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-secondary)' }}>
            No revenue recorded for this period.
          </div>
        ) : (
          <div style={{ position: 'relative', width: '100%' }}>
            <svg viewBox={`0 0 ${width} ${height}`} style={{ width: '100%', height: 'auto', overflow: 'visible' }}>
              {/* Grid Lines */}
              {Array.from({ length: 5 }).map((_, i) => {
                const y = padding + (i / 4) * chartHeight;
                const value = maxRevenue - (i / 4) * maxRevenue;
                return (
                  <g key={i}>
                    <line x1={padding} y1={y} x2={width - padding} y2={y} stroke="var(--border)" strokeDasharray="4 4" />
                    <text x={padding - 8} y={y + 4} fill="var(--text-secondary)" fontSize="10px" textAnchor="end">
                      ₱{value.toFixed(0)}
                    </text>
                  </g>
                );
              })}

              {/* Area path with beautiful gradient */}
              <defs>
                <linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.4" />
                  <stop offset="100%" stopColor="var(--accent)" stopOpacity="0.0" />
                </linearGradient>
              </defs>
              <path d={areaPath} fill="url(#chartGradient)" />

              {/* Line path */}
              <path d={linePath} fill="none" stroke="var(--accent)" strokeWidth="3" strokeLinecap="round" />

              {/* Interactive interactive nodes */}
              {points.map((p, i) => (
                <circle 
                  key={i} 
                  cx={p.x} 
                  cy={p.y} 
                  r={hoveredIndex === i ? 6 : 4} 
                  fill={hoveredIndex === i ? 'var(--accent)' : 'var(--bg-dark)'} 
                  stroke="var(--accent)" 
                  strokeWidth="2"
                  style={{ cursor: 'pointer', transition: 'all 0.1s' }}
                  onMouseEnter={() => setHoveredIndex(i)}
                  onMouseLeave={() => setHoveredIndex(null)}
                />
              ))}

              {/* X Axis Labels */}
              {data.map((d, index) => {
                // Show less labels if date range is wide
                if (data.length > 8 && index % 2 !== 0) return null;
                const x = padding + (index / (data.length - 1 || 1)) * chartWidth;
                const formattedDate = new Date(d.date).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
                return (
                  <text key={index} x={x} y={height - padding + 20} fill="var(--text-secondary)" fontSize="10px" textAnchor="middle">
                    {formattedDate}
                  </text>
                );
              })}
            </svg>

            {/* Hover Tooltip display */}
            {hoveredIndex !== null && points[hoveredIndex] && (
              <div style={{
                position: 'absolute',
                top: `${points[hoveredIndex].y - 90}px`,
                left: `${(points[hoveredIndex].x / width) * 100}%`,
                transform: 'translateX(-50%)',
                background: 'rgba(18, 24, 38, 0.9)',
                border: '1px solid var(--accent)',
                borderRadius: '8px',
                padding: '12px',
                color: 'var(--text-primary)',
                fontSize: '12px',
                zIndex: 10,
                boxShadow: '0 4px 12px rgba(0,0,0,0.5)',
                pointerEvents: 'none',
                minWidth: '150px'
              }}>
                <div style={{ fontWeight: 700, marginBottom: '4px', borderBottom: '1px solid var(--border)', paddingBottom: '4px' }}>
                  {new Date(points[hoveredIndex].data.date).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })}
                </div>
                <div style={{ color: 'var(--accent)', fontWeight: 800, fontSize: '14px', marginBottom: '4px' }}>
                  ₱{points[hoveredIndex].data.total_revenue.toLocaleString(undefined, { minimumFractionDigits: 2 })}
                </div>
                <div style={{ color: 'var(--text-secondary)' }}>
                  Tickets Sold: {points[hoveredIndex].data.total_payments}
                </div>
                <div style={{ color: 'var(--text-secondary)' }}>
                  Regular: ₱{points[hoveredIndex].data.regular_revenue.toFixed(0)}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Demographics and Passenger Type Breakdown */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '24px', marginBottom: '24px' }}>
        {/* Ticket Split */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <h3 style={{ margin: '0 0 20px 0', fontSize: '18px' }}>Passenger Demographic Split</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            {/* Regular */}
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '14px', marginBottom: '8px' }}>
                <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>Regular Fare</span>
                <span style={{ color: 'var(--text-secondary)' }}>₱{totalRegular.toLocaleString()} ({totalRevenue > 0 ? ((totalRegular / totalRevenue) * 100).toFixed(0) : 0}%)</span>
              </div>
              <div style={{ height: '8px', background: 'var(--bg-dark)', borderRadius: '4px', overflow: 'hidden' }}>
                <div style={{ width: `${totalRevenue > 0 ? (totalRegular / totalRevenue) * 100 : 0}%`, height: '100%', background: 'var(--accent)' }} />
              </div>
            </div>

            {/* Student */}
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '14px', marginBottom: '8px' }}>
                <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>Student (20% Discount)</span>
                <span style={{ color: 'var(--text-secondary)' }}>₱{data.reduce((acc, curr) => acc + curr.student_revenue, 0).toLocaleString()} ({totalRevenue > 0 ? ((data.reduce((acc, curr) => acc + curr.student_revenue, 0) / totalRevenue) * 100).toFixed(0) : 0}%)</span>
              </div>
              <div style={{ height: '8px', background: 'var(--bg-dark)', borderRadius: '4px', overflow: 'hidden' }}>
                <div style={{ width: `${totalRevenue > 0 ? (data.reduce((acc, curr) => acc + curr.student_revenue, 0) / totalRevenue) * 100 : 0}%`, height: '100%', background: 'var(--success)' }} />
              </div>
            </div>

            {/* Senior */}
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '14px', marginBottom: '8px' }}>
                <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>Senior Citizen</span>
                <span style={{ color: 'var(--text-secondary)' }}>₱{data.reduce((acc, curr) => acc + curr.senior_revenue, 0).toLocaleString()} ({totalRevenue > 0 ? ((data.reduce((acc, curr) => acc + curr.senior_revenue, 0) / totalRevenue) * 100).toFixed(0) : 0}%)</span>
              </div>
              <div style={{ height: '8px', background: 'var(--bg-dark)', borderRadius: '4px', overflow: 'hidden' }}>
                <div style={{ width: `${totalRevenue > 0 ? (data.reduce((acc, curr) => acc + curr.senior_revenue, 0) / totalRevenue) * 100 : 0}%`, height: '100%', background: 'var(--warning)' }} />
              </div>
            </div>
          </div>
        </div>

        {/* Operating Insights */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <h3 style={{ margin: '0 0 20px 0', fontSize: '18px' }}>Operational Insights</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', paddingBottom: '12px', borderBottom: '1px solid var(--border)' }}>
              <span style={{ color: 'var(--text-secondary)' }}>Avg. Tickets per Day</span>
              <span style={{ fontWeight: 700 }}>{data.length > 0 ? (data.reduce((acc, curr) => acc + curr.total_payments, 0) / data.length).toFixed(1) : 0} sales</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', paddingBottom: '12px', borderBottom: '1px solid var(--border)' }}>
              <span style={{ color: 'var(--text-secondary)' }}>Payment Conversion Rate</span>
              <span style={{ fontWeight: 700, color: 'var(--success)' }}>
                {totalRidership > 0 ? ((data.reduce((acc, curr) => acc + curr.total_payments, 0) / totalRidership) * 100).toFixed(1) : 0}%
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', paddingBottom: '12px', borderBottom: '1px solid var(--border)' }}>
              <span style={{ color: 'var(--text-secondary)' }}>Average Ticket Value</span>
              <span style={{ fontWeight: 700, color: 'var(--accent)' }}>
                ₱{data.reduce((acc, curr) => acc + curr.total_payments, 0) > 0 ? (totalRevenue / data.reduce((acc, curr) => acc + curr.total_payments, 0)).toFixed(2) : 0}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
