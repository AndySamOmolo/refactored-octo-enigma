/**
 * marketPredictor.ts (v2.1 — Complete Multi-Market Engine)
 *
 * Self-contained, zero-dependency multi-sport prediction engine for offline-first
 * Android apps (Football, NFL, NBA, NHL, MLB, ATP Tennis).
 * Predicts EVERY major market derivable from free public sports datasets with
 * plain-English labels and zero technical jargon.
 */

export type MarketSportType = 'football' | 'nfl' | 'nba' | 'nhl' | 'mlb' | 'atp_tennis';
export type TeamSportType = 'nfl' | 'nba' | 'nhl' | 'mlb';
export type TennisSurface = 'Hard' | 'Clay' | 'Grass';
export type ConfidenceTier = 'Strong' | 'Good' | 'Lean';

export type MarketCategory =
  | 'Match Result'
  | 'Double Chance'
  | 'Draw No Bet'
  | 'Goals'
  | 'Team Goals'
  | 'Both Teams to Score'
  | 'Clean Sheet'
  | 'First Half'
  | 'Half-Time / Full-Time'
  | 'Combo'
  | 'Corners'
  | 'Cards & Fouls'
  | 'Shots'
  | 'Handicap'
  | 'Spread'
  | 'Total'
  | 'Margin'
  | 'Sets'
  | 'Games'
  | 'Serve & Tiebreak';

export interface RecentMatchResult {
  result: 'W' | 'D' | 'L';
  scored: number;
  conceded: number;
  isHome?: boolean;
}

export interface MarketHeadToHead {
  meetings: number;
  homeWins: number;
  draws?: number;
  awayWins: number;
  avgHomeScore?: number;
  avgAwayScore?: number;
}

export interface FootballTeamInput {
  name: string;
  strengthRating?: number;
  goalsForAvg: number;
  goalsAgainstAvg: number;
  splitGoalsForAvg?: number;
  splitGoalsAgainstAvg?: number;
  shotsOnTargetForAvg?: number;
  shotsOnTargetAgainstAvg?: number;
  totalShotsForAvg?: number;
  totalShotsAgainstAvg?: number;
  firstHalfGoalsForAvg?: number;
  firstHalfGoalsAgainstAvg?: number;
  cornersForAvg?: number;
  cornersAgainstAvg?: number;
  foulsForAvg?: number;
  yellowCardsAvg?: number;
  redCardsAvg?: number;
  recentForm?: RecentMatchResult[];
  restDays?: number;
}

export interface FootballMatchInput {
  sport: 'football';
  leagueAvgGoalsPerTeam?: number;
  home: FootballTeamInput;
  away: FootballTeamInput;
  headToHead?: MarketHeadToHead;
}

export interface TeamSportInput {
  name: string;
  strengthRating?: number;
  offenseAvg: number;
  defenseAvg: number;
  splitOffenseAvg?: number;
  splitDefenseAvg?: number;
  recentForm?: RecentMatchResult[];
  restDays?: number;
}

export interface TeamSportMatchInput {
  sport: TeamSportType;
  home: TeamSportInput;
  away: TeamSportInput;
  headToHead?: MarketHeadToHead;
  totalLine?: number;
  spreadLine?: number;
}

export interface TennisPlayerInput {
  name: string;
  overallRating?: number;
  surfaceRatings?: Partial<Record<TennisSurface, number>>;
  surfaceWinRate?: number;
  firstServeWonPct?: number;
  acesPerMatchAvg?: number;
  doubleFaultsAvg?: number;
  recentForm?: RecentMatchResult[];
}

export interface TennisMatchInput {
  sport: 'atp_tennis';
  surface: TennisSurface;
  bestOf?: 3 | 5;
  playerA: TennisPlayerInput;
  playerB: TennisPlayerInput;
  headToHead?: MarketHeadToHead;
  totalGamesLine?: number;
}

export type MatchPredictionInput =
  | FootballMatchInput
  | TeamSportMatchInput
  | TennisMatchInput;

export interface OverUnderMarket {
  line: number;
  overProb: number;
  underProb: number;
}

export interface ExactScoreline {
  score: string;
  homeGoals: number;
  awayGoals: number;
  prob: number;
}

export interface FootballMarketPrediction {
  projectedGoals: { home: number; away: number; total: number };
  matchWinner: { home: number; draw: number; away: number };
  doubleChance: { homeOrDraw: number; awayOrDraw: number; eitherTeamWins: number };
  drawNoBet: { home: number; away: number };
  goals: {
    overUnder05: OverUnderMarket;
    overUnder15: OverUnderMarket;
    overUnder25: OverUnderMarket;
    overUnder35: OverUnderMarket;
    overUnder45: OverUnderMarket;
    btts: { yes: number; no: number };
  };
  teamGoals: {
    homeOver05: number;
    homeOver15: number;
    awayOver05: number;
    awayOver15: number;
    homeCleanSheet: number;
    awayCleanSheet: number;
    homeWinToNil: number;
    awayWinToNil: number;
  };
  combos: {
    homeWinAndOver15: number;
    awayWinAndOver15: number;
    homeWinAndBtts: number;
    awayWinAndBtts: number;
    homeOrDrawAndUnder35: number;
  };
  firstHalf: {
    projectedGoals: { home: number; away: number; total: number };
    overUnder05: OverUnderMarket;
    overUnder15: OverUnderMarket;
    winner: { home: number; draw: number; away: number };
    secondHalfOver05: number;
    highestScoringHalf: { firstHalf: number; secondHalf: number; tie: number };
    topHtFt: Array<{ label: string; prob: number }>;
  };
  teamProps: {
    corners: {
      projectedHome: number;
      projectedAway: number;
      projectedTotal: number;
      overUnder85: OverUnderMarket;
      overUnder95: OverUnderMarket;
      overUnder105: OverUnderMarket;
    };
    cards: {
      projectedHome: number;
      projectedAway: number;
      projectedTotal: number;
      overUnder35: OverUnderMarket;
      overUnder45: OverUnderMarket;
      redCardChance: number;
    };
    fouls: {
      projectedHome: number;
      projectedAway: number;
      projectedTotal: number;
      overUnder215: OverUnderMarket;
    };
    shots: {
      projectedHomeTotal: number;
      projectedAwayTotal: number;
      projectedTotal: number;
    };
    shotsOnTarget: {
      projectedHome: number;
      projectedAway: number;
      projectedTotal: number;
    };
  };
  marginAndScorelines: {
    homeWinBy2Plus: number;
    awayWinBy2Plus: number;
    homePlus15: number;
    awayPlus15: number;
    topScorelines: ExactScoreline[];
  };
}

