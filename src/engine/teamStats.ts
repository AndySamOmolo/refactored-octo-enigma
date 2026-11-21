import { SportId, TennisSurface } from '../types';

export type MatchOutcome = 'W' | 'D' | 'L';
export type PredictionPick = 'home' | 'away' | 'draw' | 'over' | 'under';

export interface RichHistoricalMatch {
  id?: string;
  sport: SportId;
  leagueId: string;
  date: string;
  homeTeam: string;
  awayTeam: string;
  homeScore: number;
  awayScore: number;
  oddsHome?: number;
  oddsDraw?: number;
  oddsAway?: number;
  // Football-Data.co.uk extra free columns
  homeHalfTimeGoals?: number | null;
  awayHalfTimeGoals?: number | null;
  homeShots?: number | null;
  awayShots?: number | null;
  homeShotsOnTarget?: number | null;
  awayShotsOnTarget?: number | null;
  homeCorners?: number | null;
  awayCorners?: number | null;
  homeFouls?: number | null;
  awayFouls?: number | null;
  homeYellowCards?: number | null;
  awayYellowCards?: number | null;
  homeRedCards?: number | null;
  awayRedCards?: number | null;
  avgOver25Odds?: number | null;
  avgUnder25Odds?: number | null;
  // nflverse extra free columns
  spreadLine?: number | null;
  totalLine?: number | null;
  homeRestDays?: number | null;
  awayRestDays?: number | null;
  // TennisMyLife extra free columns
  surface?: TennisSurface;
  homeAces?: number | null;
  awayAces?: number | null;
  homeDoubleFaults?: number | null;
  awayDoubleFaults?: number | null;
  homeServicePoints?: number | null;
  awayServicePoints?: number | null;
  homeFirstServeIn?: number | null;
  awayFirstServeIn?: number | null;
  homeFirstServeWon?: number | null;
  awayFirstServeWon?: number | null;
  homeFirstServeWinPct?: number | null;
  awayFirstServeWinPct?: number | null;
}

export interface FootballTeamStats {
  shotsPerGame: number;
  shotsConcededPerGame: number;
  shotsOnTargetPerGame: number;
  shotsOnTargetConcededPerGame: number;
  shotAccuracyPct: number;
  cornersPerGame: number;
  cornersConcededPerGame: number;
  foulsPerGame: number;
  yellowCardsPerGame: number;
  redCardsPerGame: number;
  cardsPerGame: number;
  firstHalfGoalRate: number;
  avgFirstHalfGoalsScored: number;
  avgFirstHalfGoalsConceded: number;
  cleanSheetPct: number;
  bothTeamsScoredPct: number;
  over25Pct: number;
  marketOver25Pct: number | null;
}

export interface NflTeamStats {
  pointsForPerGame: number;
  pointsAgainstPerGame: number;
  pointDiffPerGame: number;
  atsCoverPct: number;
  overTotalPct: number;
  avgRestDays: number;
}

export interface TennisSurfaceRecord {
  matches: number;
  wins: number;
  losses: number;
  winPct: number;
}

export interface TennisPlayerStats {
  hardWinPct: number;
  clayWinPct: number;
  grassWinPct: number;
  surfaceSplits: Record<TennisSurface, TennisSurfaceRecord>;
  avgAcesPerMatch: number;
  avgDoubleFaultsPerMatch: number;
  firstServeWinPct: number;
}

export interface RichTeamProfile {
  team: string;
  sport: SportId;
  gamesPlayed: number;
  wins: number;
  draws: number;
  losses: number;
  winPct: number;
  homeGames: number;
  homeWinPct: number;
  awayGames: number;
  awayWinPct: number;
  avgScored: number;
  avgConceded: number;
  homeAvgScored: number;
  homeAvgConceded: number;
  awayAvgScored: number;
  awayAvgConceded: number;
  avgTotalPoints: number;
  scoringMargin: number;
  recentForm: MatchOutcome[];
  recentMatches: Array<{ result: MatchOutcome; scored: number; conceded: number; isHome: boolean }>;
  currentStreak: number;
  powerScore: number;
  football?: FootballTeamStats;
  nfl?: NflTeamStats;
  tennis?: TennisPlayerStats;
}

export interface HeadToHeadMeeting {
  date: string;
  homeTeam: string;
  awayTeam: string;
  homeScore: number;
  awayScore: number;
  totalScore: number;
  winner: string | 'DRAW';
  surface?: TennisSurface;
}

