import React, { useMemo } from 'react';
import {
  StyleProp,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from 'react-native';
import { THEME } from '../theme';

export interface ProbBarProps {
  homeProb: number;
  awayProb: number;
  drawProb?: number;
  homeLabel?: string;
  drawLabel?: string;
  awayLabel?: string;
  homeColor?: string;
  drawColor?: string;
  awayColor?: string;
  barHeight?: number;
  style?: StyleProp<ViewStyle>;
}

function normalizeToWholePercents(values: number[]): number[] {
  const safe = values.map((v) => (Number.isFinite(v) && v > 0 ? v : 0));
  const total = safe.reduce((acc, v) => acc + v, 0);

  if (total <= 0) {
    const base = Math.floor(100 / safe.length);
    const remainder = 100 - base * safe.length;
    return safe.map((_, i) => base + (i === 0 ? remainder : 0));
  }

  const exact = safe.map((v) => (v / total) * 100);
  const floored = exact.map((v) => Math.floor(v));
  const remainder = 100 - floored.reduce((acc, v) => acc + v, 0);

  const order = exact
    .map((v, index) => ({ index, frac: v - floored[index] }))
    .sort((a, b) => b.frac - a.frac);

  for (let i = 0; i < remainder; i += 1) {
    floored[order[i].index] += 1;
  }

  return floored;
}

export const ProbBar: React.FC<ProbBarProps> = React.memo(
  ({
    homeProb,
    awayProb,
    drawProb,
    homeLabel = 'Home',
    drawLabel = 'Draw',
    awayLabel = 'Away',
    homeColor = THEME.primary,
    drawColor = THEME.draw,
    awayColor = THEME.loss,
    barHeight = 10,
    style,
  }) => {
    const isThreeWay = drawProb !== undefined;

    const { homePct, drawPct, awayPct, leader } = useMemo(() => {
      if (isThreeWay) {
        const [h, d, a] = normalizeToWholePercents([
          homeProb,
          drawProb ?? 0,
          awayProb,
        ]);
        const max = Math.max(h, d, a);
        const top =
          h === max && h > d && h > a
            ? 'home'
            : a === max && a > h && a > d
              ? 'away'
              : d === max && d > h && d > a
                ? 'draw'
                : 'none';
        return { homePct: h, drawPct: d, awayPct: a, leader: top };
      }

      const [h, a] = normalizeToWholePercents([homeProb, awayProb]);
      const top = h > a ? 'home' : a > h ? 'away' : 'none';
      return { homePct: h, drawPct: 0, awayPct: a, leader: top };
    }, [isThreeWay, homeProb, drawProb, awayProb]);

    return (
      <View style={[styles.container, style]}>
        <View style={styles.labelRow}>
          <Text
            numberOfLines={1}
            style={[styles.label, styles.alignLeft, leader === 'home' && styles.leaderLabel]}
          >
            {homeLabel}
          </Text>
          {isThreeWay && (
            <Text
              numberOfLines={1}
              style={[styles.label, styles.alignCenter, leader === 'draw' && styles.leaderLabel]}
            >
              {drawLabel}
            </Text>
          )}
          <Text
            numberOfLines={1}
            style={[styles.label, styles.alignRight, leader === 'away' && styles.leaderLabel]}
          >
            {awayLabel}
          </Text>
        </View>

        <View
          style={[
            styles.track,
            { height: barHeight, borderRadius: barHeight / 2 },
          ]}
        >
          {homePct > 0 && (
            <View style={[styles.segment, { flex: homePct, backgroundColor: homeColor }]} />
          )}
          {isThreeWay && drawPct > 0 && (
            <View style={[styles.segment, { flex: drawPct, backgroundColor: drawColor }]} />
          )}
          {awayPct > 0 && (
            <View style={[styles.segment, { flex: awayPct, backgroundColor: awayColor }]} />
          )}
        </View>

        <View style={styles.percentRow}>
          <View style={[styles.percentItem, styles.percentLeft]}>
            <View style={[styles.dot, { backgroundColor: homeColor }]} />
            <Text style={[styles.percentText, leader === 'home' && styles.leaderPercent]}>
              {homePct}%
            </Text>
          </View>

          {isThreeWay && (
            <View style={[styles.percentItem, styles.percentCenter]}>
              <View style={[styles.dot, { backgroundColor: drawColor }]} />
              <Text style={[styles.percentText, leader === 'draw' && styles.leaderPercent]}>
                {drawPct}%
              </Text>
            </View>
          )}

          <View style={[styles.percentItem, styles.percentRight]}>
            <Text style={[styles.percentText, leader === 'away' && styles.leaderPercent]}>
              {awayPct}%
            </Text>
            <View style={[styles.dot, { backgroundColor: awayColor }]} />
          </View>
        </View>
      </View>
    );
  }
);

ProbBar.displayName = 'ProbBar';

const styles = StyleSheet.create({
  container: { width: '100%', gap: 6 },
  labelRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  label: { flex: 1, fontSize: 12, fontWeight: '600', color: THEME.textMuted },
  leaderLabel: { color: THEME.textSecondary },
  alignLeft: { textAlign: 'left' },
  alignCenter: { textAlign: 'center' },
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
  percentRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  percentItem: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 5 },
  percentLeft: { justifyContent: 'flex-start' },
  percentCenter: { justifyContent: 'center' },
  percentRight: { justifyContent: 'flex-end' },
  dot: { width: 7, height: 7, borderRadius: 3.5 },
  percentText: {
    fontSize: 13,
    fontWeight: '600',
    color: THEME.textSecondary,
    fontVariant: ['tabular-nums'],
  },
  leaderPercent: { color: THEME.text, fontWeight: '800' },
});
