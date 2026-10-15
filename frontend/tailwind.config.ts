import type { Config } from 'tailwindcss';

// Tokens: cool paper and deep indigo ink; marigold is reserved for people and referrals, fit-green for match data.
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { DEFAULT: '#1B2440', soft: '#2E3A5C' },
        paper: '#F6F7FA',
        surface: '#FFFFFF',
        line: { DEFAULT: '#DCE1EA', strong: '#C3CAD8' },
        slate: { DEFAULT: '#5B6478', light: '#8790A3' },
        marigold: { DEFAULT: '#E3A008', soft: '#FDF3D6', deep: '#9A6B00' },
        fit: { DEFAULT: '#17785A', soft: '#E3F2EC', mid: '#5FA98F' },
        danger: { DEFAULT: '#B42318', soft: '#FDECEA' },
        warn: { DEFAULT: '#A15C07', soft: '#FEF4E6' },
      },
      fontFamily: {
        display: ['"Bricolage Grotesque Variable"', 'system-ui', 'sans-serif'],
        sans: ['"IBM Plex Sans"', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        // modular scale ≈ 1.25
        xs: ['0.8rem', { lineHeight: '1.15rem' }],
        sm: ['0.875rem', { lineHeight: '1.3rem' }],
        base: ['1rem', { lineHeight: '1.55rem' }],
        lg: ['1.25rem', { lineHeight: '1.75rem' }],
        xl: ['1.563rem', { lineHeight: '2rem' }],
        '2xl': ['1.953rem', { lineHeight: '2.35rem' }],
        '3xl': ['2.441rem', { lineHeight: '2.75rem' }],
        '4xl': ['3.052rem', { lineHeight: '3.2rem' }],
      },
      borderRadius: { DEFAULT: '6px', md: '6px', lg: '10px' },
      maxWidth: { content: '70rem' },
    },
  },
  plugins: [],
} satisfies Config;