export interface HeadToHeadSummary {
  sport: SportId;
  homeTeam: string;
  awayTeam: string;
  totalMeetings: number;
  lastFiveMeetings: HeadToHeadMeeting[];
  homeTeamWins: number;
  draws: number;
  awayTeamWins: number;
  avgTotalScore: number;
  avgHomeTeamScored: number;
  avgAwayTeamScored: number;
}

function isFiniteNum(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v);
}

function safeDiv(a: number, b: number, fallback = 0): number {
  return b > 0 ? a / b : fallback;
}

function round(v: number, dec = 2): number {
  if (!Number.isFinite(v)) return 0;
  const f = Math.pow(10, dec);
  return Math.round(v * f) / f;
}

function pct(part: number, total: number): number {
  return round(safeDiv(part, total, 0) * 100, 1);
}

/**
 * Maps internal Elo rating (1200..2150) to an intuitive 0–99 "Team Power" score.
 */
export function eloToPowerScore(elo: number): number {
  if (!Number.isFinite(elo)) return 50;
  const clamped = Math.max(1200, Math.min(2150, elo));
  return Math.max(1, Math.min(99, Math.round(((clamped - 1200) / (2150 - 1200)) * 99)));
}

export function profileKey(sport: SportId, team: string): string {
  return `${sport}::${team}`;
}

