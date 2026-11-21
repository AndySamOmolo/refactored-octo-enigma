import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAppState } from '../../src/context/AppStateContext';
import { SPORT_META } from '../../src/data/sources';
import { COLORS, RADIUS, SPACING } from '../../src/theme';

export default function AccuracyScreen() {
  const { modelState } = useAppState();

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>How Accurate Are Our Predictions?</Text>
      <Text style={styles.sub}>
        We test the app on past games one week at a time—without peeking ahead—so you can see where our percentages are most reliable.
      </Text>

      {modelState.backtests.map((bt) => {
        const beatsSimpleGuess = bt.modelBrier < bt.baselineBrier;
        // Convert internal error score into a friendly 0–100 Accuracy Score
        const accuracyScore = Math.max(40, Math.min(98, Math.round((1 - bt.modelBrier * 0.75) * 100)));

        return (
          <View key={bt.leagueId} style={styles.panel}>
            <View style={styles.rowBetween}>
              <Text style={styles.leagueTitle}>
                {SPORT_META[bt.sport].label} · {bt.leagueName}
              </Text>
              <Text style={[styles.badge, beatsSimpleGuess ? styles.badgeOk : styles.badgeWarn]}>
                {beatsSimpleGuess ? 'Strong Track Record' : 'Building History'}
              </Text>
            </View>

            <View style={styles.metricsRow}>
              <View style={styles.metricBox}>
                <Text style={styles.mLabel}>Accuracy Score</Text>
                <Text style={styles.mVal}>{accuracyScore}/100</Text>
              </View>
              <View style={styles.metricBox}>
                <Text style={styles.mLabel}>Games Tested</Text>
                <Text style={styles.mVal}>{bt.sampleSize}</Text>
              </View>
              <View style={styles.metricBox}>
                <Text style={styles.mLabel}>Practice Profit</Text>
                <Text
                  style={[
                    styles.mVal,
                    { color: bt.simulatedRoiPct >= 0 ? COLORS.emerald : COLORS.crimson },
                  ]}
                >
                  {bt.simulatedRoiPct >= 0 ? '+' : ''}
                  {bt.simulatedRoiPct.toFixed(1)}%
                </Text>
              </View>
            </View>

            {bt.calibrationBins.length > 0 && (
              <View style={{ gap: 6, marginTop: 4 }}>
                <Text style={styles.subHead}>When we predict a win chance, how often do they actually win?</Text>
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
                      Won {Math.round(bin.actualRate * 100)}% ({bin.count})
                    </Text>
                  </View>
                ))}
              </View>
            )}
          </View>
        );
      })}

      {/* Free Public Data Sources Health */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>Free Public Data Sources (Synced On Your Phone)</Text>
        {modelState.feedStatuses.map((fs) => (
          <View key={fs.feedId} style={styles.feedRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.feedName}>{fs.label}</Text>
              <Text style={styles.feedMeta}>
                {fs.recordsParsed > 0
                  ? `${fs.recordsParsed} games loaded on your device`
                  : fs.errorMessage ?? 'Using built-in offline starter data'}
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
              {fs.status === 'ok' ? 'LIVE' : fs.status === 'cached' ? 'OFFLINE' : 'RETRY'}
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
  heading: { fontSize: 20, fontWeight: '800', color: COLORS.text },
  sub: { fontSize: 13, color: COLORS.textMuted, lineHeight: 19 },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 16,
    gap: 10,
  },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  leagueTitle: { fontSize: 15, fontWeight: '800', color: COLORS.text },
  badge: {
    fontSize: 11,
    fontWeight: '700',
    paddingHorizontal: 9,
    paddingVertical: 3,
    borderRadius: RADIUS.pill,
    overflow: 'hidden',
  },
  badgeOk: { backgroundColor: COLORS.emeraldDim, color: COLORS.emerald },
  badgeWarn: { backgroundColor: COLORS.amberDim, color: COLORS.amber },
  metricsRow: { flexDirection: 'row', gap: 8 },
  metricBox: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.md,
    padding: 10,
    alignItems: 'center',
  },
  mLabel: { fontSize: 11, fontWeight: '600', color: COLORS.textMuted },
  mVal: { fontSize: 16, fontWeight: '800', color: COLORS.text, marginTop: 3 },
  subHead: { fontSize: 12, fontWeight: '600', color: COLORS.textSecondary },
  binRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  binLabel: { fontSize: 11, fontWeight: '700', color: COLORS.textSecondary, width: 52 },
  barTrack: {
    flex: 1,
    height: 9,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: 5,
    overflow: 'hidden',
  },
  barActual: { height: '100%', backgroundColor: COLORS.accent },
  binVal: { fontSize: 11, fontWeight: '600', color: COLORS.textMuted, width: 82, textAlign: 'right' },
  sectionTitle: { fontSize: 15, fontWeight: '800', color: COLORS.text },
  feedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 7,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
  },
  feedName: { fontSize: 13, fontWeight: '700', color: COLORS.text },
  feedMeta: { fontSize: 11.5, color: COLORS.textMuted, marginTop: 2 },
  feedStatus: { fontSize: 11, fontWeight: '800' },
});