export interface TeamSportMarketPrediction {
  sport: TeamSportType;
  matchWinner: { home: number; away: number };
  projectedScore: { home: number; away: number; display: string };
  projectedSpread: {
    homeMargin: number;
    favorite: string;
    marginAbs: number;
    display: string;
    altLineLabel: string;
    altLineProb: number;
  };
  projectedTotal: number;
  overUnder: OverUnderMarket;
  teamTotals: {
    homeLine: number;
    homeOverProb: number;
    awayLine: number;
    awayOverProb: number;
  };
  firstHalf: {
    projectedDisplay: string;
    homeLeadProb: number;
  };
  extras: {
    closeGameLabel: string;
    closeGameProb: number;
    overtimeChance: number;
  };
  restSummary: string;
}

export interface TennisMarketPrediction {
  surface: TennisSurface;
  matchWinner: { playerA: number; playerB: number };
  firstSetWinner: { playerA: number; playerB: number };
  setBetting: {
    playerAStraightSets: number;
    playerBStraightSets: number;
    straightSetsTotal: number;
    goesToThreeSets: number;
  };
  gameHandicap: {
    favorite: string;
    line: number;
    coverProb: number;
  };
  tiebreakChance: number;
  projectedAces: { playerA: number; playerB: number; total: number };
  projectedDoubleFaults: { playerA: number; playerB: number; total: number };
  totalGames: { projectedTotal: number; overUnder: OverUnderMarket };
  serveHoldProjection: { playerAHoldPct: number; playerBHoldPct: number };
}

export interface MarketPick {
  id: string;
  label: string;
  category: MarketCategory;
  chancePct: number;
  confidence: ConfidenceTier;
  plainReason: string;
}

export interface FullMarketPrediction {
  sport: MarketSportType;
  matchLabel: string;
  football?: FootballMarketPrediction;
  teamSport?: TeamSportMarketPrediction;
  tennis?: TennisMarketPrediction;
  topPicks: MarketPick[];
}

function clamp(v: number, min: number, max: number): number {
  if (!Number.isFinite(v)) return min;
  return Math.max(min, Math.min(max, v));
}

function round(v: number, dec = 2): number {
  if (!Number.isFinite(v)) return 0;
  const f = Math.pow(10, dec);
  return Math.round(v * f) / f;
}

function toPct(p: number): number {
  return clamp(Math.round(p * 100), 1, 99);
}

function confidenceFromProb(p: number): ConfidenceTier {
  const pct = p * 100;
  if (pct >= 68) return 'Strong';
  if (pct >= 58) return 'Good';
  return 'Lean';
}

function logGamma(z: number): number {
  const c = [
    76.18009172947146,
    -86.50532032941677,
    24.01409824083091,
    -1.231739572450155,
    0.001208650973866179,
    -0.000005395239384953,
  ];
  let x = z;
  let y = z;
  let tmp = x + 5.5;
  tmp -= (x + 0.5) * Math.log(tmp);
  let ser = 1.000000000190015;
  for (let j = 0; j < c.length; j++) {
    y += 1;
    ser += c[j] / y;
  }
  return -tmp + Math.log((2.5066282746310005 * ser) / x);
}

function logFactorial(n: number): number {
  return n <= 1 ? 0 : logGamma(n + 1);
}

function countPmf(k: number, mean: number): number {
  const m = Math.max(0.01, mean);
  return Math.exp(k * Math.log(m) - m - logFactorial(k));
}

function overdispersedOverUnder(mean: number, line: number, disp: number): OverUnderMarket {
  const maxU = Math.floor(line);
  const m = Math.max(0.05, mean);
  const r = Math.max(0.5, disp);
  const p = r / (r + m);
  let under = 0;
  for (let k = 0; k <= maxU; k++) {
    const lp =
      logGamma(k + r) - logGamma(r) - logFactorial(k) + r * Math.log(p) + k * Math.log(1 - p);
    under += Math.exp(lp);
  }
  under = clamp(under, 0.02, 0.98);
  return { line, overProb: round(1 - under, 4), underProb: round(under, 4) };
}

function erf(x: number): number {
  const sign = x < 0 ? -1 : 1;
  const ax = Math.abs(x);
  const t = 1 / (1 + 0.3275911 * ax);
  const poly =
    (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t +
      0.254829592) *
      t);
  return sign * (1 - poly * Math.exp(-ax * ax));
}

function normalCdf(x: number, mean: number, sd: number): number {
  return 0.5 * (1 + erf((x - mean) / (Math.max(0.001, sd) * Math.SQRT2)));
}

function formScore(recent?: RecentMatchResult[]): { score: number; wins: number; n: number } {
  if (!recent || recent.length === 0) return { score: 0, wins: 0, n: 0 };
  const weights = [1.0, 0.85, 0.72, 0.6, 0.5];
  let wp = 0;
  let tw = 0;
  let wins = 0;
  const sl = recent.slice(0, 5);
  for (let i = 0; i < sl.length; i++) {
    const w = weights[i] ?? 0.45;
    tw += w;
    if (sl[i].result === 'W') {
      wins++;
      wp += w;
    } else if (sl[i].result === 'L') {
      wp -= w;
    }
  }
  return { score: clamp(tw > 0 ? wp / tw : 0, -1, 1), wins, n: sl.length };
}

