import React from 'react';
import { Panel } from './Panel';

export function Metric({ label, value, change, icon: Icon, color = 'gold', className = '' }) {
  const palettes = {
    gold: 'bg-gold-50 text-gold-800',
    green: 'bg-green-50 text-green-700',
    amber: 'bg-amber-50 text-amber-700',
  };

  return (
    <Panel className={`p-5 ${className}`}>
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs font-medium text-slate-500">{label}</p>
          <p className="mt-2 text-[23px] font-bold tracking-tight text-ink">{value}</p>
          {change && <p className="mt-1 text-[11px] text-green-700">{change}</p>}
        </div>
        {Icon && (
          <div className={`rounded-xl p-2.5 ${palettes[color] || palettes.gold}`}>
            <Icon size={18} />
          </div>
        )}
      </div>
    </Panel>
  );
}

export default Metric;
