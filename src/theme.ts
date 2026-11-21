import { Platform } from 'react-native';

export const COLORS = {
  bg: '#0B0F17',
  surface: '#111827',
  surfaceRaised: '#1A2234',
  border: '#243047',
  borderActive: '#3B82F6',
  text: '#F8FAFC',
  textSecondary: '#CBD5E1',
  textMuted: '#94A3B8',
  accent: '#3B82F6',
  accentDim: 'rgba(59, 130, 246, 0.14)',
  emerald: '#10B981',
  emeraldDim: 'rgba(16, 185, 129, 0.14)',
  amber: '#F59E0B',
  amberDim: 'rgba(245, 158, 11, 0.14)',
  crimson: '#EF4444',
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
  sm: 6,
  md: 10,
  lg: 14,
  pill: 999,
};
