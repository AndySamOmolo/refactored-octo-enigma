import React, { useMemo, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useAppState } from '../../src/context/AppStateContext';
import { SPORT_META } from '../../src/data/sources';
import { predictDixonColesMatch } from '../../src/engine/dixonColes';
import { blendFootballPredictions, predictEloMatch, ratingKey } from '../../src/engine/eloEngine';
import { evaluateMatchSignals } from '../../src/engine/kelly';
import { COLORS, FONTS, RADIUS, SPACING } from '../../src/theme';
import { SportId, TennisSurface } from '../../src/types';

const SPORTS: SportId[] = ['football', 'nfl', 'nba', 'nhl', 'mlb', 'tennis'];
const SURFACES: TennisSurface[] = ['Hard', 'Clay', 'Grass'];

export default function SimulatorScreen() {
  const { modelState, settings } = useAppState();
  const [sport, setSport] = useState<SportId>('football');
  const [surface, setSurface] = useState<TennisSurface>('Hard');
  const [homeIdx, setHomeIdx] = useState(0);
  const [awayIdx, setAwayIdx] = useState(1);
  const [oddsHome, setOddsHome] = useState('2.10');
  const [oddsDraw, setOddsDraw] = useState('3.40');
  const [oddsAway, setOddsAway] = useState('3.50');

  const teamsForSport = useMemo(() => {
    return Object.values(modelState.ratings)
      .filter((r) => r.sport === sport)
      .sort((a, b) => b.elo - a.elo);
  }, [modelState.ratings, sport]);

  const homeTeam = teamsForSport[homeIdx % Math.max(1, teamsForSport.length)];
  const awayTeam = teamsForSport[awayIdx % Math.max(1, teamsForSport.length)];

  const prediction = useMemo(() => {
    if (!homeTeam || !awayTeam) return null;
    const eloPred = predictEloMatch(sport, homeTeam, awayTeam, surface);
    if (sport === 'football') {
      const dcLeague = modelState.dixonColes[homeTeam.leagueId];
      if (dcLeague) {
        const dcPred = predictDixonColesMatch(dcLeague, homeTeam.team, awayTeam.team);
        return blendFootballPredictions(dcPred, eloPred, 0.7);
      }
    }
    return eloPred;
  }, [homeTeam, awayTeam, sport, surface, modelState.dixonColes]);

  const signals = useMemo(() => {
    if (!prediction || !homeTeam || !awayTeam) return [];
    const h = Number.parseFloat(oddsHome) || 2.0;
    const d = SPORT_META[sport].hasDraw ? Number.parseFloat(oddsDraw) || 3.4 : undefined;
    const a = Number.parseFloat(oddsAway) || 2.0;
    return evaluateMatchSignals(
      prediction,
      homeTeam.team,
      awayTeam.team,
      { home: h, draw: d, away: a },
      {
        kellyMultiplier: settings.kellyMultiplier,
        maxStakePct: settings.maxStakePct,
        minEvThreshold: settings.minEvThreshold,
      }
    ).signals;
  }, [prediction, homeTeam, awayTeam, oddsHome, oddsDraw, oddsAway, sport, settings]);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>Offline Matchup &amp; Odds Simulator</Text>
      <Text style={styles.sub}>
        Pair any two teams or players from your locally trained database to compute calibrated probabilities and Quarter-Kelly stakes with zero internet access.
      </Text>

      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipRow}>
        {SPORTS.map((s) => (
          <Pressable
            key={s}
            style={[styles.chip, sport === s && styles.chipActive]}
            onPress={() => {
              setSport(s);
              setHomeIdx(0);
              setAwayIdx(1);
            }}
          >
            <Text style={[styles.chipText, sport === s && styles.chipTextActive]}>
              {SPORT_META[s].label.toUpperCase()}
            </Text>
          </Pressable>
        ))}
      </ScrollView>

      {sport === 'tennis' && (
        <View style={styles.surfaceRow}>
          {SURFACES.map((sf) => (
            <Pressable
              key={sf}
              style={[styles.chip, surface === sf && styles.chipActive]}
              onPress={() => setSurface(sf)}
            >
              <Text style={[styles.chipText, surface === sf && styles.chipTextActive]}>
                {sf.toUpperCase()}
              </Text>
            </Pressable>
          ))}
        </View>
      )}

      {/* Team Selectors */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>SELECT MATCHUP (TAP TO CYCLE)</Text>
        <View style={styles.pickerRow}>
          <Pressable
            style={styles.teamBtn}
            onPress={() => setHomeIdx((i) => (i + 1) % Math.max(1, teamsForSport.length))}
          >
            <Text style={styles.pickerLabel}>HOME / PLAYER A</Text>
            <Text style={styles.teamName}>{homeTeam?.team ?? '—'}</Text>
            <Text style={styles.eloText}>Elo {homeTeam?.elo ?? 1500}</Text>
          </Pressable>
          <Pressable
            style={styles.teamBtn}
            onPress={() => setAwayIdx((i) => (i + 1) % Math.max(1, teamsForSport.length))}
          >
            <Text style={styles.pickerLabel}>AWAY / PLAYER B</Text>
            <Text style={styles.teamName}>{awayTeam?.team ?? '—'}</Text>
            <Text style={styles.eloText}>Elo {awayTeam?.elo ?? 1500}</Text>
          </Pressable>
        </View>
      </View>

      {/* Predicted Probabilities */}
      {prediction && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>MODEL PROBABILITY OUTPUT</Text>
          <View style={styles.probRow}>
            <View style={styles.probBox}>
              <Text style={styles.pickerLabel}>HOME</Text>
              <Text style={styles.probBig}>{(prediction.probHome * 100).toFixed(1)}%</Text>
            </View>
            {SPORT_META[sport].hasDraw && (
              <View style={styles.probBox}>
                <Text style={styles.pickerLabel}>DRAW</Text>
                <Text style={styles.probBig}>{(prediction.probDraw * 100).toFixed(1)}%</Text>
              </View>
            )}
            <View style={styles.probBox}>
              <Text style={styles.pickerLabel}>AWAY</Text>
              <Text style={styles.probBig}>{(prediction.probAway * 100).toFixed(1)}%</Text>
            </View>
          </View>
          {prediction.expectedHomeGoals !== undefined && (
            <Text style={styles.metaLine}>
              Expected Goals: {prediction.expectedHomeGoals.toFixed(2)} –{' '}
              {prediction.expectedAwayGoals?.toFixed(2)} · Over 2.5:{' '}
              {((prediction.probOver25 ?? 0) * 100).toFixed(1)}% · BTTS:{' '}
              {((prediction.probBtts ?? 0) * 100).toFixed(1)}%
            </Text>
          )}
        </View>
      )}

      {/* Custom Odds & Kelly Sizer */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>CUSTOM DECIMAL ODDS &amp; EV CHECK</Text>
        <View style={styles.inputRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.pickerLabel}>HOME</Text>
            <TextInput
              style={styles.input}
              keyboardType="decimal-pad"
              value={oddsHome}
              onChangeText={setOddsHome}
            />
          </View>
          {SPORT_META[sport].hasDraw && (
            <View style={{ flex: 1 }}>
              <Text style={styles.pickerLabel}>DRAW</Text>
              <TextInput
                style={styles.input}
                keyboardType="decimal-pad"
                value={oddsDraw}
                onChangeText={setOddsDraw}
              />
            </View>
          )}
          <View style={{ flex: 1 }}>
            <Text style={styles.pickerLabel}>AWAY</Text>
            <TextInput
              style={styles.input}
              keyboardType="decimal-pad"
              value={oddsAway}
              onChangeText={setOddsAway}
            />
          </View>
        </View>

        {signals.map((s) => (
          <View key={s.side} style={[styles.sigRow, s.isValue && styles.sigRowPos]}>
            <View>
              <Text style={styles.sigTitle}>
                {s.label} @{s.decimalOdds.toFixed(2)}
              </Text>
              <Text style={styles.sigSub}>
                Model {(s.modelProb * 100).toFixed(1)}% · Fair {(s.fairMarketProb * 100).toFixed(1)}%
              </Text>
            </View>
            <View style={{ alignItems: 'flex-end' }}>
              <Text style={[styles.evText, { color: s.isValue ? COLORS.emerald : COLORS.textMuted }]}>
                {s.expectedValue >= 0 ? '+' : ''}
                {(s.expectedValue * 100).toFixed(1)}% EV
              </Text>
              <Text style={styles.sigSub}>
                Q-Kelly {(s.kellyFraction * 100).toFixed(2)}% ($
                {(settings.bankroll * s.kellyFraction).toFixed(0)})
              </Text>
            </View>
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
  chipRow: { gap: 8 },
  surfaceRow: { flexDirection: 'row', gap: 8 },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: RADIUS.pill,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surface,
  },
  chipActive: { borderColor: COLORS.accent, backgroundColor: COLORS.accentDim },
  chipText: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '600', color: COLORS.textSecondary },
  chipTextActive: { color: COLORS.accent },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: SPACING.md,
    gap: 10,
  },
  sectionTitle: { fontFamily: FONTS.mono, fontSize: 11, fontWeight: '700', color: COLORS.textSecondary },
  pickerRow: { flexDirection: 'row', gap: 10 },
  teamBtn: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 12,
    gap: 4,
  },
  pickerLabel: { fontFamily: FONTS.mono, fontSize: 10, color: COLORS.textMuted },
  teamName: { fontSize: 14, fontWeight: '700', color: COLORS.text },
  eloText: { fontFamily: FONTS.mono, fontSize: 12, color: COLORS.accent },
  probRow: { flexDirection: 'row', gap: 8 },
  probBox: {
    flex: 1,
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    padding: 12,
    alignItems: 'center',
  },
  probBig: { fontFamily: FONTS.mono, fontSize: 20, fontWeight: '700', color: COLORS.text, marginTop: 4 },
  metaLine: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textSecondary },
  inputRow: { flexDirection: 'row', gap: 8 },
  input: {
    marginTop: 4,
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
  sigRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    padding: 10,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  sigRowPos: { borderColor: COLORS.emerald, backgroundColor: COLORS.emeraldDim },
  sigTitle: { fontSize: 13, fontWeight: '700', color: COLORS.text },
  sigSub: { fontFamily: FONTS.mono, fontSize: 11, color: COLORS.textSecondary, marginTop: 2 },
  evText: { fontFamily: FONTS.mono, fontSize: 13, fontWeight: '700' },
});