export function computeRichTeamProfiles(
  matches: RichHistoricalMatch[],
  eloLookup: Record<string, number> = {}
): Record<string, RichTeamProfile> {
  const sorted = [...matches].sort((a, b) => a.date.localeCompare(b.date));

  interface Acc {
    team: string;
    sport: SportId;
    gp: number;
    w: number;
    d: number;
    l: number;
    hg: number;
    hw: number;
    ag: number;
    aw: number;
    scored: number;
    conceded: number;
    hScored: number;
    hConceded: number;
    aScored: number;
    aConceded: number;
    outcomes: MatchOutcome[];
    recent: Array<{ result: MatchOutcome; scored: number; conceded: number; isHome: boolean }>;
    // Football
    shots: number;
    shotsAg: number;
    shotsN: number;
    sot: number;
    sotAg: number;
    sotN: number;
    cor: number;
    corAg: number;
    corN: number;
    fouls: number;
    foulsN: number;
    yc: number;
    rc: number;
    cardsN: number;
    htScoredG: number;
    htFor: number;
    htAg: number;
    htN: number;
    cs: number;
    btts: number;
    o25: number;
    mktO25Sum: number;
    mktO25N: number;
    // NFL
    atsW: number;
    atsL: number;
    overs: number;
    unders: number;
    restSum: number;
    restN: number;
    // Tennis
    surf: Record<TennisSurface, { m: number; w: number; l: number }>;
    acesSum: number;
    acesN: number;
    dfSum: number;
    dfN: number;
    fsWonSum: number;
    fsInSum: number;
    fsPctSum: number;
    fsPctN: number;
  }

  const accs = new Map<string, Acc>();
  const getOrInit = (sport: SportId, team: string): Acc => {
    const key = profileKey(sport, team);
    let a = accs.get(key);
    if (!a) {
      a = {
        team,
        sport,
        gp: 0,
        w: 0,
        d: 0,
        l: 0,
        hg: 0,
        hw: 0,
        ag: 0,
        aw: 0,
        scored: 0,
        conceded: 0,
        hScored: 0,
        hConceded: 0,
        aScored: 0,
        aConceded: 0,
        outcomes: [],
        recent: [],
        shots: 0,
        shotsAg: 0,
        shotsN: 0,
        sot: 0,
        sotAg: 0,
        sotN: 0,
        cor: 0,
        corAg: 0,
        corN: 0,
        fouls: 0,
        foulsN: 0,
        yc: 0,
        rc: 0,
        cardsN: 0,
        htScoredG: 0,
        htFor: 0,
        htAg: 0,
        htN: 0,
        cs: 0,
        btts: 0,
        o25: 0,
        mktO25Sum: 0,
        mktO25N: 0,
        atsW: 0,
        atsL: 0,
        overs: 0,
        unders: 0,
        restSum: 0,
        restN: 0,
        surf: {
          Hard: { m: 0, w: 0, l: 0 },
          Clay: { m: 0, w: 0, l: 0 },
          Grass: { m: 0, w: 0, l: 0 },
        },
        acesSum: 0,
        acesN: 0,
        dfSum: 0,
        dfN: 0,
        fsWonSum: 0,
        fsInSum: 0,
        fsPctSum: 0,
        fsPctN: 0,
      };
      accs.set(key, a);
    }
    return a;
  };

  const record = (acc: Acc, m: RichHistoricalMatch, isHome: boolean) => {
    const sc = isHome ? m.homeScore : m.awayScore;
    const cn = isHome ? m.awayScore : m.homeScore;
    const res: MatchOutcome = sc > cn ? 'W' : sc < cn ? 'L' : 'D';

    acc.gp++;
    acc.scored += sc;
    acc.conceded += cn;
    acc.outcomes.push(res);
    acc.recent.push({ result: res, scored: sc, conceded: cn, isHome });
    if (res === 'W') acc.w++;
    else if (res === 'L') acc.l++;
    else acc.d++;

    if (isHome) {
      acc.hg++;
      acc.hScored += sc;
      acc.hConceded += cn;
      if (res === 'W') acc.hw++;
    } else {
      acc.ag++;
      acc.aScored += sc;
      acc.aConceded += cn;
      if (res === 'W') acc.aw++;
    }

    if (m.sport === 'football') {
      if (cn === 0) acc.cs++;
      if (sc > 0 && cn > 0) acc.btts++;
      if (sc + cn >= 3) acc.o25++;

      const shF = isHome ? m.homeShots : m.awayShots;
      const shA = isHome ? m.awayShots : m.homeShots;
      if (isFiniteNum(shF) && isFiniteNum(shA)) {
        acc.shots += shF;
        acc.shotsAg += shA;
        acc.shotsN++;
      }
      const stF = isHome ? m.homeShotsOnTarget : m.awayShotsOnTarget;
      const stA = isHome ? m.awayShotsOnTarget : m.homeShotsOnTarget;
      if (isFiniteNum(stF) && isFiniteNum(stA)) {
        acc.sot += stF;
        acc.sotAg += stA;
        acc.sotN++;
      }
      const cF = isHome ? m.homeCorners : m.awayCorners;
      const cA = isHome ? m.awayCorners : m.homeCorners;
      if (isFiniteNum(cF) && isFiniteNum(cA)) {
        acc.cor += cF;
        acc.corAg += cA;
        acc.corN++;
      }
      const fF = isHome ? m.homeFouls : m.awayFouls;
      if (isFiniteNum(fF)) {
        acc.fouls += fF;
        acc.foulsN++;
      }
      const yF = isHome ? m.homeYellowCards : m.awayYellowCards;
      const rF = isHome ? m.homeRedCards : m.awayRedCards;
      if (isFiniteNum(yF) || isFiniteNum(rF)) {
        acc.yc += isFiniteNum(yF) ? yF : 0;
        acc.rc += isFiniteNum(rF) ? rF : 0;
        acc.cardsN++;
      }
      const htF = isHome ? m.homeHalfTimeGoals : m.awayHalfTimeGoals;
      const htA = isHome ? m.awayHalfTimeGoals : m.homeHalfTimeGoals;
      if (isFiniteNum(htF) && isFiniteNum(htA)) {
        if (htF > 0) acc.htScoredG++;
        acc.htFor += htF;
        acc.htAg += htA;
        acc.htN++;
      }
      if (isFiniteNum(m.avgOver25Odds) && m.avgOver25Odds > 1.01) {
        const rawO = 1 / m.avgOver25Odds;
        const fairO =
          isFiniteNum(m.avgUnder25Odds) && m.avgUnder25Odds > 1.01
            ? rawO / (rawO + 1 / m.avgUnder25Odds)
            : rawO;
        acc.mktO25Sum += fairO * 100;
        acc.mktO25N++;
      }
    }

    if (m.sport === 'nfl') {
      if (isFiniteNum(m.spreadLine)) {
        const homeMargin = m.homeScore - m.awayScore - m.spreadLine;
        const teamMargin = isHome ? homeMargin : -homeMargin;
        if (teamMargin > 0) acc.atsW++;
        else if (teamMargin < 0) acc.atsL++;
      }
      if (isFiniteNum(m.totalLine)) {
        if (m.homeScore + m.awayScore > m.totalLine) acc.overs++;
        else if (m.homeScore + m.awayScore < m.totalLine) acc.unders++;
      }
      const rest = isHome ? m.homeRestDays : m.awayRestDays;
      if (isFiniteNum(rest) && rest >= 0) {
        acc.restSum += rest;
        acc.restN++;
      }
    }

    if (m.sport === 'tennis') {
      const s: TennisSurface = m.surface ?? 'Hard';
      acc.surf[s].m++;
      if (res === 'W') acc.surf[s].w++;
      else acc.surf[s].l++;

      const aces = isHome ? m.homeAces : m.awayAces;
      if (isFiniteNum(aces)) {
        acc.acesSum += aces;
        acc.acesN++;
      }
      const dfs = isHome ? m.homeDoubleFaults : m.awayDoubleFaults;
      if (isFiniteNum(dfs)) {
        acc.dfSum += dfs;
        acc.dfN++;
      }
      const fIn = isHome ? m.homeFirstServeIn : m.awayFirstServeIn;
      const fWon = isHome ? m.homeFirstServeWon : m.awayFirstServeWon;
      const fPct = isHome ? m.homeFirstServeWinPct : m.awayFirstServeWinPct;
      if (isFiniteNum(fIn) && fIn > 0 && isFiniteNum(fWon)) {
        acc.fsInSum += fIn;
        acc.fsWonSum += Math.min(fWon, fIn);
      } else if (isFiniteNum(fPct)) {
        acc.fsPctSum += fPct <= 1 ? fPct * 100 : fPct;
        acc.fsPctN++;
      }
    }
  };

  for (const m of sorted) {
    record(getOrInit(m.sport, m.homeTeam), m, true);
    record(getOrInit(m.sport, m.awayTeam), m, false);
  }

  const out: Record<string, RichTeamProfile> = {};
  for (const [key, a] of accs.entries()) {
    const avgScored = round(safeDiv(a.scored, a.gp), 2);
    const avgConceded = round(safeDiv(a.conceded, a.gp), 2);
    const recentForm = a.outcomes.slice(-5);
    let streak = 0;
    if (a.outcomes.length > 0) {
      const last = a.outcomes[a.outcomes.length - 1];
      if (last !== 'D') {
        for (let i = a.outcomes.length - 1; i >= 0 && a.outcomes[i] === last; i--) {
          streak += last === 'W' ? 1 : -1;
        }
      }
    }

    const profile: RichTeamProfile = {
      team: a.team,
      sport: a.sport,
      gamesPlayed: a.gp,
      wins: a.w,
      draws: a.d,
      losses: a.l,
      winPct: pct(a.w, a.gp),
      homeGames: a.hg,
      homeWinPct: pct(a.hw, a.hg),
      awayGames: a.ag,
      awayWinPct: pct(a.aw, a.ag),
      avgScored,
      avgConceded,
      homeAvgScored: round(safeDiv(a.hScored, a.hg, avgScored), 2),
      homeAvgConceded: round(safeDiv(a.hConceded, a.hg, avgConceded), 2),
      awayAvgScored: round(safeDiv(a.aScored, a.ag, avgScored), 2),
      awayAvgConceded: round(safeDiv(a.aConceded, a.ag, avgConceded), 2),
      avgTotalPoints: round(avgScored + avgConceded, 2),
      scoringMargin: round(avgScored - avgConceded, 2),
      recentForm,
      recentMatches: a.recent.slice(-5).reverse(),
      currentStreak: streak,
      powerScore: eloToPowerScore(eloLookup[key] ?? 1500),
    };

    if (a.sport === 'football') {
      const yc = round(safeDiv(a.yc, a.cardsN, 1.8), 2);
      const rc = round(safeDiv(a.rc, a.cardsN, 0.08), 2);
      profile.football = {
        shotsPerGame: round(safeDiv(a.shots, a.shotsN, 12.5), 1),
        shotsConcededPerGame: round(safeDiv(a.shotsAg, a.shotsN, 11.5), 1),
        shotsOnTargetPerGame: round(safeDiv(a.sot, a.sotN, 4.4), 1),
        shotsOnTargetConcededPerGame: round(safeDiv(a.sotAg, a.sotN, 4.1), 1),
        shotAccuracyPct: pct(a.sot, Math.max(1, a.shots)),
        cornersPerGame: round(safeDiv(a.cor, a.corN, 5.1), 1),
        cornersConcededPerGame: round(safeDiv(a.corAg, a.corN, 4.9), 1),
        foulsPerGame: round(safeDiv(a.fouls, a.foulsN, 11.2), 1),
        yellowCardsPerGame: yc,
        redCardsPerGame: rc,
        cardsPerGame: round(yc + rc, 1),
        firstHalfGoalRate: pct(a.htScoredG, a.htN || 1),
        avgFirstHalfGoalsScored: round(safeDiv(a.htFor, a.htN, avgScored * 0.44), 2),
        avgFirstHalfGoalsConceded: round(safeDiv(a.htAg, a.htN, avgConceded * 0.44), 2),
        cleanSheetPct: pct(a.cs, a.gp),
        bothTeamsScoredPct: pct(a.btts, a.gp),
        over25Pct: pct(a.o25, a.gp),
        marketOver25Pct: a.mktO25N > 0 ? round(a.mktO25Sum / a.mktO25N, 1) : null,
      };
    }

    if (a.sport === 'nfl') {
      profile.nfl = {
        pointsForPerGame: avgScored,
        pointsAgainstPerGame: avgConceded,
        pointDiffPerGame: round(avgScored - avgConceded, 1),
        atsCoverPct: pct(a.atsW, a.atsW + a.atsL),
        overTotalPct: pct(a.overs, a.overs + a.unders),
        avgRestDays: round(safeDiv(a.restSum, a.restN, 7), 1),
      };
    }

    if (a.sport === 'tennis') {
      const toRec = (s: { m: number; w: number; l: number }): TennisSurfaceRecord => ({
        matches: s.m,
        wins: s.w,
        losses: s.l,
        winPct: pct(s.w, s.m),
      });
      const hard = toRec(a.surf.Hard);
      const clay = toRec(a.surf.Clay);
      const grass = toRec(a.surf.Grass);
      profile.tennis = {
        hardWinPct: hard.winPct,
        clayWinPct: clay.winPct,
        grassWinPct: grass.winPct,
        surfaceSplits: { Hard: hard, Clay: clay, Grass: grass },
        avgAcesPerMatch: round(safeDiv(a.acesSum, a.acesN, 6.8), 1),
        avgDoubleFaultsPerMatch: round(safeDiv(a.dfSum, a.dfN, 2.6), 1),
        firstServeWinPct:
          a.fsInSum > 0
            ? pct(a.fsWonSum, a.fsInSum)
            : round(safeDiv(a.fsPctSum, a.fsPctN, 73.5), 1),
      };
    }

    out[key] = profile;
  }

  return out;
}

