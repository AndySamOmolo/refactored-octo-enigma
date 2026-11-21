import React, { useMemo } from 'react';
import {
  StyleProp,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from 'react-native';
import { THEME } from '../theme';

export interface StatCompareBarProps {
  label: string;
  homeValue: number;
  awayValue: number;
  unit?: string;
  decimals?: number;
  higherIsBetter?: boolean;
  homeColor?: string;
  awayColor?: string;
  barHeight?: number;
  style?: StyleProp<ViewStyle>;
}

function formatStat(value: number, unit?: string, decimals?: number): string {
  const safe = Number.isFinite(value) ? value : 0;
  const formatted =
    decimals !== undefined
      ? safe.toFixed(decimals)
      : Number.isInteger(safe)
        ? String(safe)
        : safe.toFixed(1);
  return unit ? `${formatted}${unit}` : formatted;
}

export const StatCompareBar: React.FC<StatCompareBarProps> = React.memo(
  ({
    label,
    homeValue,
    awayValue,
    unit,
    decimals,
    higherIsBetter = true,
    homeColor = THEME.primary,
    awayColor = THEME.loss,
    barHeight = 8,
    style,
  }) => {
    const {
      homeDisplay,
      awayDisplay,
      homeFlex,
      awayFlex,
      isEmpty,
      homeLeads,
      awayLeads,
    } = useMemo(() => {
      const h = Number.isFinite(homeValue) && homeValue > 0 ? homeValue : 0;
      const a = Number.isFinite(awayValue) && awayValue > 0 ? awayValue : 0;
      const total = h + a;

      const hDisplay = formatStat(homeValue, unit, decimals);
      const aDisplay = formatStat(awayValue, unit, decimals);

      const hLeads =
        homeValue !== awayValue &&
        (higherIsBetter ? homeValue > awayValue : homeValue < awayValue);
      const aLeads =
        homeValue !== awayValue &&
        (higherIsBetter ? awayValue > homeValue : awayValue < homeValue);

      if (total <= 0) {
        return {
          homeDisplay: hDisplay,
          awayDisplay: aDisplay,
          homeFlex: 1,
          awayFlex: 1,
          isEmpty: true,
          homeLeads: false,
          awayLeads: false,
        };
      }

      return {
        homeDisplay: hDisplay,
        awayDisplay: aDisplay,
        homeFlex: h / total,
        awayFlex: a / total,
        isEmpty: false,
        homeLeads: hLeads,
        awayLeads: aLeads,
      };
    }, [homeValue, awayValue, unit, decimals, higherIsBetter]);

    return (
      <View style={[styles.container, style]}>
        <View style={styles.headerRow}>
          <Text
            numberOfLines={1}
            style={[styles.valueText, styles.alignLeft, homeLeads && styles.leadingValue]}
          >
            {homeDisplay}
          </Text>
          <Text numberOfLines={1} style={styles.metricLabel}>
            {label}
          </Text>
          <Text
            numberOfLines={1}
            style={[styles.valueText, styles.alignRight, awayLeads && styles.leadingValue]}
          >
            {awayDisplay}
          </Text>
        </View>

        <View style={[styles.track, { height: barHeight, borderRadius: barHeight / 2 }]}>
          {isEmpty ? (
            <View style={styles.emptyFill} />
          ) : (
            <>
              {homeFlex > 0 && (
                <View
                  style={[
                    styles.segment,
                    {
                      flex: homeFlex,
                      backgroundColor: homeColor,
                      opacity: awayLeads ? 0.5 : 1,
                    },
                  ]}
                />
              )}
              {awayFlex > 0 && (
                <View
                  style={[
                    styles.segment,
                    {
                      flex: awayFlex,
                      backgroundColor: awayColor,
                      opacity: homeLeads ? 0.5 : 1,
                    },
                  ]}
                />
              )}
            </>
          )}
        </View>
      </View>
    );
  }
);

StatCompareBar.displayName = 'StatCompareBar';

const styles = StyleSheet.create({
  container: { width: '100%', gap: 6 },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  valueText: {
    minWidth: 46,
    fontSize: 13,
    fontWeight: '600',
    color: THEME.textSecondary,
    fontVariant: ['tabular-nums'],
  },
  leadingValue: { color: THEME.text, fontWeight: '800' },
  metricLabel: {
    flex: 1,
    fontSize: 12,
    fontWeight: '600',
    color: THEME.textMuted,
    textAlign: 'center',
  },
  alignLeft: { textAlign: 'left' },
  alignRight: { textAlign: 'right' },
  track: {
    flexDirection: 'row',
    width: '100%',
    backgroundColor: THEME.elevated,
    overflow: 'hidden',
    gap: 2,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: THEME.border,
  },
  segment: { height: '100%' },
  emptyFill: { flex: 1, backgroundColor: THEME.elevated },
});
