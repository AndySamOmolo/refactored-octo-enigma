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
import { profileKey } from '../../src/engine/teamStats';
import { deVigOdds, evaluateMatchSignals } from '../../src/engine/kelly';
import { TeamAvatar } from '../../src/components/TeamAvatar';
import { ProbBar } from '../../src/components/ProbBar';
import { FormDots } from '../../src/components/FormDots';
import { StatCompareBar } from '../../src/components/StatCompareBar';
import { COLORS, RADIUS, SPACING } from '../../src/theme';

export default function MatchDetailScreen() {
  const params = useLocalSearchParams<{ id?: string | string[] }>();
  const rawId = Array.isArray(params.id) ? params.id[0] : params.id ?? '';
  const { modelState, settings, addPaperBet } = useAppState();

  const fixture = useMemo(() => {
    let decoded = rawId;
    try {
      decoded = decodeURIComponent(rawId);
    } catch {
      decoded = rawId;
    }
    return modelState.fixtures.find((f) => f.id === rawId || f.id === decoded);
  }, [modelState.fixtures, rawId]);

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
        <Text style={styles.title}>Game not found</Text>
      </View>
    );
  }

  const hasDraw = SPORT_META[fixture.sport].hasDraw;
  const pH = modelState.teamProfiles?.[profileKey(fixture.sport, fixture.homeTeam)];
  const pA = modelState.teamProfiles?.[profileKey(fixture.sport, fixture.awayTeam)];
  const fb = fixture.markets?.football;
  const us = fixture.markets?.teamSport;
  const ten = fixture.markets?.tennis;

  const parsedHome = Math.max(1.02, Number.parseFloat(homeOddsInput) || 2.0);
  const parsedDraw = hasDraw ? Math.max(1.02, Number.parseFloat(drawOddsInput) || 3.4) : undefined;
  const parsedAway = Math.max(1.02, Number.parseFloat(awayOddsInput) || 2.0);

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

  const handleSavePick = async (label: string, odds: number, chance: number, ev: number, stakePct: number) => {
    const stake = Math.max(10, Number((settings.bankroll * Math.max(stakePct, 0.01)).toFixed(0)));
    await addPaperBet({
      fixtureId: fixture.id,
      sport: fixture.sport,
      leagueName: fixture.leagueName,
      matchup: `${fixture.homeTeam} vs ${fixture.awayTeam}`,
      selection: label,
      decimalOdds: odds,
      modelProb: chance,
      expectedValue: ev,
      stakeAmount: stake,
    });
    Alert.alert('Saved to My Picks', `${label} ($${stake} practice bet) added to your tracker.`);
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Hero Matchup Banner */}
      <View style={styles.panel}>
        <Text style={styles.leagueHeader}>
          {fixture.leagueName}
          {fixture.surface ? ` · ${fixture.surface} Court` : ''} ·{' '}
          {fixture.kickoffIso.slice(5, 16).replace('T', ' ')} UTC
        </Text>

        <View style={styles.heroRow}>
          <View style={styles.heroTeam}>
            <TeamAvatar teamName={fixture.homeTeam} size={52} />
            <Text style={styles.heroName}>{fixture.homeTeam}</Text>
            <Text style={styles.powerPill}>Power {pH?.powerScore ?? 55}/99</Text>
            <FormDots results={pH?.recentForm ?? []} size="sm" highlightLatest={false} />
          </View>

          <View style={styles.heroCenter}>
            <Text style={styles.projLabel}>PROJECTED</Text>
            <Text style={styles.projScore}>
              {fb
                ? `${fb.projectedGoals.home.toFixed(1)} – ${fb.projectedGoals.away.toFixed(1)}`
                : us
                ? us.projectedScore.display
                : ten
                ? `${Math.round(ten.matchWinner.playerA * 100)}–${Math.round(ten.matchWinner.playerB * 100)}`
                : 'VS'}
            </Text>
            <Text style={styles.projSub}>
              {fb ? 'Goals' : us ? us.projectedSpread.display : ten ? 'Win %' : ''}
            </Text>
          </View>

          <View style={styles.heroTeam}>
            <TeamAvatar teamName={fixture.awayTeam} size={52} />
            <Text style={styles.heroName}>{fixture.awayTeam}</Text>
            <Text style={styles.powerPill}>Power {pA?.powerScore ?? 55}/99</Text>
            <FormDots results={pA?.recentForm ?? []} size="sm" highlightLatest={false} />
          </View>
        </View>

        <ProbBar
          homeLabel={fixture.homeTeam}
          drawLabel="Draw"
          awayLabel={fixture.awayTeam}
          homeProb={fixture.prediction.probHome}
          drawProb={hasDraw ? fixture.prediction.probDraw : undefined}
          awayProb={fixture.prediction.probAway}
          barHeight={12}
        />
      </View>

      {/* Key Match Insights */}
      {fixture.plainInsights && fixture.plainInsights.length > 0 && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Key Match Insights</Text>
          {fixture.plainInsights.map((line, i) => (
            <View key={i} style={styles.bulletRow}>
              <View style={styles.bulletDot} />
              <Text style={styles.bulletText}>{line}</Text>
            </View>
          ))}
        </View>
      )}

      {/* Ranked Top Picks */}
      {fixture.markets?.topPicks && fixture.markets.topPicks.length > 0 && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Top Picks Across All Markets</Text>
          {fixture.markets.topPicks.map((pick) => (
            <View key={pick.id} style={styles.pickRow}>
              <View style={{ flex: 1, gap: 3 }}>
                <View style={styles.pickMetaRow}>
                  <Text style={styles.catBadge}>{pick.category}</Text>
                  <Text
                    style={[
                      styles.confLabel,
                      pick.confidence === 'Strong'
                        ? { color: COLORS.emerald }
                        : pick.confidence === 'Good'
                        ? { color: COLORS.accent }
                        : { color: COLORS.amber },
                    ]}
                  >
                    {pick.confidence} · {pick.chancePct}% chance
                  </Text>
                </View>
                <Text style={styles.pickLabel}>{pick.label}</Text>
                <Text style={styles.pickDesc}>{pick.plainReason}</Text>
              </View>
              <Pressable
                style={styles.trackBtn}
                onPress={() =>
                  handleSavePick(
                    pick.label,
                    Number((100 / Math.max(15, pick.chancePct)).toFixed(2)),
                    pick.chancePct / 100,
                    0.04,
                    0.02
                  )
                }
              >
                <Text style={styles.trackBtnText}>Save</Text>
              </Pressable>
            </View>
          ))}
        </View>
      )}

      {/* COMPLETE FOOTBALL MULTI-MARKET SUITE */}
      {fb && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Every Football Market Predicted</Text>

          <Text style={styles.subHead}>Safer Options &amp; Handicaps</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.homeTeam} or Draw</Text>
              <Text style={styles.statBig}>{Math.round(fb.doubleChance.homeOrDraw * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.awayTeam} or Draw</Text>
              <Text style={styles.statBig}>{Math.round(fb.doubleChance.awayOrDraw * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.homeTeam} (Draw = Refund)</Text>
              <Text style={styles.statBig}>{Math.round(fb.drawNoBet.home * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Home Win by 2+ Goals</Text>
              <Text style={styles.statBig}>{Math.round(fb.marginAndScorelines.homeWinBy2Plus * 100)}%</Text>
              <Text style={styles.statHint}>Away +1.5: {Math.round(fb.marginAndScorelines.awayPlus15 * 100)}%</Text>
            </View>
          </View>

          <Text style={styles.subHead}>Total Goals &amp; Both Teams Score</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Over 1.5 Goals</Text>
              <Text style={styles.statBig}>{Math.round(fb.goals.overUnder15.overProb * 100)}%</Text>
              <Text style={styles.statHint}>Over 0.5: {Math.round(fb.goals.overUnder05.overProb * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Over 2.5 Goals</Text>
              <Text style={styles.statBig}>{Math.round(fb.goals.overUnder25.overProb * 100)}%</Text>
              <Text style={styles.statHint}>Under 2.5: {Math.round(fb.goals.overUnder25.underProb * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Over 3.5 Goals</Text>
              <Text style={styles.statBig}>{Math.round(fb.goals.overUnder35.overProb * 100)}%</Text>
              <Text style={styles.statHint}>Over 4.5: {Math.round(fb.goals.overUnder45.overProb * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Both Teams to Score</Text>
              <Text style={styles.statBig}>{Math.round(fb.goals.btts.yes * 100)}%</Text>
              <Text style={styles.statHint}>No: {Math.round(fb.goals.btts.no * 100)}%</Text>
            </View>
          </View>

          <Text style={styles.subHead}>Team Goals, Clean Sheets &amp; Combos</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.homeTeam} Over 1.5 Goals</Text>
              <Text style={styles.statBig}>{Math.round(fb.teamGoals.homeOver15 * 100)}%</Text>
              <Text style={styles.statHint}>To Score (0.5+): {Math.round(fb.teamGoals.homeOver05 * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.awayTeam} Over 1.5 Goals</Text>
              <Text style={styles.statBig}>{Math.round(fb.teamGoals.awayOver15 * 100)}%</Text>
              <Text style={styles.statHint}>To Score (0.5+): {Math.round(fb.teamGoals.awayOver05 * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Home Clean Sheet</Text>
              <Text style={styles.statBig}>{Math.round(fb.teamGoals.homeCleanSheet * 100)}%</Text>
              <Text style={styles.statHint}>Win to Nil: {Math.round(fb.teamGoals.homeWinToNil * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Home Win &amp; Over 1.5</Text>
              <Text style={styles.statBig}>{Math.round(fb.combos.homeWinAndOver15 * 100)}%</Text>
              <Text style={styles.statHint}>Win &amp; Both Score: {Math.round(fb.combos.homeWinAndBtts * 100)}%</Text>
            </View>
          </View>

          <Text style={styles.subHead}>Halves &amp; Half-Time / Full-Time</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>1st Half Goal (Over 0.5)</Text>
              <Text style={styles.statBig}>{Math.round(fb.firstHalf.overUnder05.overProb * 100)}%</Text>
              <Text style={styles.statHint}>1st Half Over 1.5: {Math.round(fb.firstHalf.overUnder15.overProb * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>2nd Half Goal (Over 0.5)</Text>
              <Text style={styles.statBig}>{Math.round(fb.firstHalf.secondHalfOver05 * 100)}%</Text>
              <Text style={styles.statHint}>More Goals 2nd Half: {Math.round(fb.firstHalf.highestScoringHalf.secondHalf * 100)}%</Text>
            </View>
          </View>
          {fb.firstHalf.topHtFt.map((htft) => (
            <View key={htft.label} style={styles.h2hRow}>
              <Text style={styles.h2hDate}>Half-Time / Full-Time: {htft.label}</Text>
              <Text style={styles.h2hScore}>{Math.round(htft.prob * 100)}%</Text>
            </View>
          ))}

          <Text style={styles.subHead}>Corners, Cards, Fouls &amp; Shots</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Corners</Text>
              <Text style={styles.statBig}>{fb.teamProps.corners.projectedTotal.toFixed(1)}</Text>
              <Text style={styles.statHint}>
                O8.5: {Math.round(fb.teamProps.corners.overUnder85.overProb * 100)}% · O9.5: {Math.round(fb.teamProps.corners.overUnder95.overProb * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Cards</Text>
              <Text style={styles.statBig}>{fb.teamProps.cards.projectedTotal.toFixed(1)}</Text>
              <Text style={styles.statHint}>
                O3.5: {Math.round(fb.teamProps.cards.overUnder35.overProb * 100)}% · Red: {Math.round(fb.teamProps.cards.redCardChance * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Total Shots / On Target</Text>
              <Text style={styles.statBig}>{fb.teamProps.shots.projectedTotal.toFixed(0)} / {fb.teamProps.shotsOnTarget.projectedTotal.toFixed(1)}</Text>
              <Text style={styles.statHint}>
                On Target: {fb.teamProps.shotsOnTarget.projectedHome} vs {fb.teamProps.shotsOnTarget.projectedAway}
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Fouls</Text>
              <Text style={styles.statBig}>{fb.teamProps.fouls.projectedTotal.toFixed(1)}</Text>
              <Text style={styles.statHint}>
                Over 21.5: {Math.round(fb.teamProps.fouls.overUnder215.overProb * 100)}%
              </Text>
            </View>
          </View>

          <Text style={styles.subHead}>Most Likely Exact Scores</Text>
          <View style={styles.scoreGrid}>
            {fb.marginAndScorelines.topScorelines.map((s) => (
              <View key={s.score} style={styles.scorePill}>
                <Text style={styles.scoreText}>{s.score}</Text>
                <Text style={styles.scoreProb}>{Math.round(s.prob * 100)}%</Text>
              </View>
            ))}
          </View>
        </View>
      )}

      {/* COMPLETE US TEAM SPORTS SUITE (NFL / NBA / NHL / MLB) */}
      {us && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Every Game Market Predicted</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Score</Text>
              <Text style={styles.statBig}>{us.projectedScore.display}</Text>
              <Text style={styles.statHint}>Halftime: {us.firstHalf.projectedDisplay}</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Spread</Text>
              <Text style={styles.statBig}>{us.projectedSpread.display}</Text>
              <Text style={styles.statHint}>
                {us.projectedSpread.altLineLabel}: {Math.round(us.projectedSpread.altLineProb * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Total</Text>
              <Text style={styles.statBig}>{us.projectedTotal}</Text>
              <Text style={styles.statHint}>
                Over {us.overUnder.line}: {Math.round(us.overUnder.overProb * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{us.extras.closeGameLabel}</Text>
              <Text style={styles.statBig}>{Math.round(us.extras.closeGameProb * 100)}%</Text>
              <Text style={styles.statHint}>Overtime Chance: {Math.round(us.extras.overtimeChance * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.homeTeam} Over {us.teamTotals.homeLine}</Text>
              <Text style={styles.statBig}>{Math.round(us.teamTotals.homeOverProb * 100)}%</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>{fixture.awayTeam} Over {us.teamTotals.awayLine}</Text>
              <Text style={styles.statBig}>{Math.round(us.teamTotals.awayOverProb * 100)}%</Text>
            </View>
          </View>
          <Text style={styles.mutedNote}>{us.restSummary}</Text>
        </View>
      )}

      {/* COMPLETE ATP TENNIS SUITE */}
      {ten && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Every Tennis Market Predicted ({ten.surface})</Text>
          <View style={styles.grid2}>
            <View style={styles.statCard}>
              <Text style={styles.statName}>1st Set Winner</Text>
              <Text style={styles.statBig}>
                {Math.round(ten.firstSetWinner.playerA * 100)}% / {Math.round(ten.firstSetWinner.playerB * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Straight Sets vs 3 Sets</Text>
              <Text style={styles.statBig}>
                {Math.round(ten.setBetting.straightSetsTotal * 100)}% / {Math.round(ten.setBetting.goesToThreeSets * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Game Handicap</Text>
              <Text style={styles.statBig}>{ten.gameHandicap.favorite.split(' ')[0]} {ten.gameHandicap.line}</Text>
              <Text style={styles.statHint}>{Math.round(ten.gameHandicap.coverProb * 100)}% chance</Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Tiebreak in Match</Text>
              <Text style={styles.statBig}>{Math.round(ten.tiebreakChance * 100)}%</Text>
              <Text style={styles.statHint}>
                Serve Hold: {ten.serveHoldProjection.playerAHoldPct}% vs {ten.serveHoldProjection.playerBHoldPct}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Projected Total Games</Text>
              <Text style={styles.statBig}>{ten.totalGames.projectedTotal}</Text>
              <Text style={styles.statHint}>
                Over {ten.totalGames.overUnder.line}: {Math.round(ten.totalGames.overUnder.overProb * 100)}%
              </Text>
            </View>
            <View style={styles.statCard}>
              <Text style={styles.statName}>Aces / Double Faults</Text>
              <Text style={styles.statBig}>{ten.projectedAces.total} / {ten.projectedDoubleFaults.total}</Text>
              <Text style={styles.statHint}>
                Aces: {ten.projectedAces.playerA} vs {ten.projectedAces.playerB}
              </Text>
            </View>
          </View>
        </View>
      )}

      {/* Side-by-Side Team Stats */}
      {pH && pA && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>Team Stats Comparison</Text>
          <StatCompareBar label="Overall Win Rate" homeValue={pH.winPct} awayValue={pA.winPct} unit="%" decimals={0} />
          <StatCompareBar label="Scored per Game" homeValue={pH.avgScored} awayValue={pA.avgScored} decimals={1} />
          <StatCompareBar label="Allowed per Game" homeValue={pH.avgConceded} awayValue={pA.avgConceded} decimals={1} higherIsBetter={false} />
          {pH.football && pA.football && (
            <>
              <StatCompareBar label="Shots on Target" homeValue={pH.football.shotsOnTargetPerGame} awayValue={pA.football.shotsOnTargetPerGame} decimals={1} />
              <StatCompareBar label="Corners per Game" homeValue={pH.football.cornersPerGame} awayValue={pA.football.cornersPerGame} decimals={1} />
              <StatCompareBar label="Clean Sheets" homeValue={pH.football.cleanSheetPct} awayValue={pA.football.cleanSheetPct} unit="%" decimals={0} />
              <StatCompareBar label="Cards per Game" homeValue={pH.football.cardsPerGame} awayValue={pA.football.cardsPerGame} decimals={1} higherIsBetter={false} />
            </>
          )}
          {pH.nfl && pA.nfl && (
            <>
              <StatCompareBar label="Spread Cover Rate" homeValue={pH.nfl.atsCoverPct} awayValue={pA.nfl.atsCoverPct} unit="%" decimals={0} />
              <StatCompareBar label="High-Scoring Games (Over)" homeValue={pH.nfl.overTotalPct} awayValue={pA.nfl.overTotalPct} unit="%" decimals={0} />
            </>
          )}
          {pH.tennis && pA.tennis && (
            <>
              <StatCompareBar label="1st Serve Points Won" homeValue={pH.tennis.firstServeWinPct} awayValue={pA.tennis.firstServeWinPct} unit="%" decimals={0} />
              <StatCompareBar label="Aces per Match" homeValue={pH.tennis.avgAcesPerMatch} awayValue={pA.tennis.avgAcesPerMatch} decimals={1} />
            </>
          )}
        </View>
      )}

      {/* Recent Head-to-Head History */}
      {fixture.headToHead && fixture.headToHead.totalMeetings > 0 && (
        <View style={styles.panel}>
          <Text style={styles.sectionTitle}>
            Head-to-Head ({fixture.headToHead.homeTeamWins}W –{' '}
            {hasDraw ? `${fixture.headToHead.draws}D – ` : ''}
            {fixture.headToHead.awayTeamWins}W)
          </Text>
          {fixture.headToHead.lastFiveMeetings.map((m, idx) => (
            <View key={idx} style={styles.h2hRow}>
              <Text style={styles.h2hDate}>{m.date}</Text>
              <Text style={styles.h2hScore}>
                {m.homeTeam} {m.homeScore} – {m.awayScore} {m.awayTeam}
              </Text>
            </View>
          ))}
        </View>
      )}

      {/* Plain-English Odds & Suggested Bet Checker */}
      <View style={styles.panel}>
        <Text style={styles.sectionTitle}>Bookie Odds &amp; Suggested Bet Checker</Text>
        <Text style={styles.mutedNote}>
          Type your bookmaker&apos;s odds below to see if the payout beats the real chance (works 100% offline). Bookie margin: {((devig.overround - 1) * 100).toFixed(1)}%.
        </Text>

        <View style={styles.inputRow}>
          <View style={styles.inputCol}>
            <Text style={styles.inputLabel}>Home Odds</Text>
            <TextInput style={styles.input} keyboardType="decimal-pad" value={homeOddsInput} onChangeText={setHomeOddsInput} />
          </View>
          {hasDraw && (
            <View style={styles.inputCol}>
              <Text style={styles.inputLabel}>Draw Odds</Text>
              <TextInput style={styles.input} keyboardType="decimal-pad" value={drawOddsInput} onChangeText={setDrawOddsInput} />
            </View>
          )}
          <View style={styles.inputCol}>
            <Text style={styles.inputLabel}>Away Odds</Text>
            <TextInput style={styles.input} keyboardType="decimal-pad" value={awayOddsInput} onChangeText={setAwayOddsInput} />
          </View>
        </View>

        {signals.map((sig) => {
          const suggestedDollars = Math.round(settings.bankroll * sig.kellyFraction);
          return (
            <View key={sig.side} style={[styles.oddsRow, sig.isValue && styles.oddsRowValue]}>
              <View style={{ flex: 1 }}>
                <Text style={styles.pickLabel}>
                  {sig.label} @ {sig.decimalOdds.toFixed(2)}
                </Text>
                <Text style={styles.pickDesc}>
                  Our Chance: {Math.round(sig.modelProb * 100)}% · Bookie Fair: {Math.round(sig.fairMarketProb * 100)}%
                </Text>
              </View>
              <View style={{ alignItems: 'flex-end', marginRight: 10 }}>
                <Text style={[styles.valueTag, { color: sig.isValue ? COLORS.emerald : COLORS.textMuted }]}>
                  {sig.isValue ? `Good Value (+${(sig.expectedValue * 100).toFixed(0)}%)` : 'Fair / Skip'}
                </Text>
                <Text style={styles.pickDesc}>
                  {suggestedDollars > 0 ? `Suggested: $${suggestedDollars}` : 'No edge'}
                </Text>
              </View>
              <Pressable
                style={styles.trackBtn}
                onPress={() =>
                  handleSavePick(sig.label, sig.decimalOdds, sig.modelProb, sig.expectedValue, sig.kellyFraction)
                }
              >
                <Text style={styles.trackBtnText}>Save</Text>
              </Pressable>
            </View>
          );
        })}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  content: { padding: SPACING.md, paddingBottom: 42, gap: 14 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: COLORS.bg },
  title: { fontSize: 18, fontWeight: '700', color: COLORS.text },
  panel: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.border,
    padding: 16,
    gap: 12,
  },
  leagueHeader: { fontSize: 12, fontWeight: '700', color: COLORS.accent, textAlign: 'center' },
  heroRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  heroTeam: { flex: 1, alignItems: 'center', gap: 6 },
  heroName: { fontSize: 14, fontWeight: '800', color: COLORS.text, textAlign: 'center' },
  powerPill: { fontSize: 11, fontWeight: '700', color: COLORS.textSecondary },
  heroCenter: { alignItems: 'center', paddingHorizontal: 6 },
  projLabel: { fontSize: 10, fontWeight: '700', color: COLORS.textMuted },
  projScore: { fontSize: 22, fontWeight: '800', color: COLORS.text, marginVertical: 2 },
  projSub: { fontSize: 11, fontWeight: '600', color: COLORS.emerald, textAlign: 'center' },
  sectionTitle: { fontSize: 16, fontWeight: '800', color: COLORS.text },
  subHead: { fontSize: 12, fontWeight: '700', color: COLORS.textMuted, marginTop: 4 },
  bulletRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 8 },
  bulletDot: { width: 6, height: 6, borderRadius: 3, backgroundColor: COLORS.emerald, marginTop: 7 },
  bulletText: { flex: 1, fontSize: 13.5, color: COLORS.textSecondary, lineHeight: 20 },
  pickRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: COLORS.surfaceRaised,
    padding: 12,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  pickMetaRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  catBadge: {
    fontSize: 10,
    fontWeight: '700',
    color: COLORS.textSecondary,
    backgroundColor: COLORS.surface,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: 6,
    overflow: 'hidden',
  },
  confLabel: { fontSize: 11, fontWeight: '800' },
  pickLabel: { fontSize: 14, fontWeight: '800', color: COLORS.text },
  pickDesc: { fontSize: 12, color: COLORS.textMuted, lineHeight: 17 },
  trackBtn: {
    backgroundColor: COLORS.accent,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: RADIUS.sm,
  },
  trackBtnText: { fontSize: 12, fontWeight: '700', color: COLORS.text },
  grid2: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  statCard: {
    width: '48.5%',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.md,
    padding: 12,
    gap: 3,
  },
  statName: { fontSize: 12, fontWeight: '600', color: COLORS.textSecondary },
  statBig: { fontSize: 18, fontWeight: '800', color: COLORS.text },
  statHint: { fontSize: 11, fontWeight: '600', color: COLORS.emerald },
  scoreGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  scorePill: {
    width: '31%',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.sm,
    paddingVertical: 10,
    alignItems: 'center',
  },
  scoreText: { fontSize: 15, fontWeight: '800', color: COLORS.text },
  scoreProb: { fontSize: 12, fontWeight: '700', color: COLORS.accent, marginTop: 2 },
  mutedNote: { fontSize: 12.5, color: COLORS.textMuted, lineHeight: 18 },
  h2hRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: COLORS.border,
  },
  h2hDate: { fontSize: 12, color: COLORS.textMuted },
  h2hScore: { fontSize: 12.5, fontWeight: '700', color: COLORS.text },
  inputRow: { flexDirection: 'row', gap: 8 },
  inputCol: { flex: 1 },
  inputLabel: { fontSize: 11, fontWeight: '600', color: COLORS.textMuted, marginBottom: 4 },
  input: {
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
  oddsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.surfaceRaised,
    borderRadius: RADIUS.md,
    padding: 12,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  oddsRowValue: { borderColor: COLORS.emerald, backgroundColor: COLORS.emeraldDim },
  valueTag: { fontSize: 12, fontWeight: '800' },
});
