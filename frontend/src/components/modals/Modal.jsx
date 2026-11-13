import React, { useEffect } from 'react';
import { X } from 'lucide-react';
import { Panel } from '../common/Panel';

export function Modal({
  isOpen = true,
  onClose,
  title,
  subtitle,
  children,
  maxWidth = 'max-w-md',
  showClose = true,
}) {
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && onClose) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4 backdrop-blur-[2px] transition fade-in"
      onClick={onClose}
    >
      <Panel
        className={`w-full ${maxWidth} p-6 shadow-2xl transition`}
        onClick={(e) => e.stopPropagation()}
      >
        {(title || showClose) && (
          <div className="flex items-start justify-between gap-4">
            <div>
              {title && <h2 className="text-lg font-bold text-ink">{title}</h2>}
              {subtitle && <p className="mt-1 text-xs text-slate-500">{subtitle}</p>}
            </div>
            {showClose && (
              <button
                type="button"
                onClick={onClose}
                className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-ink transition"
                aria-label="Close modal"
              >
                <X size={18} />
              </button>
            )}
          </div>
        )}
        <div className={title || showClose ? 'mt-4' : ''}>{children}</div>
      </Panel>
    </div>
  );
}

export default Modal;
