import { Platform } from 'react-native';

export const THEME = {
  background: '#0A0E17',
  surface: '#131A29',
  elevated: '#1C2538',
  border: '#26334D',
  primary: '#3B82F6',
  win: '#10B981',
  draw: '#F59E0B',
  loss: '#EF4444',
  text: '#F8FAFC',
  textSecondary: '#CBD5E1',
  textMuted: '#94A3B8',
} as const;

export const COLORS = {
  bg: THEME.background,
  surface: THEME.surface,
  surfaceRaised: THEME.elevated,
  border: THEME.border,
  borderActive: THEME.primary,
  text: THEME.text,
  textSecondary: THEME.textSecondary,
  textMuted: THEME.textMuted,
  accent: THEME.primary,
  accentDim: 'rgba(59, 130, 246, 0.14)',
  emerald: THEME.win,
  emeraldDim: 'rgba(16, 185, 129, 0.14)',
  amber: THEME.draw,
  amberDim: 'rgba(245, 158, 11, 0.14)',
  crimson: THEME.loss,
  crimsonDim: 'rgba(239, 68, 68, 0.14)',
  cyan: '#06B6D4',
};

export const FONTS = {
  sans: Platform.select({
    ios: 'System',
    android: 'Roboto',
    default: 'System',
  }),
  mono: Platform.select({
    ios: 'Menlo',
    android: 'monospace',
    default: 'monospace',
  }),
};

export const SPACING = {
  xs: 4,
  sm: 8,
  md: 14,
  lg: 20,
  xl: 28,
};

export const RADIUS = {
  sm: 8,
  md: 12,
  lg: 16,
  pill: 999,
};
