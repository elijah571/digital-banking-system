import React from 'react';

export function BrandMark({ compact = false }) {
  return (
    <div className="flex items-center gap-3">
      <div className="grid h-9 w-9 place-items-center rounded-xl bg-brand text-base font-extrabold text-ink shadow-sm">
        E
      </div>
      {!compact && (
        <div className="leading-tight">
          <div className="text-[12px] font-bold tracking-[0.12em] text-white">ELI</div>
          <div className="mt-0.5 text-[9px] font-medium tracking-[0.17em] text-slate-400">
            DIGITAL BANK
          </div>
        </div>
      )}
    </div>
  );
}

export default BrandMark;
