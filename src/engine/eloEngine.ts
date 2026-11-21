import { HistoricalMatch, MatchPrediction, SportId, TeamRating, TennisSurface } from '../types';

const SPORT_ELO_CONFIG: Record<
  SportId,
  { kFactor: number; homeAdv: number; movScale: number }
> = {
  football: { kFactor: 24, homeAdv: 65, movScale: 0.45 },
  nfl: { kFactor: 20, homeAdv: 48, movScale: 0.32 },
  nba: { kFactor: 20, homeAdv: 72, movScale: 0.28 },
  nhl: { kFactor: 18, homeAdv: 35, movScale: 0.4 },
  mlb: { kFactor: 12, homeAdv: 24, movScale: 0.35 },
  tennis: { kFactor: 28, homeAdv: 0, movScale: 0.15 },
};

export function expectedEloScore(ratingA: number, ratingB: number): number {
  return 1 / (1 + Math.pow(10, (ratingB - ratingA) / 400));
}

export function ratingKey(sport: SportId, team: string): string {
  return `${sport}::${team}`;
}

/**
 * Fits chronological Elo ratings with Margin-of-Victory multiplier,
 * optional ClubElo anchor (for Football), and Surface-specific Elo (for Tennis).
 */
export function fitMultiSportElo(
  matches: HistoricalMatch[],
  clubEloMap: Record<string, number> = {}
): Record<string, TeamRating> {
  const sorted = [...matches].sort((a, b) => a.date.localeCompare(b.date));
  const ratings: Record<string, TeamRating> = {};

  const getOrInit = (sport: SportId, leagueId: string, team: string): TeamRating => {
    const key = ratingKey(sport, team);
    if (!ratings[key]) {
      const anchor = sport === 'football' ? clubEloMap[team] : undefined;
      ratings[key] = {
        team,
        sport,
        leagueId,
        elo: anchor ?? 1500,
        clubElo: anchor,
        surfaceElo:
          sport === 'tennis' ? { Hard: 1500, Clay: 1500, Grass: 1500 } : undefined,
        recentForm: [],
        matchesPlayed: 0,
      };
    }
    return ratings[key];
  };

  for (const m of sorted) {
    const cfg = SPORT_ELO_CONFIG[m.sport];
    const h = getOrInit(m.sport, m.leagueId, m.homeTeam);
    const a = getOrInit(m.sport, m.leagueId, m.awayTeam);

    const expH = expectedEloScore(h.elo + cfg.homeAdv, a.elo);
    const actualH =
      m.homeScore > m.awayScore ? 1 : m.homeScore === m.awayScore ? 0.5 : 0;

    const margin = Math.abs(m.homeScore - m.awayScore);
    const movMultiplier =
      margin === 0 ? 1 : Math.log(margin + 1) * cfg.movScale + 0.85;
    const delta = cfg.kFactor * movMultiplier * (actualH - expH);

    h.elo = Math.round(h.elo + delta);
    a.elo = Math.round(a.elo - delta);
    h.matchesPlayed += 1;
    a.matchesPlayed += 1;

    h.recentForm = [
      ...h.recentForm.slice(-4),
      actualH === 1 ? 'W' : actualH === 0.5 ? 'D' : 'L',
    ];
    a.recentForm = [
      ...a.recentForm.slice(-4),
      actualH === 0 ? 'W' : actualH === 0.5 ? 'D' : 'L',
    ];

    if (m.sport === 'tennis' && m.surface && h.surfaceElo && a.surfaceElo) {
      const surf = m.surface;
      const sh = h.surfaceElo[surf] ?? 1500;
      const sa = a.surfaceElo[surf] ?? 1500;
      const sExp = expectedEloScore(sh, sa);
      const sDelta = cfg.kFactor * (actualH - sExp);
      h.surfaceElo[surf] = Math.round(sh + sDelta);
      a.surfaceElo[surf] = Math.round(sa - sDelta);
    }
  }

  // Blend final ClubElo anchor smoothly for football clubs (65% computed, 35% ClubElo)
  for (const r of Object.values(ratings)) {
    if (r.sport === 'football' && clubEloMap[r.team]) {
      r.clubElo = clubEloMap[r.team];
      r.elo = Math.round(r.elo * 0.65 + clubEloMap[r.team] * 0.35);
    }
  }

  return ratings;
}

/**
 * Predicts 2-way or 3-way match probabilities from Elo ratings.
 */
export function predictEloMatch(
  sport: SportId,
  homeRating: TeamRating | undefined,
  awayRating: TeamRating | undefined,
  surface?: TennisSurface
): MatchPrediction {
  const cfg = SPORT_ELO_CONFIG[sport];

  let rH = homeRating?.elo ?? 1500;
  let rA = awayRating?.elo ?? 1500;

  if (sport === 'tennis' && surface) {
    const sH = homeRating?.surfaceElo?.[surface] ?? rH;
    const sA = awayRating?.surfaceElo?.[surface] ?? rA;
    rH = 0.55 * rH + 0.45 * sH;
    rA = 0.55 * rA + 0.45 * sA;
  }

  const rawWinH = expectedEloScore(rH + cfg.homeAdv, rA);
  const sampleMin = Math.min(homeRating?.matchesPlayed ?? 0, awayRating?.matchesPlayed ?? 0);
  const confidenceScore = Math.min(92, Math.round(42 + Math.min(50, sampleMin * 2.5)));

  if (sport === 'football') {
    // Draw probability peaks near ~28% when teams are evenly matched and decays with Elo gap
    const eloDiff = Math.abs(rH + cfg.homeAdv - rA);
    const probDraw = Math.max(0.11, 0.285 * Math.exp(-Math.pow(eloDiff / 360, 2)));
    const probHome = rawWinH * (1 - probDraw);
    const probAway = (1 - rawWinH) * (1 - probDraw);
    return {
      probHome: Number(probHome.toFixed(4)),
      probDraw: Number(probDraw.toFixed(4)),
      probAway: Number(probAway.toFixed(4)),
      modelSource: 'dixon-coles+elo',
      confidenceScore,
    };
  }

  return {
    probHome: Number(rawWinH.toFixed(4)),
    probDraw: 0,
    probAway: Number((1 - rawWinH).toFixed(4)),
    modelSource: sport === 'tennis' ? 'surface-elo' : 'elo-mov',
    confidenceScore,
  };
}

/**
 * Blends Dixon-Coles (70%) and ClubElo/Elo (30%) for Football fixtures,
 * preserving Dixon-Coles goal expectations and exact scoreline distributions.
 */
export function blendFootballPredictions(
  dc: MatchPrediction,
  elo: MatchPrediction,
  dcWeight: number = 0.7
): MatchPrediction {
  const ew = 1 - dcWeight;
  const pH = dc.probHome * dcWeight + elo.probHome * ew;
  const pD = dc.probDraw * dcWeight + elo.probDraw * ew;
  const pA = dc.probAway * dcWeight + elo.probAway * ew;
  const sum = pH + pD + pA;

  return {
    ...dc,
    probHome: Number((pH / sum).toFixed(4)),
    probDraw: Number((pD / sum).toFixed(4)),
    probAway: Number((pA / sum).toFixed(4)),
    modelSource: 'dixon-coles+elo',
    confidenceScore: Math.round((dc.confidenceScore + elo.confidenceScore) / 2),
  };
}