function buildScoreMatrix(homeExp: number, awayExp: number, maxG = 7, rho = -0.09): number[][] {
  const mat: number[][] = [];
  let sum = 0;
  for (let h = 0; h <= maxG; h++) {
    const row: number[] = [];
    const ph = countPmf(h, homeExp);
    for (let a = 0; a <= maxG; a++) {
      const pa = countPmf(a, awayExp);
      let adj = 1;
      if (h === 0 && a === 0) adj = Math.max(0.05, 1 - homeExp * awayExp * rho);
      else if (h === 0 && a === 1) adj = Math.max(0.05, 1 + homeExp * rho);
      else if (h === 1 && a === 0) adj = Math.max(0.05, 1 + awayExp * rho);
      else if (h === 1 && a === 1) adj = Math.max(0.05, 1 - rho);
      const p = ph * pa * adj;
      row.push(p);
      sum += p;
    }
    mat.push(row);
  }
  for (let h = 0; h <= maxG; h++) {
    for (let a = 0; a <= maxG; a++) {
      mat[h][a] /= Math.max(1e-9, sum);
    }
  }
  return mat;
}

function rankPicks(
  cands: Array<{ group: string; prob: number; pick: MarketPick }>,
  limit = 6
): MarketPick[] {
  const map = new Map<string, { group: string; prob: number; pick: MarketPick }>();
  for (const c of cands) {
    const ex = map.get(c.group);
    if (!ex || c.prob > ex.prob) map.set(c.group, c);
  }
  return Array.from(map.values())
    .sort((a, b) => b.prob - a.prob)
    .slice(0, limit)
    .map((x) => x.pick);
}

