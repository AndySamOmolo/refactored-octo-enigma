import React, { useMemo } from 'react';
import {
  StyleProp,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from 'react-native';
import { THEME } from '../theme';

export interface TeamAvatarProps {
  teamName: string;
  size?: number;
  initials?: string;
  color?: string;
  style?: StyleProp<ViewStyle>;
}

const TEAM_PALETTE = [
  '#2563EB',
  '#DC2626',
  '#059669',
  '#7C3AED',
  '#EA580C',
  '#0891B2',
  '#DB2777',
  '#4F46E5',
  '#0D9488',
  '#B91C1C',
  '#1D4ED8',
  '#9333EA',
] as const;

const IGNORED_PREFIXES = new Set([
  'fc',
  'cf',
  'sc',
  'ac',
  'afc',
  'as',
  'cd',
  'ud',
  'rc',
  'sv',
]);

function hashTeamName(name: string): number {
  const normalized = name.trim().toLowerCase();
  let hash = 2166136261;
  for (let i = 0; i < normalized.length; i += 1) {
    hash ^= normalized.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

export function getTeamColor(teamName: string): string {
  const idx = hashTeamName(teamName) % TEAM_PALETTE.length;
  return TEAM_PALETTE[idx];
}

export function getTeamInitials(teamName: string): string {
  const cleaned = teamName.replace(/[^a-zA-Z0-9\s]/g, ' ').trim();
  if (!cleaned) return '??';
  const rawWords = cleaned.split(/\s+/).filter(Boolean);
  const meaningfulWords =
    rawWords.length > 1 && IGNORED_PREFIXES.has(rawWords[0].toLowerCase())
      ? rawWords.slice(1)
      : rawWords;
  if (meaningfulWords.length === 1) {
    return meaningfulWords[0].slice(0, 2).toUpperCase();
  }
  return `${meaningfulWords[0][0]}${meaningfulWords[1][0]}`.toUpperCase();
}

export const TeamAvatar: React.FC<TeamAvatarProps> = React.memo(
  ({ teamName, size = 40, initials, color, style }) => {
    const resolvedColor = useMemo(
      () => color ?? getTeamColor(teamName),
      [color, teamName]
    );

    const resolvedInitials = useMemo(() => {
      if (initials?.trim()) {
        return initials.trim().slice(0, 2).toUpperCase();
      }
      return getTeamInitials(teamName);
    }, [initials, teamName]);

    const fontSize = Math.max(10, Math.round(size * 0.38));
    const borderWidth = size >= 44 ? 2 : 1.5;

    return (
      <View
        accessible
        accessibilityRole="image"
        accessibilityLabel={`${teamName} badge`}
        style={[
          styles.badge,
          {
            width: size,
            height: size,
            borderRadius: size / 2,
            backgroundColor: resolvedColor,
            borderWidth,
          },
          style,
        ]}
      >
        <Text
          numberOfLines={1}
          adjustsFontSizeToFit
          style={[
            styles.initials,
            {
              fontSize,
              lineHeight: Math.round(fontSize * 1.15),
            },
          ]}
        >
          {resolvedInitials}
        </Text>
      </View>
    );
  }
);

TeamAvatar.displayName = 'TeamAvatar';

const styles = StyleSheet.create({
  badge: {
    alignItems: 'center',
    justifyContent: 'center',
    borderColor: 'rgba(248, 250, 252, 0.22)',
    overflow: 'hidden',
  },
  initials: {
    color: THEME.text,
    fontWeight: '800',
    letterSpacing: 0.6,
    includeFontPadding: false,
    textAlign: 'center',
    textAlignVertical: 'center',
  },
});
