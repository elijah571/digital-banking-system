import React from 'react';
import logo from '../../assets/logo.png';

export function BrandMark({ compact = false }) {
  return (
    <div className="flex items-center gap-3">
      <img src={logo} alt="Eli‑Pay logo" className="h-9 w-9" />
      {!compact && (
        <div className="leading-tight">
          <div className="text-[12px] font-bold tracking-[0.12em] text-white">ELI‑PAY</div>
          <div className="mt-0.5 text-[9px] font-medium tracking-[0.17em] text-slate-400">
            DIGITAL BANK
          </div>
        </div>
      )}
    </div>
  );
}

export default BrandMark;
