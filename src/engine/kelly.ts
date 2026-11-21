import { MatchPrediction, ValueSignal } from '../types';

export interface DeViggedMarket {
  overround: number;
  fairHome: number;
  fairDraw?: number;
  fairAway: number;
}

/**
 * Removes bookmaker overround (multiplicative normalization) to recover fair market probabilities.
 */
export function deVigOdds(
  oddsHome: number,
  oddsAway: number,
  oddsDraw?: number
): DeViggedMarket {
  const invH = 1 / Math.max(1.01, oddsHome);
  const invA = 1 / Math.max(1.01, oddsAway);
  const invD = oddsDraw && oddsDraw > 1.01 ? 1 / oddsDraw : 0;
  const overround = invH + invA + invD;

  return {
    overround: Number(overround.toFixed(4)),
    fairHome: Number((invH / overround).toFixed(4)),
    fairDraw: invD > 0 ? Number((invD / overround).toFixed(4)) : undefined,
    fairAway: Number((invA / overround).toFixed(4)),
  };
}

/**
 * Computes Expected Value: EV = (p * decimalOdds) - 1.
 */
export function computeExpectedValue(modelProb: number, decimalOdds: number): number {
  if (decimalOdds <= 1.01 || modelProb <= 0) return -1;
  return Number((modelProb * decimalOdds - 1).toFixed(4));
}

/**
 * Computes Fractional Kelly stake fraction capped at maxStakePct (default 5%).
 * Full Kelly: f* = (b*p - q) / b, where b = decimalOdds - 1 and q = 1 - p.
 */
export function computeFractionalKelly(
  modelProb: number,
  decimalOdds: number,
  kellyMultiplier: number = 0.25,
  maxStakePct: number = 0.05
): number {
  const b = decimalOdds - 1;
  if (b <= 0.01 || modelProb <= 0 || modelProb >= 1) return 0;
  const q = 1 - modelProb;
  const fullKelly = (b * modelProb - q) / b;
  if (fullKelly <= 0) return 0;
  const scaled = fullKelly * kellyMultiplier;
  return Number(Math.min(maxStakePct, Math.max(0, scaled)).toFixed(4));
}

/**
 * Evaluates all outcomes of a match against market odds and returns all ValueSignals,
 * plus the highest-EV qualifying signal if any exceeds minEvThreshold.
 */
export function evaluateMatchSignals(
  prediction: MatchPrediction,
  homeTeam: string,
  awayTeam: string,
  marketOdds: { home: number; draw?: number; away: number },
  options: {
    kellyMultiplier?: number;
    maxStakePct?: number;
    minEvThreshold?: number;
  } = {}
): { signals: ValueSignal[]; bestSignal?: ValueSignal } {
  const kellyMultiplier = options.kellyMultiplier ?? 0.25;
  const maxStakePct = options.maxStakePct ?? 0.05;
  const minEvThreshold = options.minEvThreshold ?? 0.03;

  const devig = deVigOdds(marketOdds.home, marketOdds.away, marketOdds.draw);
  const candidates: Array<{
    side: 'HOME' | 'DRAW' | 'AWAY';
    label: string;
    prob: number;
    odds: number;
    fair: number;
  }> = [
    {
      side: 'HOME',
      label: homeTeam,
      prob: prediction.probHome,
      odds: marketOdds.home,
      fair: devig.fairHome,
    },
    {
      side: 'AWAY',
      label: awayTeam,
      prob: prediction.probAway,
      odds: marketOdds.away,
      fair: devig.fairAway,
    },
  ];

  if (marketOdds.draw && marketOdds.draw > 1.01 && prediction.probDraw > 0) {
    candidates.splice(1, 0, {
      side: 'DRAW',
      label: 'Draw',
      prob: prediction.probDraw,
      odds: marketOdds.draw,
      fair: devig.fairDraw ?? 1 / marketOdds.draw,
    });
  }

  const signals: ValueSignal[] = candidates.map((c) => {
    const ev = computeExpectedValue(c.prob, c.odds);
    const kelly = computeFractionalKelly(c.prob, c.odds, kellyMultiplier, maxStakePct);
    return {
      side: c.side,
      label: c.label,
      modelProb: c.prob,
      impliedProb: Number((1 / c.odds).toFixed(4)),
      fairMarketProb: c.fair,
      decimalOdds: c.odds,
      expectedValue: ev,
      kellyFraction: kelly,
      isValue: ev >= minEvThreshold && kelly > 0,
    };
  });

  const qualifying = signals
    .filter((s) => s.isValue)
    .sort((a, b) => b.expectedValue - a.expectedValue);

  return {
    signals,
    bestSignal: qualifying[0],
  };
}
