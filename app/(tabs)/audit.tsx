import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAppState } from '../../src/context/AppStateContext';
import { SPORT_META } from '../../src/data/sources';
import { COLORS, FONTS, RADIUS, SPACING } from '../../src/theme';

export default function AuditScreen() {
  const { modelState } = useAppState();

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>Walk-Forward Out-of-Sample Audit</Text>
      <Text style={styles.sub}>
        Every league is scored strictly chronologically before updating ratings with each match result. Lower Brier Score and Log-Loss indicate better probability calibration than the naive baseline.
      </Text>

      {modelState.backtests.map((bt) => {
        const beatsBrier = bt.modelBrier < bt.baselineBrier;
        return (
          <View key={bt.leagueId} style={styles.panel}>
            <View style={styles.rowBetween}>
              <Text style={styles.leagueTitle}>
                {SPORT_META[bt.sport].short} · {bt.leagueName}
              </Text>
              <Text style={[styles.badge, beatsBrier ? styles.badgeOk : styles.badgeWarn]}>
                {beatsBrier ? 'BEATS BASELINE' : 'NEEDS MORE DATA'}
              </Text>
            </View>

            <View style={styles.metricsRow}>
              <View style={styles.metricBox}>
                <Text style={styles.mLabel}>BRIER</Text>
                <Text style={styles.mVal}>{bt.modelBrier.toFixed(4)}</Text>
                <Text style={styles.mSub}>base {bt.baselineBrier.toFixed(4)}</Text>
              </View>
              <View style={styles.metricBox}>
                <Text style={styles.mLabel}>LOG-LOSS</Text>
                <Text style={styles.mVal}>{bt.modelLogLoss.toFixed(4)}</Text>
                <Text style={styles.mSub}>base {bt.baselineLogLoss.toFixed(4)}</Text>
              </View>
              <View style={styles.metricBox}>
                <Text style={styles.mLabel}>SIM ROI</Text>
                <Text
                  style={[
                    styles.mVal,
                    { color: bt.simulatedRoiPct >= 0 ? COLORS.emerald : COLORS.crimson },
                  ]}
                >
                  {bt.simulatedRoiPct >= 0 ? '+' : ''}
                  {bt.simulatedRoiPct.toFixed(1)}%
                </Text>
                <Text style={styles.mSub}>n={bt.sampleSize}</Text>
              </View>
            </View>

            {/* Calibration Reliability Bars */}
            {bt.calibrationBins.length > 0 && (
              <View style={{ gap: 6, marginTop: 4 }}>
                <Text style={styles.mLabel}>CALIBRATION (PREDICTED VS ACTUAL WIN RATE)</Text>
                {bt.calibrationBins.map((bin) => (
                  <View key={bin.binLabel} style={styles.binRow}>
                    <Text style={styles.binLabel}>{bin.binLabel}</Text>
                    <View style={styles.barTrack}>
                      <View
                        style={[
                          styles.barActual,
                          { width: `${Math.min(100, bin.actualRate * 100)}%` },
                        ]}
                      />
                    </View>
                    <Text style={styles.binVal}>
                      {(bin.actualRate * 100).toFixed(0)}% (n={bin.count})
                    </Text>
                  </View>
                ))}
              </View>
            )}
          </View>
        );
      })}

      {/* Keyless Public Feed Sync Health */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>FREE PUBLIC DATA FEED STATUS</Text>
        {modelState.feedStatuses.map((fs) => (
          <View key={fs.feedId} style={styles.feedRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.feedName}>{fs.label}</Text>
              <Text style={styles.feedMeta}>
                {fs.recordsParsed > 0
                  ? `${fs.recordsParsed} records parsed on-device`
                  : fs.errorMessage ?? 'Offline seed cached'}
              </Text>
            </View>
            <Text
              style={[
                styles.feedStatus,
                fs.status === 'ok'
                  ? { color: COLORS.emerald }
                  : fs.status === 'cached'
                  ? { color: COLORS.amber }
                  : { color: COLORS.crimson },
              ]}
            >
              {fs.status.toUpperCase()}
            </Text>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  content: { padding: SPACING.md, gap: 14, paddingBottom: 36 },
  heading: { fontSize: 20, fontWeight: '700', color: COLORS.text },
  sub: { fontSize: 13, color: COLORS.textMuted, lineHeight: 19 },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: SPACING.md,
    gap: 10,
  },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  leagueTitle: { fontSize: 15, fontWeight: '700', color: COLORS.text },
  badge: {
    fontFamily: FONTS.mono,
    fontSize: 10,
    fontWeight: '700',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: RADIUS.sm,
    overflow: 'hidden',
  },
  badgeOk: { backgroundColor: COLORS.emeraldDim, color: COLORS.emerald },
  badgeWarn: { backgroundColor: COLORS.amberDim, color: COLORS.amber },
  metricsRow: { flexDirection: 'row', gap: 8 },
  metricBox: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    padding: 10,
    alignItems: 'center',
  },
  mLabel: { fontFamily: FONTS.mono, fontSize: 10, color: COLORS.textMuted },
  mVal: { fontFamily: FONTS.mono, fontSize: 15, fontWeight: '700', color: COLORS.text, marginTop: 2 },
  mSub: { fontFamily: FONTS.mono, fontSize: 10, color: COLORS.textSecondary, marginTop: 2 },
  binRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  binLabel: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textSecondary, width: 52 },
  barTrack: {
    flex: 1,
    height: 8,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: 4,
    overflow: 'hidden',
  },
  barActual: { height: '100%', backgroundColor: COLORS.accent },
  binVal: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textMuted, width: 72, textAlign: 'right' },
  sectionTitle: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '700', color: COLORS.textSecondary },
  feedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
  },
  feedName: { fontSize: 13, fontWeight: '600', color: COLORS.text },
  feedMeta: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textMuted, marginTop: 2 },
  feedStatus: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '700' },
});
