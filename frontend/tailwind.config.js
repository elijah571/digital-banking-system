/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        ink: '#12294a',
        brand: '#2862dc',
        canvas: '#f4f6fb',
        mint: '#dff6ee',
      },
      boxShadow: {
        panel: '0 2px 6px rgba(17, 38, 67, 0.06), 0 12px 28px rgba(17, 38, 67, 0.035)',
      },
    },
  },
  plugins: [],
};