function predictFootballMarkets(input: FootballMatchInput): {
  prediction: FootballMarketPrediction;
  picks: MarketPick[];
} {
  const { home, away, headToHead } = input;
  const lgAvg = clamp(input.leagueAvgGoalsPerTeam ?? 1.38, 0.9, 2.2);

  const hAtk = home.splitGoalsForAvg !== undefined ? 0.58 * home.splitGoalsForAvg + 0.42 * home.goalsForAvg : home.goalsForAvg * 1.08;
  const hDef = home.splitGoalsAgainstAvg !== undefined ? 0.58 * home.splitGoalsAgainstAvg + 0.42 * home.goalsAgainstAvg : home.goalsAgainstAvg * 0.94;
  const aAtk = away.splitGoalsForAvg !== undefined ? 0.58 * away.splitGoalsForAvg + 0.42 * away.goalsForAvg : away.goalsForAvg * 0.92;
  const aDef = away.splitGoalsAgainstAvg !== undefined ? 0.58 * away.splitGoalsAgainstAvg + 0.42 * away.goalsAgainstAvg : away.goalsAgainstAvg * 1.07;

  const hSot = home.shotsOnTargetForAvg ?? hAtk * 3.1;
  const aSotAg = away.shotsOnTargetAgainstAvg ?? aDef * 3.1;
  const aSot = away.shotsOnTargetForAvg ?? aAtk * 3.1;
  const hSotAg = home.shotsOnTargetAgainstAvg ?? hDef * 3.1;

  const rawH = (hAtk * aDef) / lgAvg;
  const rawA = (aAtk * hDef) / lgAvg;
  const shotH = ((hSot + aSotAg) / 2) * 0.31;
  const shotA = ((aSot + hSotAg) / 2) * 0.30;

  let homeExp = 0.72 * rawH + 0.28 * shotH;
  let awayExp = 0.72 * rawA + 0.28 * shotA;

  const rDiff = clamp((home.strengthRating ?? 1500) - (away.strengthRating ?? 1500), -450, 450);
  const rFac = clamp(rDiff / 850, -0.32, 0.32);
  homeExp *= 1 + rFac * 0.55;
  awayExp *= 1 - rFac * 0.55;

  const hForm = formScore(home.recentForm);
  const aForm = formScore(away.recentForm);
  homeExp *= 1 + hForm.score * 0.08;
  awayExp *= 1 + aForm.score * 0.08;

  if (headToHead && headToHead.meetings >= 2) {
    const h2hEdge = clamp((headToHead.homeWins - headToHead.awayWins) / (headToHead.meetings + 4), -0.3, 0.3);
    homeExp *= 1 + h2hEdge * 0.12;
    awayExp *= 1 - h2hEdge * 0.12;
  }

  homeExp = clamp(homeExp, 0.28, 4.1);
  awayExp = clamp(awayExp, 0.22, 3.7);

  const mat = buildScoreMatrix(homeExp, awayExp, 7, -0.09);
  let homeWin = 0;
  let draw = 0;
  let awayWin = 0;
  let o05 = 0;
  let o15 = 0;
  let o25 = 0;
  let o35 = 0;
  let o45 = 0;
  let btts = 0;
  let hO05 = 0;
  let hO15 = 0;
  let aO05 = 0;
  let aO15 = 0;
  let hCS = 0;
  let aCS = 0;
  let hWtn = 0;
  let aWtn = 0;
  let hWinO15 = 0;
  let aWinO15 = 0;
  let hWinBtts = 0;
  let aWinBtts = 0;
  let dc1xU35 = 0;
  let h2p = 0;
  let a2p = 0;
  let hPlus15 = 0;
  let aPlus15 = 0;
  const scores: ExactScoreline[] = [];

  for (let h = 0; h < mat.length; h++) {
    for (let a = 0; a < mat[h].length; a++) {
      const p = mat[h][a];
      const tot = h + a;
      if (h > a) homeWin += p;
      else if (h === a) draw += p;
      else awayWin += p;

      if (tot >= 1) o05 += p;
      if (tot >= 2) o15 += p;
      if (tot >= 3) o25 += p;
      if (tot >= 4) o35 += p;
      if (tot >= 5) o45 += p;
      if (h >= 1 && a >= 1) btts += p;

      if (h >= 1) hO05 += p;
      if (h >= 2) hO15 += p;
      if (a >= 1) aO05 += p;
      if (a >= 2) aO15 += p;
      if (a === 0) hCS += p;
      if (h === 0) aCS += p;
      if (h > 0 && a === 0) hWtn += p;
      if (a > 0 && h === 0) aWtn += p;

      if (h > a && tot >= 2) hWinO15 += p;
      if (a > h && tot >= 2) aWinO15 += p;
      if (h > a && a >= 1) hWinBtts += p;
      if (a > h && h >= 1) aWinBtts += p;
      if (h >= a && tot <= 3) dc1xU35 += p;

      if (h - a >= 2) h2p += p;
      if (a - h >= 2) a2p += p;
      if (h - a >= -1) hPlus15 += p;
      if (a - h >= -1) aPlus15 += p;

      scores.push({ score: `${h}-${a}`, homeGoals: h, awayGoals: a, prob: round(p, 4) });
    }
  }
  scores.sort((a, b) => b.prob - a.prob);

  // First Half vs Second Half + HT/FT transitions
  const h1Rate = home.firstHalfGoalsForAvg && home.goalsForAvg > 0.1 ? clamp(home.firstHalfGoalsForAvg / home.goalsForAvg, 0.34, 0.56) : 0.44;
  const a1Rate = away.firstHalfGoalsForAvg && away.goalsForAvg > 0.1 ? clamp(away.firstHalfGoalsForAvg / away.goalsForAvg, 0.34, 0.56) : 0.43;
  const h1Exp = clamp(homeExp * h1Rate, 0.12, 2.0);
  const a1Exp = clamp(awayExp * a1Rate, 0.1, 1.8);
  const h2Exp = Math.max(0.15, homeExp - h1Exp);
  const a2Exp = Math.max(0.12, awayExp - a1Exp);

  const htMat = buildScoreMatrix(h1Exp, a1Exp, 4, -0.05);
  const shMat = buildScoreMatrix(h2Exp, a2Exp, 4, -0.04);

  let htH = 0;
  let htD = 0;
  let htA = 0;
  let ht05 = 0;
  let ht15 = 0;
  let sh05 = 0;
  const htTotDist = [0, 0, 0, 0, 0, 0, 0, 0, 0];
  const shTotDist = [0, 0, 0, 0, 0, 0, 0, 0, 0];

  for (let h = 0; h < htMat.length; h++) {
    for (let a = 0; a < htMat[h].length; a++) {
      const p1 = htMat[h][a];
      const p2 = shMat[h][a];
      if (h > a) htH += p1;
      else if (h === a) htD += p1;
      else htA += p1;
      if (h + a >= 1) {
        ht05 += p1;
        sh05 += p2;
      }
      if (h + a >= 2) ht15 += p1;
      htTotDist[Math.min(8, h + a)] += p1;
      shTotDist[Math.min(8, h + a)] += p2;
    }
  }

  let firstHalfMore = 0;
  let secondHalfMore = 0;
  let halvesTie = 0;
  for (let g1 = 0; g1 <= 8; g1++) {
    for (let g2 = 0; g2 <= 8; g2++) {
      const p = htTotDist[g1] * shTotDist[g2];
      if (g1 > g2) firstHalfMore += p;
      else if (g2 > g1) secondHalfMore += p;
      else halvesTie += p;
    }
  }

  // Exact HT/FT convolution
  const htftMap: Record<string, number> = {
    [`${home.name} / ${home.name}`]: 0,
    [`Draw / ${home.name}`]: 0,
    ['Draw / Draw']: 0,
    [`Draw / ${away.name}`]: 0,
    [`${away.name} / ${away.name}`]: 0,
    [`${home.name} / Draw`]: 0,
    [`${away.name} / Draw`]: 0,
  };
  for (let h1 = 0; h1 < htMat.length; h1++) {
    for (let a1 = 0; a1 < htMat[h1].length; a1++) {
      const p1 = htMat[h1][a1];
      if (p1 < 0.0005) continue;
      const htRes = h1 > a1 ? home.name : h1 === a1 ? 'Draw' : away.name;
      for (let h2 = 0; h2 < shMat.length; h2++) {
        for (let a2 = 0; a2 < shMat[h2].length; a2++) {
          const p = p1 * shMat[h2][a2];
          const ftH = h1 + h2;
          const ftA = a1 + a2;
          const ftRes = ftH > ftA ? home.name : ftH === ftA ? 'Draw' : away.name;
          const k = `${htRes} / ${ftRes}`;
          if (htftMap[k] !== undefined) htftMap[k] += p;
        }
      }
    }
  }
  const topHtFt = Object.entries(htftMap)
    .map(([label, prob]) => ({ label, prob: round(prob, 4) }))
    .sort((a, b) => b.prob - a.prob)
    .slice(0, 3);

  // Corners, Cards, Fouls, Shots
  const projHC = clamp(0.55 * (home.cornersForAvg ?? 5.3) + 0.45 * (away.cornersAgainstAvg ?? 5.1) + clamp((homeExp - awayExp) * 0.25, -0.6, 0.6), 2.2, 9.2);
  const projAC = clamp(0.55 * (away.cornersForAvg ?? 4.6) + 0.45 * (home.cornersAgainstAvg ?? 4.8) - clamp((homeExp - awayExp) * 0.18, -0.5, 0.5), 1.8, 8.2);
  const projTotC = projHC + projAC;

  const tightBoost = Math.abs(homeExp - awayExp) < 0.45 ? 1.05 : 0.98;
  const redRate = clamp((home.redCardsAvg ?? 0.08) + (away.redCardsAvg ?? 0.09), 0.05, 0.45);
  const redCardChance = round(clamp(1 - Math.exp(-redRate), 0.06, 0.38), 4);
  const projHCards = clamp(((home.yellowCardsAvg ?? 1.85) + (home.redCardsAvg ?? 0.08)) * tightBoost, 0.9, 4.3);
  const projACards = clamp(((away.yellowCardsAvg ?? 2.05) + (away.redCardsAvg ?? 0.09)) * tightBoost, 0.9, 4.5);
  const projTotCards = projHCards + projACards;

  const projHFouls = clamp((home.foulsForAvg ?? 11.1) * tightBoost, 7, 17);
  const projAFouls = clamp((away.foulsForAvg ?? 11.7) * tightBoost, 7, 17);
  const projTotFouls = projHFouls + projAFouls;

  const projHShots = clamp(0.55 * (home.totalShotsForAvg ?? 13.2) + 0.45 * (away.totalShotsAgainstAvg ?? 12.1), 6, 22);
  const projAShots = clamp(0.55 * (away.totalShotsForAvg ?? 11.4) + 0.45 * (home.totalShotsAgainstAvg ?? 11.0), 5, 20);

  const projHSot = clamp(0.55 * hSot + 0.45 * aSotAg, 1.8, 9.2);
  const projASot = clamp(0.55 * aSot + 0.45 * hSotAg, 1.4, 8.2);

  const prediction: FootballMarketPrediction = {
    projectedGoals: { home: round(homeExp, 2), away: round(awayExp, 2), total: round(homeExp + awayExp, 2) },
    matchWinner: { home: round(homeWin, 4), draw: round(draw, 4), away: round(awayWin, 4) },
    doubleChance: {
      homeOrDraw: round(homeWin + draw, 4),
      awayOrDraw: round(awayWin + draw, 4),
      eitherTeamWins: round(homeWin + awayWin, 4),
    },
    drawNoBet: {
      home: round(homeWin / Math.max(0.001, homeWin + awayWin), 4),
      away: round(awayWin / Math.max(0.001, homeWin + awayWin), 4),
    },
    goals: {
      overUnder05: { line: 0.5, overProb: round(o05, 4), underProb: round(1 - o05, 4) },
      overUnder15: { line: 1.5, overProb: round(o15, 4), underProb: round(1 - o15, 4) },
      overUnder25: { line: 2.5, overProb: round(o25, 4), underProb: round(1 - o25, 4) },
      overUnder35: { line: 3.5, overProb: round(o35, 4), underProb: round(1 - o35, 4) },
      overUnder45: { line: 4.5, overProb: round(o45, 4), underProb: round(1 - o45, 4) },
      btts: { yes: round(btts, 4), no: round(1 - btts, 4) },
    },
    teamGoals: {
      homeOver05: round(hO05, 4),
      homeOver15: round(hO15, 4),
      awayOver05: round(aO05, 4),
      awayOver15: round(aO15, 4),
      homeCleanSheet: round(hCS, 4),
      awayCleanSheet: round(aCS, 4),
      homeWinToNil: round(hWtn, 4),
      awayWinToNil: round(aWtn, 4),
    },
    combos: {
      homeWinAndOver15: round(hWinO15, 4),
      awayWinAndOver15: round(aWinO15, 4),
      homeWinAndBtts: round(hWinBtts, 4),
      awayWinAndBtts: round(aWinBtts, 4),
      homeOrDrawAndUnder35: round(dc1xU35, 4),
    },
    firstHalf: {
      projectedGoals: { home: round(h1Exp, 2), away: round(a1Exp, 2), total: round(h1Exp + a1Exp, 2) },
      overUnder05: { line: 0.5, overProb: round(ht05, 4), underProb: round(1 - ht05, 4) },
      overUnder15: { line: 1.5, overProb: round(ht15, 4), underProb: round(1 - ht15, 4) },
      winner: { home: round(htH, 4), draw: round(htD, 4), away: round(htA, 4) },
      secondHalfOver05: round(sh05, 4),
      highestScoringHalf: {
        firstHalf: round(firstHalfMore, 4),
        secondHalf: round(secondHalfMore, 4),
        tie: round(halvesTie, 4),
      },
      topHtFt,
    },
    teamProps: {
      corners: {
        projectedHome: round(projHC, 1),
        projectedAway: round(projAC, 1),
        projectedTotal: round(projTotC, 1),
        overUnder85: overdispersedOverUnder(projTotC, 8.5, 16),
        overUnder95: overdispersedOverUnder(projTotC, 9.5, 16),
        overUnder105: overdispersedOverUnder(projTotC, 10.5, 16),
      },
      cards: {
        projectedHome: round(projHCards, 1),
        projectedAway: round(projACards, 1),
        projectedTotal: round(projTotCards, 1),
        overUnder35: overdispersedOverUnder(projTotCards, 3.5, 10),
        overUnder45: overdispersedOverUnder(projTotCards, 4.5, 10),
        redCardChance,
      },
      fouls: {
        projectedHome: round(projHFouls, 1),
        projectedAway: round(projAFouls, 1),
        projectedTotal: round(projTotFouls, 1),
        overUnder215: overdispersedOverUnder(projTotFouls, 21.5, 22),
      },
      shots: {
        projectedHomeTotal: round(projHShots, 1),
        projectedAwayTotal: round(projAShots, 1),
        projectedTotal: round(projHShots + projAShots, 1),
      },
      shotsOnTarget: {
        projectedHome: round(projHSot, 1),
        projectedAway: round(projASot, 1),
        projectedTotal: round(projHSot + projASot, 1),
      },
    },
    marginAndScorelines: {
      homeWinBy2Plus: round(h2p, 4),
      awayWinBy2Plus: round(a2p, 4),
      homePlus15: round(hPlus15, 4),
      awayPlus15: round(aPlus15, 4),
      topScorelines: scores.slice(0, 6),
    },
  };

  const cands: Array<{ group: string; prob: number; pick: MarketPick }> = [];

  if (homeWin >= awayWin && homeWin >= 0.50) {
    cands.push({
      group: 'winner',
      prob: homeWin,
      pick: {
        id: 'fb_home_win',
        label: `${home.name} to Win`,
        category: 'Match Result',
        chancePct: toPct(homeWin),
        confidence: confidenceFromProb(homeWin),
        plainReason: `${home.name} project ${round(homeExp, 1)} goals at home with ${round(projHSot, 1)} shots on target.`,
      },
    });
  } else if (awayWin > homeWin && awayWin >= 0.50) {
    cands.push({
      group: 'winner',
      prob: awayWin,
      pick: {
        id: 'fb_away_win',
        label: `${away.name} to Win`,
        category: 'Match Result',
        chancePct: toPct(awayWin),
        confidence: confidenceFromProb(awayWin),
        plainReason: `${away.name} carry stronger attacking pressure (${round(projASot, 1)} shots on target projected).`,
      },
    });
  }

  const dc1X = homeWin + draw;
  const dcX2 = awayWin + draw;
  if (dc1X >= dcX2 && dc1X >= 0.68) {
    cands.push({
      group: 'double_chance',
      prob: dc1X,
      pick: {
        id: 'fb_dc_1x',
        label: `${home.name} or Draw`,
        category: 'Double Chance',
        chancePct: toPct(dc1X),
        confidence: confidenceFromProb(dc1X),
        plainReason: `${home.name} hold home advantage and should avoid defeat.`,
      },
    });
  } else if (dcX2 > dc1X && dcX2 >= 0.68) {
    cands.push({
      group: 'double_chance',
      prob: dcX2,
      pick: {
        id: 'fb_dc_x2',
        label: `${away.name} or Draw`,
        category: 'Double Chance',
        chancePct: toPct(dcX2),
        confidence: confidenceFromProb(dcX2),
        plainReason: `${away.name} bring stronger overall form on the road.`,
      },
    });
  }

  if (o25 >= 0.55) {
    cands.push({
      group: 'goals25',
      prob: o25,
      pick: {
        id: 'fb_o25',
        label: 'Over 2.5 Goals',
        category: 'Goals',
        chancePct: toPct(o25),
        confidence: confidenceFromProb(o25),
        plainReason: `Both sides combine for ${round(homeExp + awayExp, 1)} projected goals and ${round(projHSot + projASot, 1)} shots on target.`,
      },
    });
  } else if (1 - o25 >= 0.56) {
    cands.push({
      group: 'goals25',
      prob: 1 - o25,
      pick: {
        id: 'fb_u25',
        label: 'Under 2.5 Goals',
        category: 'Goals',
        chancePct: toPct(1 - o25),
        confidence: confidenceFromProb(1 - o25),
        plainReason: `Tight defenses keep the projected total around ${round(homeExp + awayExp, 1)} goals.`,
      },
    });
  }

  if (btts >= 0.55) {
    cands.push({
      group: 'btts',
      prob: btts,
      pick: {
        id: 'fb_btts_yes',
        label: 'Both Teams to Score',
        category: 'Both Teams to Score',
        chancePct: toPct(btts),
        confidence: confidenceFromProb(btts),
        plainReason: `Both ${home.name} and ${away.name} create steady chances and score regularly.`,
      },
    });
  }

  if (ht05 >= 0.68) {
    cands.push({
      group: '1h',
      prob: ht05,
      pick: {
        id: 'fb_1h_o05',
        label: '1st Half Over 0.5 Goals',
        category: 'First Half',
        chancePct: toPct(ht05),
        confidence: confidenceFromProb(ht05),
        plainReason: `Fast-starting attacks project ${round(h1Exp + a1Exp, 1)} goals before halftime.`,
      },
    });
  }

  if (hWinO15 >= 0.52) {
    cands.push({
      group: 'combo',
      prob: hWinO15,
      pick: {
        id: 'fb_combo_h_o15',
        label: `${home.name} Win & Over 1.5 Goals`,
        category: 'Combo',
        chancePct: toPct(hWinO15),
        confidence: confidenceFromProb(hWinO15),
        plainReason: `${home.name} project to control the match in a game with 2+ goals.`,
      },
    });
  }

  const c95 = prediction.teamProps.corners.overUnder95;
  const c85 = prediction.teamProps.corners.overUnder85;
  if (c95.overProb >= 0.57) {
    cands.push({
      group: 'corners',
      prob: c95.overProb,
      pick: {
        id: 'fb_cor_o95',
        label: 'Over 9.5 Corners',
        category: 'Corners',
        chancePct: toPct(c95.overProb),
        confidence: confidenceFromProb(c95.overProb),
        plainReason: `Strong wing pressure from both teams projects ${round(projTotC, 1)} total corners.`,
      },
    });
  } else if (c85.overProb >= 0.60) {
    cands.push({
      group: 'corners',
      prob: c85.overProb,
      pick: {
        id: 'fb_cor_o85',
        label: 'Over 8.5 Corners',
        category: 'Corners',
        chancePct: toPct(c85.overProb),
        confidence: confidenceFromProb(c85.overProb),
        plainReason: `Both sides combine for ${round(projTotC, 1)} projected corners.`,
      },
    });
  }

  const crd35 = prediction.teamProps.cards.overUnder35;
  if (crd35.overProb >= 0.59) {
    cands.push({
      group: 'cards',
      prob: crd35.overProb,
      pick: {
        id: 'fb_crd_o35',
        label: 'Over 3.5 Cards',
        category: 'Cards & Fouls',
        chancePct: toPct(crd35.overProb),
        confidence: confidenceFromProb(crd35.overProb),
        plainReason: `A competitive matchup with ${round(projTotCards, 1)} projected bookings.`,
      },
    });
  }

  return { prediction, picks: rankPicks(cands, 6) };
}