export function computeHeadToHead(
  matches: RichHistoricalMatch[],
  sport: SportId,
  homeTeam: string,
  awayTeam: string
): HeadToHeadSummary {
  const relevant = matches
    .filter(
      (m) =>
        m.sport === sport &&
        ((m.homeTeam === homeTeam && m.awayTeam === awayTeam) ||
          (m.homeTeam === awayTeam && m.awayTeam === homeTeam))
    )
    .sort((a, b) => b.date.localeCompare(a.date));

  let homeWins = 0;
  let awayWins = 0;
  let draws = 0;
  let totalScoreSum = 0;
  let homeScoreSum = 0;
  let awayScoreSum = 0;

  const meetings: HeadToHeadMeeting[] = relevant.map((m) => {
    const sameOrder = m.homeTeam === homeTeam;
    const hS = sameOrder ? m.homeScore : m.awayScore;
    const aS = sameOrder ? m.awayScore : m.homeScore;
    totalScoreSum += hS + aS;
    homeScoreSum += hS;
    awayScoreSum += aS;

    let winner: string | 'DRAW' = 'DRAW';
    if (hS > aS) {
      homeWins++;
      winner = homeTeam;
    } else if (aS > hS) {
      awayWins++;
      winner = awayTeam;
    } else {
      draws++;
    }
    return {
      date: m.date,
      homeTeam: m.homeTeam,
      awayTeam: m.awayTeam,
      homeScore: m.homeScore,
      awayScore: m.awayScore,
      totalScore: m.homeScore + m.awayScore,
      winner,
      surface: m.surface,
    };
  });

  return {
    sport,
    homeTeam,
    awayTeam,
    totalMeetings: meetings.length,
    lastFiveMeetings: meetings.slice(0, 5),
    homeTeamWins: homeWins,
    draws,
    awayTeamWins: awayWins,
    avgTotalScore: round(safeDiv(totalScoreSum, meetings.length), 1),
    avgHomeTeamScored: round(safeDiv(homeScoreSum, meetings.length), 1),
    avgAwayTeamScored: round(safeDiv(awayScoreSum, meetings.length), 1),
  };
}

