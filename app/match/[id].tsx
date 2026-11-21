import React, { useMemo, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useAppState } from '../../src/context/AppStateContext';
import { SPORT_META } from '../../src/data/sources';
import { ratingKey } from '../../src/engine/eloEngine';
import { deVigOdds, evaluateMatchSignals } from '../../src/engine/kelly';
import { COLORS, FONTS, RADIUS, SPACING } from '../../src/theme';

export default function MatchDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { modelState, settings, addPaperBet } = useAppState();

  const fixture = useMemo(
    () => modelState.fixtures.find((f) => f.id === decodeURIComponent(id ?? '')),
    [modelState.fixtures, id]
  );

  const [homeOddsInput, setHomeOddsInput] = useState(
    fixture?.marketOdds?.home?.toFixed(2) ?? '2.00'
  );
  const [drawOddsInput, setDrawOddsInput] = useState(
    fixture?.marketOdds?.draw?.toFixed(2) ?? '3.40'
  );
  const [awayOddsInput, setAwayOddsInput] = useState(
    fixture?.marketOdds?.away?.toFixed(2) ?? '3.60'
  );

  if (!fixture) {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Fixture not found in local pack</Text>
      </View>
    );
  }

  const hasDraw = SPORT_META[fixture.sport].hasDraw;
  const rH = modelState.ratings[ratingKey(fixture.sport, fixture.homeTeam)];
  const rA = modelState.ratings[ratingKey(fixture.sport, fixture.awayTeam)];

  const parsedHome = Number.parseFloat(homeOddsInput) || 2.0;
  const parsedDraw = hasDraw ? Number.parseFloat(drawOddsInput) || 3.4 : undefined;
  const parsedAway = Number.parseFloat(awayOddsInput) || 2.0;

  const devig = deVigOdds(parsedHome, parsedAway, parsedDraw);
  const { signals } = evaluateMatchSignals(
    fixture.prediction,
    fixture.homeTeam,
    fixture.awayTeam,
    { home: parsedHome, draw: parsedDraw, away: parsedAway },
    {
      kellyMultiplier: settings.kellyMultiplier,
      maxStakePct: settings.maxStakePct,
      minEvThreshold: settings.minEvThreshold,
    }
  );

  const handleLogPaperPick = async (sideIndex: number) => {
    const sig = signals[sideIndex];
    if (!sig) return;
    const stake = Math.max(
      10,
      Number((settings.bankroll * Math.max(sig.kellyFraction, 0.01)).toFixed(2))
    );
    await addPaperBet({
      fixtureId: fixture.id,
      sport: fixture.sport,
      leagueName: fixture.leagueName,
      matchup: `${fixture.homeTeam} vs ${fixture.awayTeam}`,
      selection: sig.label,
      decimalOdds: sig.decimalOdds,
      modelProb: sig.modelProb,
      expectedValue: sig.expectedValue,
      stakeAmount: stake,
    });
    Alert.alert('Paper Pick Logged', `${sig.label} @ ${sig.decimalOdds.toFixed(2)} ($${stake}) added to Ledger.`);
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.eyebrow}>
        {SPORT_META[fixture.sport].label.toUpperCase()} · {fixture.leagueName.toUpperCase()}
      </Text>
      <Text style={styles.title}>
        {fixture.homeTeam} vs {fixture.awayTeam}
      </Text>
      <Text style={styles.sub}>
        Kickoff: {fixture.kickoffIso.slice(0, 16).replace('T', ' ')} UTC · Model:{' '}
        {fixture.prediction.modelSource.toUpperCase()}
      </Text>

      {/* Team Ratings Comparison */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>ON-DEVICE TEAM RATINGS</Text>
        <View style={styles.splitRow}>
          <View style={styles.teamCol}>
            <Text style={styles.teamName}>{fixture.homeTeam}</Text>
            <Text style={styles.ratingNum}>Elo {rH?.elo ?? 1500}</Text>
            {rH?.clubElo && <Text style={styles.metaText}>ClubElo: {rH.clubElo}</Text>}
            {rH?.attack !== undefined && (
              <Text style={styles.metaText}>
                Atk {rH.attack.toFixed(2)} · Def {rH.defense?.toFixed(2)}
              </Text>
            )}
            <Text style={styles.formText}>
              Form: {rH?.recentForm.join(' ') || 'N/A'}
            </Text>
          </View>
          <View style={styles.teamCol}>
            <Text style={styles.teamName}>{fixture.awayTeam}</Text>
            <Text style={styles.ratingNum}>Elo {rA?.elo ?? 1500}</Text>
            {rA?.clubElo && <Text style={styles.metaText}>ClubElo: {rA.clubElo}</Text>}
            {rA?.attack !== undefined && (
              <Text style={styles.metaText}>
                Atk {rA.attack.toFixed(2)} · Def {rA.defense?.toFixed(2)}
              </Text>
            )}
            <Text style={styles.formText}>
              Form: {rA?.recentForm.join(' ') || 'N/A'}
            </Text>
          </View>
        </View>
      </View>

      {/* Dixon-Coles Goal & Scoreline Matrix (Football only) */}
      {fixture.prediction.topScorelines && fixture.prediction.topScorelines.length > 0 && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>DIXON-COLES GOAL DISTRIBUTION</Text>
          <View style={styles.kpiRow}>
            <View style={styles.kpiBox}>
              <Text style={styles.kpiLabel}>HOME xG</Text>
              <Text style={styles.kpiVal}>{fixture.prediction.expectedHomeGoals?.toFixed(2)}</Text>
            </View>
            <View style={styles.kpiBox}>
              <Text style={styles.kpiLabel}>AWAY xG</Text>
              <Text style={styles.kpiVal}>{fixture.prediction.expectedAwayGoals?.toFixed(2)}</Text>
            </View>
            <View style={styles.kpiBox}>
              <Text style={styles.kpiLabel}>OVER 2.5</Text>
              <Text style={styles.kpiVal}>
                {((fixture.prediction.probOver25 ?? 0) * 100).toFixed(1)}%
              </Text>
            </View>
            <View style={styles.kpiBox}>
              <Text style={styles.kpiLabel}>BTTS YES</Text>
              <Text style={styles.kpiVal}>
                {((fixture.prediction.probBtts ?? 0) * 100).toFixed(1)}%
              </Text>
            </View>
          </View>

          <Text style={styles.subLabel}>MOST LIKELY EXACT SCORELINES</Text>
          <View style={styles.scoreGrid}>
            {fixture.prediction.topScorelines.map((s) => (
              <View key={`${s.homeGoals}-${s.awayGoals}`} style={styles.scorePill}>
                <Text style={styles.scoreText}>
                  {s.homeGoals} – {s.awayGoals}
                </Text>
                <Text style={styles.scoreProb}>{(s.prob * 100).toFixed(1)}%</Text>
              </View>
            ))}
          </View>
        </View>
      )}

      {/* Offline Custom Bookmaker Odds & Value Edge Calculator */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>OFFLINE ODDS &amp; QUARTER-KELLY EVALUATOR</Text>
        <Text style={styles.metaText}>
          Enter any bookmaker&apos;s decimal odds below to recalculate de-vigged EV offline.
          Bookmaker overround: {((devig.overround - 1) * 100).toFixed(2)}%.
        </Text>

        <View style={styles.inputRow}>
          <View style={styles.inputCol}>
            <Text style={styles.inputLabel}>HOME ODDS</Text>
            <TextInput
              style={styles.input}
              keyboardType="decimal-pad"
              value={homeOddsInput}
              onChangeText={setHomeOddsInput}
              placeholderTextColor={COLORS.textMuted}
            />
          </View>
          {hasDraw && (
            <View style={styles.inputCol}>
              <Text style={styles.inputLabel}>DRAW ODDS</Text>
              <TextInput
                style={styles.input}
                keyboardType="decimal-pad"
                value={drawOddsInput}
                onChangeText={setDrawOddsInput}
                placeholderTextColor={COLORS.textMuted}
              />
            </View>
          )}
          <View style={styles.inputCol}>
            <Text style={styles.inputLabel}>AWAY ODDS</Text>
            <TextInput
              style={styles.input}
              keyboardType="decimal-pad"
              value={awayOddsInput}
              onChangeText={setAwayOddsInput}
              placeholderTextColor={COLORS.textMuted}
            />
          </View>
        </View>

        {signals.map((sig, idx) => (
          <View
            key={sig.side}
            style={[styles.signalRow, sig.isValue && styles.signalRowPositive]}
          >
            <View style={{ flex: 1 }}>
              <Text style={styles.signalName}>
                {sig.label} @{sig.decimalOdds.toFixed(2)}
              </Text>
              <Text style={styles.signalMeta}>
                Model {(sig.modelProb * 100).toFixed(1)}% vs Fair Market{' '}
                {(sig.fairMarketProb * 100).toFixed(1)}%
              </Text>
            </View>
            <View style={{ alignItems: 'flex-end', marginRight: 10 }}>
              <Text
                style={[
                  styles.evNum,
                  { color: sig.isValue ? COLORS.emerald : COLORS.textMuted },
                ]}
              >
                {sig.expectedValue >= 0 ? '+' : ''}
                {(sig.expectedValue * 100).toFixed(1)}% EV
              </Text>
              <Text style={styles.signalMeta}>
                Stake {(sig.kellyFraction * 100).toFixed(2)}%
              </Text>
            </View>
            <Pressable style={styles.logBtn} onPress={() => handleLogPaperPick(idx)}>
              <Text style={styles.logBtnText}>LOG</Text>
            </Pressable>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  content: { padding: SPACING.md, paddingBottom: 40, gap: 14 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: COLORS.bg },
  eyebrow: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    color: COLORS.accent,
    fontWeight: '700',
  },
  title: { fontSize: 22, fontWeight: '700', color: COLORS.text, marginTop: 4 },
  sub: { fontFamily: FONTS.mono, fontSize: 12, color: COLORS.textMuted },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: SPACING.md,
    gap: 10,
  },
  sectionTitle: {
    fontFamily: FONTS.mono,
    fontSize: 11,
    fontWeight: '700',
    color: COLORS.textSecondary,
    letterSpacing: 0.5,
  },
  splitRow: { flexDirection: 'row', gap: 12 },
  teamCol: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    padding: 12,
    borderRadius: RADIUS.sm,
    gap: 4,
  },
  teamName: { fontSize: 14, fontWeight: '700', color: COLORS.text },
  ratingNum: { fontFamily: FONTS.mono, fontSize: 16, fontWeight: '700', color: COLORS.accent },
  metaText: { fontSize: 12, color: COLORS.textMuted, lineHeight: 18 },
  formText: { fontFamily: FONTS.mono, fontSize: 12, color: COLORS.emerald, marginTop: 2 },
  kpiRow: { flexDirection: 'row', gap: 8 },
  kpiBox: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    padding: 10,
    alignItems: 'center',
  },
  kpiLabel: { fontFamily: FONTS.mono, fontSize: 10, color: COLORS.textMuted },
  kpiVal: { fontFamily: FONTS.mono, fontSize: 15, fontWeight: '700', color: COLORS.text, marginTop: 2 },
  subLabel: {
    fontFamily: FONTS.mono,
    fontSize: 10,
    color: COLORS.textMuted,
    marginTop: 4,
  },
  scoreGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  scorePill: {
    width: '31%',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    paddingVertical: 8,
    alignItems: 'center',
  },
  scoreText: { fontFamily: FONTS.mono, fontSize: 14, fontWeight: '700', color: COLORS.text },
  scoreProb: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.accent, marginTop: 2 },
  inputRow: { flexDirection: 'row', gap: 8 },
  inputCol: { flex: 1 },
  inputLabel: { fontFamily: FONTS.mono, fontSize: 10, color: COLORS.textMuted, marginBottom: 4 },
  input: {
    backgroundColor: COLORS.surfaceRaised,
    borderWidth: 1,
    borderColor: COLORS.border,
    borderRadius: RADIUS.sm,
    color: COLORS.text,
    fontFamily: FONTS.mono,
    fontSize: 15,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  signalRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    padding: 10,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  signalRowPositive: {
    borderColor: COLORS.emerald,
    backgroundColor: COLORS.emeraldDim,
  },
  signalName: { fontSize: 14, fontWeight: '700', color: COLORS.text },
  signalMeta: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textSecondary, marginTop: 2 },
  evNum: { fontFamily: FONTS.mono, fontSize: 14, fontWeight: '700' },
  logBtn: {
    backgroundColor: COLORS.accent,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: RADIUS.sm,
  },
  logBtnText: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '700', color: COLORS.text },
});