const US_CONFIG: Record<
  TeamSportType,
  {
    homeAdv: number;
    marginSd: number;
    totalSd: number;
    rPer100: number;
    formMax: number;
    unit: string;
    altSpread: number;
    closeBand: number;
    closeLabel: string;
    otBase: number;
  }
> = {
  nfl: { homeAdv: 2.2, marginSd: 13.5, totalSd: 13.0, rPer100: 3.4, formMax: 2.5, unit: 'Points', altSpread: 3.5, closeBand: 7, closeLabel: 'One-Score Game (≤7 pts)', otBase: 0.08 },
  nba: { homeAdv: 2.8, marginSd: 12.2, totalSd: 17.0, rPer100: 3.8, formMax: 3.5, unit: 'Points', altSpread: 5.5, closeBand: 6, closeLabel: 'Close Finish (≤6 pts)', otBase: 0.06 },
  nhl: { homeAdv: 0.22, marginSd: 2.15, totalSd: 2.2, rPer100: 0.32, formMax: 0.35, unit: 'Goals', altSpread: 1.5, closeBand: 1.5, closeLabel: '1-Goal Game', otBase: 0.23 },
  mlb: { homeAdv: 0.25, marginSd: 3.35, totalSd: 3.5, rPer100: 0.45, formMax: 0.4, unit: 'Runs', altSpread: 1.5, closeBand: 2, closeLabel: 'Close Game (≤2 runs)', otBase: 0.09 },
};

