import React from 'react';

export function ActivityChart() {
  const bars = [28, 38, 31, 52, 43, 62, 49, 74, 56, 83, 68, 96];

  return (
    <div className="mt-4">
      <div className="flex h-[150px] items-end gap-2 border-b border-slate-100 pb-2 sm:gap-3">
        {bars.map((height, index) => (
          <div key={index} className="group relative flex h-full flex-1 items-end justify-center">
            <div
              className={`w-full max-w-8 rounded-t-md transition ${
                index === 9 ? 'bg-brand' : 'bg-gold-100 group-hover:bg-gold-200'
              }`}
              style={{ height: `${height}%` }}
              title={`Month index ${index + 1}: ${height}% activity`}
            />
          </div>
        ))}
      </div>
      <div className="mt-2 flex justify-between text-[10px] text-slate-400 font-medium">
        <span>Jun</span>
        <span>Jul</span>
        <span>Aug</span>
        <span>Sep</span>
        <span>Oct</span>
        <span>Nov</span>
      </div>
    </div>
  );
}

export default ActivityChart;
