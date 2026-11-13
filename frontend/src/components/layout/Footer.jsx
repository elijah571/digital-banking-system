import React from 'react';
import { ShieldCheck } from 'lucide-react';

export function Footer() {
  return (
    <footer className="mt-10 flex flex-col gap-2 border-t border-slate-200/80 py-5 text-[10px] text-slate-400 sm:flex-row sm:items-center sm:justify-between">
      <span>© 2026 Eli Digital Bank · Banking made clear.</span>
      <span className="inline-flex items-center gap-1">
        <ShieldCheck size={12} className="text-green-600" /> Your money is protected
      </span>
    </footer>
  );
}

export default Footer;