function predictTeamSportMarkets(input: TeamSportMatchInput): {
  prediction: TeamSportMarketPrediction;
  picks: MarketPick[];
} {
  const cfg = US_CONFIG[input.sport];
  const { home, away } = input;
  const hOff = home.splitOffenseAvg !== undefined ? 0.55 * home.splitOffenseAvg + 0.45 * home.offenseAvg : home.offenseAvg;
  const hDef = home.splitDefenseAvg !== undefined ? 0.55 * home.splitDefenseAvg + 0.45 * home.defenseAvg : home.defenseAvg;
  const aOff = away.splitOffenseAvg !== undefined ? 0.55 * away.splitOffenseAvg + 0.45 * away.offenseAvg : away.offenseAvg;
  const aDef = away.splitDefenseAvg !== undefined ? 0.55 * away.splitDefenseAvg + 0.45 * away.defenseAvg : away.defenseAvg;

  let pH = (hOff + aDef) / 2 + cfg.homeAdv / 2;
  let pA = (aOff + hDef) / 2 - cfg.homeAdv / 2;

  const rShift = clamp((((home.strengthRating ?? 1500) - (away.strengthRating ?? 1500)) / 100) * cfg.rPer100, -cfg.marginSd, cfg.marginSd);
  pH += rShift * 0.5;
  pA -= rShift * 0.5;

  const hForm = formScore(home.recentForm);
  const aForm = formScore(away.recentForm);
  pH += hForm.score * cfg.formMax * 0.5;
  pA += aForm.score * cfg.formMax * 0.5;

  const margin = pH - pA;
  const total = pH + pA;
  const homeWin = clamp(1 - normalCdf(0, margin, cfg.marginSd), 0.05, 0.95);
  const line = input.totalLine ?? Math.floor(total) + 0.5;
  const underProb = clamp(normalCdf(line, total, cfg.totalSd), 0.05, 0.95);
  const overUnder: OverUnderMarket = { line, overProb: round(1 - underProb, 4), underProb: round(underProb, 4) };

  const dec = input.sport === 'nfl' || input.sport === 'nba' ? 0 : 1;
  const dispH = round(pH, dec);
  const dispA = round(pA, dec);
  const fav = margin >= 0 ? home.name : away.name;
  const marginAbs = round(Math.abs(margin), 1);

  const altProb = clamp(1 - normalCdf(cfg.altSpread, Math.abs(margin), cfg.marginSd), 0.08, 0.92);
  const teamSd = cfg.totalSd * 0.65;
  const homeTeamLine = Math.floor(pH) + 0.5;
  const awayTeamLine = Math.floor(pA) + 0.5;
  const homeTeamOver = round(clamp(1 - normalCdf(homeTeamLine, pH, teamSd), 0.08, 0.92), 4);
  const awayTeamOver = round(clamp(1 - normalCdf(awayTeamLine, pA, teamSd), 0.08, 0.92), 4);

  const fhH = round(pH * 0.49, dec);
  const fhA = round(pA * 0.49, dec);
  const fhHomeLead = round(clamp(1 - normalCdf(0, margin * 0.5, cfg.marginSd * 0.72), 0.1, 0.9), 4);

  const closeProb = round(
    clamp(
      normalCdf(cfg.closeBand, margin, cfg.marginSd) - normalCdf(-cfg.closeBand, margin, cfg.marginSd),
      0.15,
      0.85
    ),
    4
  );
  const otChance = round(clamp(cfg.otBase * Math.exp(-Math.pow(margin / cfg.marginSd, 2)), 0.03, 0.30), 4);

  const restSummary =
    home.restDays !== undefined && away.restDays !== undefined && Math.abs(home.restDays - away.restDays) >= 2
      ? home.restDays > away.restDays
        ? `${home.name} enter fresher with extra rest days.`
        : `${away.name} enter fresher with extra rest days.`
      : hForm.score > aForm.score + 0.3
      ? `${home.name} carry stronger recent 5-game momentum.`
      : aForm.score > hForm.score + 0.3
      ? `${away.name} carry stronger recent 5-game momentum.`
      : 'Both teams enter on balanced rest and recent form.';

  const prediction: TeamSportMarketPrediction = {
    sport: input.sport,
    matchWinner: { home: round(homeWin, 4), away: round(1 - homeWin, 4) },
    projectedScore: { home: dispH, away: dispA, display: `${dispH} – ${dispA}` },
    projectedSpread: {
      homeMargin: round(margin, 1),
      favorite: fav,
      marginAbs,
      display: `${fav} -${marginAbs}`,
      altLineLabel: `${fav} -${cfg.altSpread}`,
      altLineProb: round(altProb, 4),
    },
    projectedTotal: round(total, 1),
    overUnder,
    teamTotals: {
      homeLine: homeTeamLine,
      homeOverProb: homeTeamOver,
      awayLine: awayTeamLine,
      awayOverProb: awayTeamOver,
    },
    firstHalf: {
      projectedDisplay: `${fhH} – ${fhA}`,
      homeLeadProb: fhHomeLead,
    },
    extras: {
      closeGameLabel: cfg.closeLabel,
      closeGameProb: closeProb,
      overtimeChance: otChance,
    },
    restSummary,
  };

  const winProb = Math.max(homeWin, 1 - homeWin);
  const winName = homeWin >= 0.5 ? home.name : away.name;
  const totSide = overUnder.overProb >= overUnder.underProb ? 'Over' : 'Under';
  const totProb = Math.max(overUnder.overProb, overUnder.underProb);

  const picks: MarketPick[] = [
    {
      id: `${input.sport}_win`,
      label: `${winName} to Win`,
      category: 'Match Result',
      chancePct: toPct(winProb),
      confidence: confidenceFromProb(winProb),
      plainReason: `${winName} project to win ${dispH}–${dispA}. ${restSummary}`,
    },
    {
      id: `${input.sport}_alt_spread`,
      label: `${fav} -${cfg.altSpread}`,
      category: 'Spread',
      chancePct: toPct(altProb),
      confidence: confidenceFromProb(altProb),
      plainReason: `Projected margin is ${fav} by ${marginAbs} ${cfg.unit.toLowerCase()}.`,
    },
    {
      id: `${input.sport}_total`,
      label: `${totSide} ${overUnder.line} ${cfg.unit}`,
      category: 'Total',
      chancePct: toPct(totProb),
      confidence: confidenceFromProb(totProb),
      plainReason: `Projected combined score is ${round(total, 1)} ${cfg.unit.toLowerCase()}.`,
    },
  ];

  return { prediction, picks };
}

