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
import { blendFootballPredictions, predictEloMatch } from '../../src/engine/eloEngine';
import { evaluateMatchSignals } from '../../src/engine/kelly';
import { profileKey } from '../../src/engine/teamStats';
import { predictAllMarkets } from '../../src/engine/marketPredictor';
import { TeamAvatar } from '../../src/components/TeamAvatar';
import { ProbBar } from '../../src/components/ProbBar';
import { StatCompareBar } from '../../src/components/StatCompareBar';
import { COLORS, RADIUS, SPACING } from '../../src/theme';
import { SportId, TennisSurface } from '../../src/types';

const SPORTS: SportId[] = ['football', 'nfl', 'nba', 'nhl', 'mlb', 'tennis'];
const SURFACES: TennisSurface[] = ['Hard', 'Clay', 'Grass'];

export default function CompareScreen() {
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

  const count = Math.max(1, teamsForSport.length);
  const safeHomeIdx = homeIdx % count;
  const safeAwayIdx = awayIdx % count === safeHomeIdx && count > 1 ? (safeHomeIdx + 1) % count : awayIdx % count;

  const homeTeam = teamsForSport[safeHomeIdx];
  const awayTeam = teamsForSport[safeAwayIdx];
  const pH = homeTeam ? modelState.teamProfiles?.[profileKey(sport, homeTeam.team)] : undefined;
  const pA = awayTeam ? modelState.teamProfiles?.[profileKey(sport, awayTeam.team)] : undefined;

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

  const fullMarkets = useMemo(() => {
    if (!homeTeam || !awayTeam) return null;
    if (sport === 'football') {
      return predictAllMarkets({
        sport: 'football',
        home: {
          name: homeTeam.team,
          strengthRating: homeTeam.elo,
          goalsForAvg: pH?.avgScored ?? 1.4,
          goalsAgainstAvg: pH?.avgConceded ?? 1.2,
          splitGoalsForAvg: pH?.homeAvgScored,
          splitGoalsAgainstAvg: pH?.homeAvgConceded,
          shotsOnTargetForAvg: pH?.football?.shotsOnTargetPerGame,
          shotsOnTargetAgainstAvg: pH?.football?.shotsOnTargetConcededPerGame,
          totalShotsForAvg: pH?.football?.shotsPerGame,
          totalShotsAgainstAvg: pH?.football?.shotsConcededPerGame,
          firstHalfGoalsForAvg: pH?.football?.avgFirstHalfGoalsScored,
          cornersForAvg: pH?.football?.cornersPerGame,
          cornersAgainstAvg: pH?.football?.cornersConcededPerGame,
          foulsForAvg: pH?.football?.foulsPerGame,
          yellowCardsAvg: pH?.football?.yellowCardsPerGame,
          redCardsAvg: pH?.football?.redCardsPerGame,
          recentForm: pH?.recentMatches,
        },
        away: {
          name: awayTeam.team,
          strengthRating: awayTeam.elo,
          goalsForAvg: pA?.avgScored ?? 1.3,
          goalsAgainstAvg: pA?.avgConceded ?? 1.3,
          splitGoalsForAvg: pA?.awayAvgScored,
          splitGoalsAgainstAvg: pA?.awayAvgConceded,
          shotsOnTargetForAvg: pA?.football?.shotsOnTargetPerGame,
          shotsOnTargetAgainstAvg: pA?.football?.shotsOnTargetConcededPerGame,
          totalShotsForAvg: pA?.football?.shotsPerGame,
          totalShotsAgainstAvg: pA?.football?.shotsConcededPerGame,
          firstHalfGoalsForAvg: pA?.football?.avgFirstHalfGoalsScored,
          cornersForAvg: pA?.football?.cornersPerGame,
          cornersAgainstAvg: pA?.football?.cornersConcededPerGame,
          foulsForAvg: pA?.football?.foulsPerGame,
          yellowCardsAvg: pA?.football?.yellowCardsPerGame,
          redCardsAvg: pA?.football?.redCardsPerGame,
          recentForm: pA?.recentMatches,
        },
      });
    }
    if (sport === 'tennis') {
      return predictAllMarkets({
        sport: 'atp_tennis',
        surface,
        playerA: {
          name: homeTeam.team,
          overallRating: homeTeam.elo,
          surfaceRatings: homeTeam.surfaceElo,
          firstServeWonPct: (pH?.tennis?.firstServeWinPct ?? 73) / 100,
          acesPerMatchAvg: pH?.tennis?.avgAcesPerMatch,
          doubleFaultsAvg: pH?.tennis?.avgDoubleFaultsPerMatch,
          recentForm: pH?.recentMatches,
        },
        playerB: {
          name: awayTeam.team,
          overallRating: awayTeam.elo,
          surfaceRatings: awayTeam.surfaceElo,
          firstServeWonPct: (pA?.tennis?.firstServeWinPct ?? 73) / 100,
          acesPerMatchAvg: pA?.tennis?.avgAcesPerMatch,
          doubleFaultsAvg: pA?.tennis?.avgDoubleFaultsPerMatch,
          recentForm: pA?.recentMatches,
        },
      });
    }
    return predictAllMarkets({
      sport,
      home: {
        name: homeTeam.team,
        strengthRating: homeTeam.elo,
        offenseAvg: pH?.avgScored ?? (sport === 'nba' ? 113 : sport === 'nfl' ? 23 : 3.2),
        defenseAvg: pH?.avgConceded ?? (sport === 'nba' ? 112 : sport === 'nfl' ? 21 : 3.0),
        splitOffenseAvg: pH?.homeAvgScored,
        splitDefenseAvg: pH?.homeAvgConceded,
        recentForm: pH?.recentMatches,
        restDays: pH?.nfl?.avgRestDays,
      },
      away: {
        name: awayTeam.team,
        strengthRating: awayTeam.elo,
        offenseAvg: pA?.avgScored ?? (sport === 'nba' ? 112 : sport === 'nfl' ? 22 : 3.0),
        defenseAvg: pA?.avgConceded ?? (sport === 'nba' ? 113 : sport === 'nfl' ? 22 : 3.1),
        splitOffenseAvg: pA?.awayAvgScored,
        splitDefenseAvg: pA?.awayAvgConceded,
        recentForm: pA?.recentMatches,
        restDays: pA?.nfl?.avgRestDays,
      },
    });
  }, [homeTeam, awayTeam, sport, surface, pH, pA]);

  const signals = useMemo(() => {
    if (!prediction || !homeTeam || !awayTeam) return [];
    const h = Math.max(1.02, Number.parseFloat(oddsHome) || 2.0);
    const d = SPORT_META[sport].hasDraw ? Math.max(1.02, Number.parseFloat(oddsDraw) || 3.4) : undefined;
    const a = Math.max(1.02, Number.parseFloat(oddsAway) || 2.0);
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
      <Text style={styles.heading}>Compare Any Two Teams Offline</Text>
      <Text style={styles.sub}>
        Tap either team card below to cycle through teams in your saved database and predict every market—even without internet.
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
              {SPORT_META[s].label}
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
              <Text style={[styles.chipText, surface === sf && styles.chipTextActive]}>{sf} Court</Text>
            </Pressable>
          ))}
        </View>
      )}

      {/* Team Selectors */}
      <View style={styles.pickerRow}>
        <Pressable
          style={styles.teamBtn}
          onPress={() => setHomeIdx((i) => (i + 1) % count)}
        >
          <TeamAvatar teamName={homeTeam?.team ?? 'Home'} size={44} />
          <Text style={styles.pickerLabel}>HOME · TAP TO CHANGE</Text>
          <Text numberOfLines={1} style={styles.teamName}>{homeTeam?.team ?? '—'}</Text>
          <Text style={styles.powerText}>Power {pH?.powerScore ?? 55}/99</Text>
        </Pressable>

        <Pressable
          style={styles.teamBtn}
          onPress={() => setAwayIdx((i) => (i + 1) % count)}
        >
          <TeamAvatar teamName={awayTeam?.team ?? 'Away'} size={44} />
          <Text style={styles.pickerLabel}>AWAY · TAP TO CHANGE</Text>
          <Text numberOfLines={1} style={styles.teamName}>{awayTeam?.team ?? '—'}</Text>
          <Text style={styles.powerText}>Power {pA?.powerScore ?? 55}/99</Text>
        </Pressable>
      </View>

      {/* Win Chance Visual Bar */}
      {prediction && homeTeam && awayTeam && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Predicted Win Chances</Text>
          <ProbBar
            homeLabel={homeTeam.team}
            drawLabel="Draw"
            awayLabel={awayTeam.team}
            homeProb={prediction.probHome}
            drawProb={SPORT_META[sport].hasDraw ? prediction.probDraw : undefined}
            awayProb={prediction.probAway}
            barHeight={12}
          />
          {fullMarkets?.football && (
            <Text style={styles.metaLine}>
              Projected: {fullMarkets.football.projectedGoals.home.toFixed(1)} –{' '}
              {fullMarkets.football.projectedGoals.away.toFixed(1)} Goals · Corners:{' '}
              {fullMarkets.football.teamProps.corners.projectedTotal.toFixed(1)} · Cards:{' '}
              {fullMarkets.football.teamProps.cards.projectedTotal.toFixed(1)}
            </Text>
          )}
          {fullMarkets?.teamSport && (
            <Text style={styles.metaLine}>
              Projected Score: {fullMarkets.teamSport.projectedScore.display} · Spread:{' '}
              {fullMarkets.teamSport.projectedSpread.display} · Total:{' '}
              {fullMarkets.teamSport.projectedTotal}
            </Text>
          )}
          {fullMarkets?.tennis && (
            <Text style={styles.metaLine}>
              Projected Games: {fullMarkets.tennis.totalGames.projectedTotal} · Tiebreak:{' '}
              {Math.round(fullMarkets.tennis.tiebreakChance * 100)}% · Aces:{' '}
              {fullMarkets.tennis.projectedAces.total}
            </Text>
          )}
        </View>
      )}

      {/* Full Multi-Market Top Picks Offline */}
      {fullMarkets && fullMarkets.topPicks.length > 0 && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Top Picks for This Matchup</Text>
          {fullMarkets.topPicks.map((pk) => (
            <View key={pk.id} style={styles.sigRow}>
              <View style={{ flex: 1, paddingRight: 8 }}>
                <Text style={styles.sigTitle}>{pk.label}</Text>
                <Text style={styles.sigSub}>{pk.plainReason}</Text>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <Text style={[styles.evText, { color: pk.confidence === 'Strong' ? COLORS.emerald : COLORS.accent }]}>
                  {pk.chancePct}%
                </Text>
                <Text style={styles.sigSub}>{pk.confidence}</Text>
              </View>
            </View>
          ))}
        </View>
      )}

      {/* Side-by-Side Stats */}
      {pH && pA && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Side-by-Side Stats</Text>
          <StatCompareBar label="Win Rate" homeValue={pH.winPct} awayValue={pA.winPct} unit="%" decimals={0} />
          <StatCompareBar label="Scored per Game" homeValue={pH.avgScored} awayValue={pA.avgScored} decimals={1} />
          <StatCompareBar label="Allowed per Game" homeValue={pH.avgConceded} awayValue={pA.avgConceded} decimals={1} higherIsBetter={false} />
        </View>
      )}

      {/* Plain-English Odds Checker */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>Check Bookie Odds &amp; Suggested Bet</Text>
        <View style={styles.inputRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.pickerLabel}>Home Odds</Text>
            <TextInput style={styles.input} keyboardType="decimal-pad" value={oddsHome} onChangeText={setOddsHome} />
          </View>
          {SPORT_META[sport].hasDraw && (
            <View style={{ flex: 1 }}>
              <Text style={styles.pickerLabel}>Draw Odds</Text>
              <TextInput style={styles.input} keyboardType="decimal-pad" value={oddsDraw} onChangeText={setOddsDraw} />
            </View>
          )}
          <View style={{ flex: 1 }}>
            <Text style={styles.pickerLabel}>Away Odds</Text>
            <TextInput style={styles.input} keyboardType="decimal-pad" value={oddsAway} onChangeText={setOddsAway} />
          </View>
        </View>

        {signals.map((s) => {
          const stakeAmt = Math.round(settings.bankroll * s.kellyFraction);
          return (
            <View key={s.side} style={[styles.sigRow, s.isValue && styles.sigRowPos]}>
              <View>
                <Text style={styles.sigTitle}>{s.label} @ {s.decimalOdds.toFixed(2)}</Text>
                <Text style={styles.sigSub}>
                  Our Chance: {Math.round(s.modelProb * 100)}% · Bookie Fair: {Math.round(s.fairMarketProb * 100)}%
                </Text>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <Text style={[styles.evText, { color: s.isValue ? COLORS.emerald : COLORS.textMuted }]}>
                  {s.isValue ? 'Good Value' : 'Skip'}
                </Text>
                <Text style={styles.sigSub}>
                  {stakeAmt > 0 ? `Suggested: $${stakeAmt}` : 'No edge'}
                </Text>
              </View>
            </View>
          );
        })}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  content: { padding: SPACING.md, gap: 14, paddingBottom: 36 },
  heading: { fontSize: 20, fontWeight: '800', color: COLORS.text },
  sub: { fontSize: 13, color: COLORS.textMuted, lineHeight: 19 },
  chipRow: { gap: 8 },
  surfaceRow: { flexDirection: 'row', gap: 8 },
  chip: {
    paddingHorizontal: 13,
    paddingVertical: 7,
    borderRadius: RADIUS.pill,
    borderWidth: 1,
    borderColor: COLORS.border,
    backgroundColor: COLORS.surface,
  },
  chipActive: { borderColor: COLORS.accent, backgroundColor: COLORS.accentDim },
  chipText: { fontSize: 12, fontWeight: '700', color: COLORS.textSecondary },
  chipTextActive: { color: COLORS.accent },
  pickerRow: { flexDirection: 'row', gap: 10 },
  teamBtn: {
    flex: 1,
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 14,
    alignItems: 'center',
    gap: 5,
  },
  pickerLabel: { fontSize: 10, fontWeight: '700', color: COLORS.textMuted },
  teamName: { fontSize: 14, fontWeight: '800', color: COLORS.text, textAlign: 'center' },
  powerText: { fontSize: 12, fontWeight: '700', color: COLORS.accent },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 16,
    gap: 12,
  },
  sectionTitle: { fontSize: 15, fontWeight: '800', color: COLORS.text },
  metaLine: { fontSize: 12.5, color: COLORS.textSecondary, lineHeight: 18 },
  inputRow: { flexDirection: 'row', gap: 8 },
  input: {
    marginTop: 4,
    backgroundColor: COLORS.surfaceRaised,
    borderWidth: 1,
    borderColor: COLORS.border,
    borderRadius: RADIUS.sm,
    color: COLORS.text,
    fontSize: 15,
    fontWeight: '700',
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  sigRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.md,
    padding: 12,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  sigRowPos: { borderColor: COLORS.emerald, backgroundColor: COLORS.emeraldDim },
  sigTitle: { fontSize: 14, fontWeight: '700', color: COLORS.text },
  sigSub: { fontSize: 12, color: COLORS.textSecondary, marginTop: 2 },
  evText: { fontSize: 13, fontWeight: '800' },
});
