// Centralised design tokens. The product is an operations console, so the palette is a calm,
// dark "control room" surface with a single high-energy accent and explicit status colours.

export const palette = {
  bg: '#0B0F14',
  surface: '#121822',
  surfaceAlt: '#19212E',
  surfaceHigh: '#202A3A',
  border: '#27313F',
  borderStrong: '#34415440',

  text: '#E8EDF4',
  textMuted: '#94A3B8',
  textFaint: '#5C6B7E',

  accent: '#3DDC97', // signal green — "flowing / healthy"
  accentDim: '#1F6B52',
  accentSoft: '#13241E',

  info: '#56A8FF',
  warn: '#FFB454',
  danger: '#FF6B6B',
  dangerSoft: '#2A1718',

  // Status mapping reused across History / Queue.
  statusSent: '#3DDC97',
  statusQueued: '#56A8FF',
  statusFailed: '#FFB454',
  statusDead: '#FF6B6B',
  statusNone: '#5C6B7E',
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
};

export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  pill: 999,
};

export const typography = {
  display: { fontSize: 28, fontWeight: '700' as const, letterSpacing: -0.5 },
  title: { fontSize: 20, fontWeight: '700' as const, letterSpacing: -0.3 },
  heading: { fontSize: 16, fontWeight: '600' as const },
  body: { fontSize: 14, fontWeight: '400' as const },
  label: { fontSize: 12, fontWeight: '600' as const, letterSpacing: 0.4 },
  mono: { fontFamily: 'monospace', fontSize: 12 },
};

export const theme = { palette, spacing, radius, typography };
export type Theme = typeof theme;

export function statusColor(status: string): string {
  switch (status) {
    case 'sent':
      return palette.statusSent;
    case 'queued':
    case 'pending':
    case 'in_progress':
      return palette.statusQueued;
    case 'failed':
      return palette.statusFailed;
    case 'dead':
    case 'no_webhook':
      return palette.statusDead;
    default:
      return palette.statusNone;
  }
}