function predictTennisMarkets(input: TennisMatchInput): {
  prediction: TennisMarketPrediction;
  picks: MarketPick[];
} {
  const { surface, playerA, playerB } = input;
  const rA = playerA.surfaceRatings?.[surface] ?? playerA.overallRating ?? 1500;
  const rB = playerB.surfaceRatings?.[surface] ?? playerB.overallRating ?? 1500;

  const holdA = clamp((playerA.firstServeWonPct ?? 0.73) * 1.12, 0.62, 0.94);
  const holdB = clamp((playerB.firstServeWonPct ?? 0.73) * 1.12, 0.62, 0.94);

  const baseProb = 1 / (1 + Math.pow(10, -(rA - rB) / 400));
  const pSetA = clamp(baseProb * 0.75 + (0.5 + (holdA - holdB) * 1.4) * 0.25, 0.12, 0.88);
  const pSetB = 1 - pSetA;

  const straightA = pSetA * pSetA;
  const straightB = pSetB * pSetB;
  const threeSets = 2 * pSetA * pSetB;
  const matchA = straightA + threeSets * pSetA;

  const expSets = 2 * (straightA + straightB) + 3 * threeSets;
  const baseGames = surface === 'Grass' ? 10.0 : surface === 'Clay' ? 9.3 : 9.6;
  const projGames = round(expSets * baseGames, 1);
  const line = input.totalGamesLine ?? (surface === 'Clay' ? 21.5 : 22.5);
  const underG = clamp(normalCdf(line, projGames, 4.3), 0.06, 0.94);
  const overUnder: OverUnderMarket = { line, overProb: round(1 - underG, 4), underProb: round(underG, 4) };

  const aceMult = surface === 'Grass' ? 1.3 : surface === 'Clay' ? 0.7 : 1.0;
  const acesA = round((playerA.acesPerMatchAvg ?? 6.8) * aceMult, 1);
  const acesB = round((playerB.acesPerMatchAvg ?? 6.5) * aceMult, 1);
  const dfA = round(playerA.doubleFaultsAvg ?? 2.4, 1);
  const dfB = round(playerB.doubleFaultsAvg ?? 2.5, 1);

  // Tiebreak chance rises strongly with combined serve hold rate
  const avgHold = (holdA + holdB) / 2;
  const tbPerSet = clamp(0.08 + Math.max(0, avgHold - 0.72) * 1.5, 0.08, 0.42);
  const tiebreakChance = round(clamp(1 - Math.pow(1 - tbPerSet, expSets), 0.14, 0.78), 4);

  const favA = matchA >= 0.5;
  const favName = favA ? playerA.name : playerB.name;
  const favProb = favA ? matchA : 1 - matchA;
  const estGameDiff = Math.abs(pSetA - 0.5) * 9.5;
  const hcapLine = estGameDiff >= 3.0 ? -3.5 : -2.5;
  const hcapCover = round(clamp(1 - normalCdf(Math.abs(hcapLine), estGameDiff, 3.4), 0.25, 0.86), 4);

  const prediction: TennisMarketPrediction = {
    surface,
    matchWinner: { playerA: round(matchA, 4), playerB: round(1 - matchA, 4) },
    firstSetWinner: { playerA: round(pSetA, 4), playerB: round(pSetB, 4) },
    setBetting: {
      playerAStraightSets: round(straightA, 4),
      playerBStraightSets: round(straightB, 4),
      straightSetsTotal: round(straightA + straightB, 4),
      goesToThreeSets: round(threeSets, 4),
    },
    gameHandicap: {
      favorite: favName,
      line: hcapLine,
      coverProb: hcapCover,
    },
    tiebreakChance,
    projectedAces: { playerA: acesA, playerB: acesB, total: round(acesA + acesB, 1) },
    projectedDoubleFaults: { playerA: dfA, playerB: dfB, total: round(dfA + dfB, 1) },
    totalGames: { projectedTotal: projGames, overUnder },
    serveHoldProjection: { playerAHoldPct: toPct(holdA), playerBHoldPct: toPct(holdB) },
  };

  const picks: MarketPick[] = [
    {
      id: 'atp_winner',
      label: `${favName} to Win`,
      category: 'Match Result',
      chancePct: toPct(favProb),
      confidence: confidenceFromProb(favProb),
      plainReason: `${favName} hold the stronger ${surface.toLowerCase()}-court edge and serve hold rate.`,
    },
    {
      id: 'atp_set1',
      label: `${favName} to Win 1st Set`,
      category: 'Sets',
      chancePct: toPct(Math.max(pSetA, pSetB)),
      confidence: confidenceFromProb(Math.max(pSetA, pSetB)),
      plainReason: `Stronger opening-set serving profile on ${surface.toLowerCase()} courts.`,
    },
    {
      id: 'atp_sets',
      label: straightA + straightB >= 0.58 ? 'Decided in Straight Sets' : 'Match Goes to 3 Sets',
      category: 'Sets',
      chancePct: toPct(straightA + straightB >= 0.58 ? straightA + straightB : threeSets),
      confidence: confidenceFromProb(Math.max(straightA + straightB, threeSets)),
      plainReason: `Projected match length is ${projGames} total games with a ${toPct(tiebreakChance)}% chance of a tiebreak.`,
    },
  ];

  return { prediction, picks };
}

export function predictAllMarkets(input: MatchPredictionInput): FullMarketPrediction {
  if (input.sport === 'football') {
    const { prediction, picks } = predictFootballMarkets(input);
    return {
      sport: 'football',
      matchLabel: `${input.home.name} vs ${input.away.name}`,
      football: prediction,
      topPicks: picks,
    };
  }
  if (input.sport === 'atp_tennis') {
    const { prediction, picks } = predictTennisMarkets(input);
    return {
      sport: 'atp_tennis',
      matchLabel: `${input.playerA.name} vs ${input.playerB.name}`,
      tennis: prediction,
      topPicks: picks,
    };
  }
  const { prediction, picks } = predictTeamSportMarkets(input);
  return {
    sport: input.sport,
    matchLabel: `${input.home.name} vs ${input.away.name}`,
    teamSport: prediction,
    topPicks: picks,
  };
}
