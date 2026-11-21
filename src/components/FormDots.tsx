import React, { useMemo } from 'react';
import {
  StyleProp,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from 'react-native';
import { THEME } from '../theme';

export type FormResult = 'W' | 'D' | 'L';

export interface FormDotsProps {
  results: ReadonlyArray<FormResult>;
  size?: 'sm' | 'md';
  highlightLatest?: boolean;
  label?: string;
  style?: StyleProp<ViewStyle>;
}

const RESULT_CONFIG: Record<FormResult, { bg: string; fg: string }> = {
  W: { bg: THEME.win, fg: THEME.background },
  D: { bg: THEME.draw, fg: THEME.background },
  L: { bg: THEME.loss, fg: THEME.text },
};

const SIZE_MAP = {
  sm: { box: 20, radius: 6, fontSize: 10, gap: 4 },
  md: { box: 24, radius: 7, fontSize: 11, gap: 5 },
} as const;

export const FormDots: React.FC<FormDotsProps> = React.memo(
  ({ results, size = 'md', highlightLatest = true, label, style }) => {
    const metrics = SIZE_MAP[size];

    const slots = useMemo(() => {
      const recent = results.slice(-5);
      const missingCount = Math.max(0, 5 - recent.length);
      return [...Array<FormResult | null>(missingCount).fill(null), ...recent];
    }, [results]);

    return (
      <View style={[styles.wrapper, style]}>
        {label ? <Text style={styles.label}>{label}</Text> : null}
        <View style={[styles.row, { gap: metrics.gap }]}>
          {slots.map((result, index) => {
            const isLatest =
              highlightLatest && result !== null && index === slots.length - 1;

            if (result === null) {
              return (
                <View
                  key={`empty-${index}`}
                  style={[
                    styles.pill,
                    styles.emptyPill,
                    { width: metrics.box, height: metrics.box, borderRadius: metrics.radius },
                  ]}
                >
                  <Text style={[styles.emptyText, { fontSize: metrics.fontSize }]}>–</Text>
                </View>
              );
            }

            const config = RESULT_CONFIG[result];
            return (
              <View
                key={`${result}-${index}`}
                style={[
                  styles.pill,
                  {
                    width: metrics.box,
                    height: metrics.box,
                    borderRadius: metrics.radius,
                    backgroundColor: config.bg,
                  },
                  isLatest && styles.latestRing,
                ]}
              >
                <Text style={[styles.pillText, { color: config.fg, fontSize: metrics.fontSize }]}>
                  {result}
                </Text>
              </View>
            );
          })}
        </View>
      </View>
    );
  }
);

FormDots.displayName = 'FormDots';

const styles = StyleSheet.create({
  wrapper: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  label: {
    fontSize: 11,
    fontWeight: '600',
    color: THEME.textMuted,
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  row: { flexDirection: 'row', alignItems: 'center' },
  pill: { alignItems: 'center', justifyContent: 'center' },
  emptyPill: {
    backgroundColor: THEME.elevated,
    borderWidth: 1,
    borderColor: THEME.border,
  },
  latestRing: { borderWidth: 1.5, borderColor: THEME.text },
  pillText: {
    fontWeight: '800',
    includeFontPadding: false,
    textAlign: 'center',
    textAlignVertical: 'center',
  },
  emptyText: { color: THEME.textMuted, fontWeight: '700', includeFontPadding: false },
});
