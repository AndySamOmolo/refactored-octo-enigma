import {
  CalibrationBin,
  HistoricalMatch,
  LeagueBacktestSummary,
  SportId,
} from '../types';
import { expectedEloScore } from './eloEngine';
import { computeExpectedValue, computeFractionalKelly } from './kelly';

/**
 * Runs a strict chronological walk-forward out-of-sample backtest for a league.
 * Evaluates every match strictly BEFORE updating team ratings with its result
 * (zero future leakage), computing Brier Score, Log-Loss, 5-bin calibration,
 * and simulated Quarter-Kelly ROI when historical odds are present.
 */
export function runWalkForwardBacktest(
  sport: SportId,
  leagueId: string,
  leagueName: string,
  matches: HistoricalMatch[]
): LeagueBacktestSummary {
  const sorted = [...matches].sort((a, b) => a.date.localeCompare(b.date));
  const elo: Record<string, number> = {};
  const isThreeWay = sport === 'football';
  const homeAdv =
    sport === 'football' ? 65 : sport === 'nba' ? 72 : sport === 'nfl' ? 48 : sport === 'nhl' ? 35 : sport === 'mlb' ? 24 : 0;
  const k = sport === 'tennis' ? 28 : sport === 'football' ? 24 : 20;

  // Warm-up on the first 25% of matches before scoring out-of-sample
  const warmupCount = Math.min(20, Math.floor(sorted.length * 0.25));

  let evalCount = 0;
  let modelBrierSum = 0;
  let baseBrierSum = 0;
  let modelLogLossSum = 0;
  let baseLogLossSum = 0;

  let bankroll = 1000;
  let totalStaked = 0;

  const bins = [
    { label: '0–20%', min: 0, max: 0.2, predSum: 0, hitSum: 0, n: 0 },
    { label: '20–40%', min: 0.2, max: 0.4, predSum: 0, hitSum: 0, n: 0 },
    { label: '40–60%', min: 0.4, max: 0.6, predSum: 0, hitSum: 0, n: 0 },
    { label: '60–80%', min: 0.6, max: 0.8, predSum: 0, hitSum: 0, n: 0 },
    { label: '80–100%', min: 0.8, max: 1.01, predSum: 0, hitSum: 0, n: 0 },
  ];

  const eps = 1e-6;

  for (let i = 0; i < sorted.length; i++) {
    const m = sorted[i];
    const rH = elo[m.homeTeam] ?? 1500;
    const rA = elo[m.awayTeam] ?? 1500;

    const rawH = expectedEloScore(rH + homeAdv, rA);
    let pH = rawH;
    let pD = 0;
    let pA = 1 - rawH;

    if (isThreeWay) {
      const gap = Math.abs(rH + homeAdv - rA);
      pD = Math.max(0.11, 0.285 * Math.exp(-Math.pow(gap / 360, 2)));
      pH = rawH * (1 - pD);
      pA = (1 - rawH) * (1 - pD);
    }

    const yH = m.homeScore > m.awayScore ? 1 : 0;
    const yD = m.homeScore === m.awayScore ? 1 : 0;
    const yA = m.homeScore < m.awayScore ? 1 : 0;

    if (i >= warmupCount) {
      evalCount += 1;

      if (isThreeWay) {
        modelBrierSum +=
          Math.pow(pH - yH, 2) + Math.pow(pD - yD, 2) + Math.pow(pA - yA, 2);
        // Naive 3-way baseline (44% home, 27% draw, 29% away)
        baseBrierSum +=
          Math.pow(0.44 - yH, 2) + Math.pow(0.27 - yD, 2) + Math.pow(0.29 - yA, 2);

        const actualProb = yH ? pH : yD ? pD : pA;
        const baseProb = yH ? 0.44 : yD ? 0.27 : 0.29;
        modelLogLossSum += -Math.log(Math.max(eps, actualProb));
        baseLogLossSum += -Math.log(Math.max(eps, baseProb));
      } else {
        modelBrierSum += Math.pow(pH - yH, 2);
        baseBrierSum += Math.pow(0.54 - yH, 2);
        const actualProb = yH ? pH : pA;
        const baseProb = yH ? 0.54 : 0.46;
        modelLogLossSum += -Math.log(Math.max(eps, actualProb));
        baseLogLossSum += -Math.log(Math.max(eps, baseProb));
      }

      // Calibration bin for home win probability
      const bin = bins.find((b) => pH >= b.min && pH < b.max) ?? bins[2];
      bin.predSum += pH;
      bin.hitSum += yH;
      bin.n += 1;

      // Simulated Quarter-Kelly ROI when historical odds exist
      if (m.oddsHome && m.oddsAway) {
        const evH = computeExpectedValue(pH, m.oddsHome);
        const evA = computeExpectedValue(pA, m.oddsAway);
        if (evH >= 0.03 && evH >= evA) {
          const frac = computeFractionalKelly(pH, m.oddsHome, 0.25, 0.05);
          const stake = bankroll * frac;
          totalStaked += stake;
          bankroll += yH ? stake * (m.oddsHome - 1) : -stake;
        } else if (evA >= 0.03) {
          const frac = computeFractionalKelly(pA, m.oddsAway, 0.25, 0.05);
          const stake = bankroll * frac;
          totalStaked += stake;
          bankroll += yA ? stake * (m.oddsAway - 1) : -stake;
        }
      }
    }

    // Update ratings strictly AFTER evaluating out-of-sample
    const actualScore = yH ? 1 : yD ? 0.5 : 0;
    const delta = k * (actualScore - rawH);
    elo[m.homeTeam] = rH + delta;
    elo[m.awayTeam] = rA - delta;
  }

  const denom = Math.max(1, evalCount);
  const calibrationBins: CalibrationBin[] = bins
    .filter((b) => b.n > 0)
    .map((b) => ({
      binLabel: b.label,
      predictedMean: Number((b.predSum / b.n).toFixed(3)),
      actualRate: Number((b.hitSum / b.n).toFixed(3)),
      count: b.n,
    }));

  const roiPct =
    totalStaked > 0 ? Number((((bankroll - 1000) / totalStaked) * 100).toFixed(2)) : 0;

  return {
    sport,
    leagueId,
    leagueName,
    sampleSize: evalCount,
    modelBrier: Number((modelBrierSum / denom).toFixed(4)),
    baselineBrier: Number((baseBrierSum / denom).toFixed(4)),
    modelLogLoss: Number((modelLogLossSum / denom).toFixed(4)),
    baselineLogLoss: Number((baseLogLossSum / denom).toFixed(4)),
    simulatedRoiPct: roiPct,
    calibrationBins,
  };
}