export function generatePlainEnglishInsights(
  homeProfile: RichTeamProfile | undefined,
  awayProfile: RichTeamProfile | undefined,
  h2h: HeadToHeadSummary | undefined,
  sport: SportId,
  favoredSide: 'home' | 'away' | 'draw',
  surface?: TennisSurface
): string[] {
  const bullets: string[] = [];
  const favored = favoredSide === 'away' ? awayProfile : homeProfile;
  const underdog = favoredSide === 'away' ? homeProfile : awayProfile;

  if (homeProfile && awayProfile && favoredSide !== 'draw') {
    const gap = Math.abs(homeProfile.powerScore - awayProfile.powerScore);
    if (gap >= 8 && favored && underdog) {
      bullets.push(
        `${favored.team} holds a clear overall strength edge (${favored.powerScore} vs ${underdog.powerScore} Team Power).`
      );
    }
  }

  if (sport !== 'tennis' && favoredSide === 'home' && homeProfile && homeProfile.homeGames >= 3 && homeProfile.homeWinPct >= 55) {
    bullets.push(
      `${homeProfile.team} is strong at home, winning ${Math.round(homeProfile.homeWinPct)}% of home games.`
    );
  } else if (sport !== 'tennis' && favoredSide === 'away' && awayProfile && awayProfile.awayGames >= 3 && awayProfile.awayWinPct >= 50) {
    bullets.push(
      `${awayProfile.team} travels well, winning ${Math.round(awayProfile.awayWinPct)}% of road games.`
    );
  }

  if (favored && favored.currentStreak >= 3) {
    bullets.push(`${favored.team} enters hot on a ${favored.currentStreak}-game winning streak.`);
  }

  if (sport === 'football' && homeProfile?.football && awayProfile?.football) {
    if (favored?.football && favored.football.shotsOnTargetPerGame >= 4.8) {
      bullets.push(
        `${favored.team} creates steady danger with ${favored.football.shotsOnTargetPerGame.toFixed(1)} shots on target and ${favored.football.cornersPerGame.toFixed(1)} corners per match.`
      );
    }
    if (homeProfile.football.over25Pct >= 58 && awayProfile.football.over25Pct >= 58) {
      bullets.push(
        `Both clubs play open matches—over 2.5 goals landed in ${Math.round((homeProfile.football.over25Pct + awayProfile.football.over25Pct) / 2)}% of their games.`
      );
    }
  } else if (sport === 'nfl' && favored?.nfl) {
    bullets.push(
      `${favored.team} averages ${favored.nfl.pointsForPerGame.toFixed(1)} points per game and covers the spread ${Math.round(favored.nfl.atsCoverPct)}% of the time.`
    );
  } else if (sport === 'tennis' && favored?.tennis) {
    const surf = surface ?? 'Hard';
    const split = favored.tennis.surfaceSplits[surf];
    bullets.push(
      `${favored.team} wins ${Math.round(favored.tennis.firstServeWinPct)}% of first-serve points and averages ${favored.tennis.avgAcesPerMatch.toFixed(1)} aces (${Math.round(split.winPct || favored.winPct)}% win rate on ${surf.toLowerCase()} courts).`
    );
  }

  if (h2h && h2h.totalMeetings >= 2) {
    bullets.push(
      `Recent head-to-head: ${h2h.homeTeam} ${h2h.homeTeamWins}W – ${h2h.draws > 0 ? `${h2h.draws}D – ` : ''}${h2h.awayTeamWins}W ${h2h.awayTeam} (avg ${h2h.avgTotalScore.toFixed(1)} total).`
    );
  }

  if (bullets.length < 3 && homeProfile && awayProfile) {
    bullets.push(
      `Scoring pace: ${homeProfile.team} averages ${homeProfile.avgScored.toFixed(1)} per game vs ${awayProfile.team}'s ${awayProfile.avgScored.toFixed(1)}.`
    );
  }

  return bullets.slice(0, 4);
}
