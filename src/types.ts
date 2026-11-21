export type SportId = 'football' | 'nfl' | 'nba' | 'nhl' | 'mlb' | 'tennis';

export type MarketType = '1X2' | 'H2H' | 'OVER_25' | 'BTTS';

export type TennisSurface = 'Hard' | 'Clay' | 'Grass';

export interface HistoricalMatch {
  id: string;
  sport: SportId;
  leagueId: string;
  date: string; // ISO YYYY-MM-DD
  homeTeam: string;
  awayTeam: string;
  homeScore: number;
  awayScore: number;
  surface?: TennisSurface;
  /** Optional closing market decimal odds for backtest calibration & CLV */
  oddsHome?: number;
  oddsDraw?: number;
  oddsAway?: number;
}

export interface DixonColesParams {
  leagueId: string;
  attack: Record<string, number>;
  defense: Record<string, number>;
  homeAdvantage: number;
  rho: number;
  avgGoals: number;
  fittedMatches: number;
  updatedAt: string;
}

export interface TeamRating {
  team: string;
  sport: SportId;
  leagueId: string;
  elo: number;
  clubElo?: number;
  attack?: number;
  defense?: number;
  surfaceElo?: Partial<Record<TennisSurface, number>>;
  recentForm: ('W' | 'D' | 'L')[];
  matchesPlayed: number;
}

export interface ScorelineProb {
  homeGoals: number;
  awayGoals: number;
  prob: number;
}

export interface MatchPrediction {
  probHome: number;
  probDraw: number; // 0 for 2-way sports (NFL/NBA/NHL/MLB/Tennis)
  probAway: number;
  expectedHomeGoals?: number;
  expectedAwayGoals?: number;
  probOver25?: number;
  probBtts?: number;
  topScorelines?: ScorelineProb[];
  modelSource: 'dixon-coles+elo' | 'elo-mov' | 'surface-elo';
  confidenceScore: number; // 0..100 based on sample size & rating convergence
}

export interface ValueSignal {
  side: 'HOME' | 'DRAW' | 'AWAY';
  label: string;
  modelProb: number;
  impliedProb: number;
  fairMarketProb: number;
  decimalOdds: number;
  expectedValue: number; // e.g. +0.045 = +4.5% EV
  kellyFraction: number; // Recommended fractional Kelly stake (capped at 5%)
  isValue: boolean;
}

export interface UpcomingFixture {
  id: string;
  sport: SportId;
  leagueId: string;
  leagueName: string;
  kickoffIso: string;
  homeTeam: string;
  awayTeam: string;
  surface?: TennisSurface;
  marketOdds?: {
    home: number;
    draw?: number;
    away: number;
    source: string;
  };
  prediction: MatchPrediction;
  bestSignal?: ValueSignal;
}

export interface CalibrationBin {
  binLabel: string;
  predictedMean: number;
  actualRate: number;
  count: number;
}

export interface LeagueBacktestSummary {
  sport: SportId;
  leagueId: string;
  leagueName: string;
  sampleSize: number;
  modelBrier: number;
  baselineBrier: number;
  modelLogLoss: number;
  baselineLogLoss: number;
  simulatedRoiPct: number;
  calibrationBins: CalibrationBin[];
}

export interface FeedSyncStatus {
  feedId: string;
  label: string;
  sport: SportId;
  url: string;
  status: 'ok' | 'cached' | 'failed';
  recordsParsed: number;
  lastAttemptIso: string;
  errorMessage?: string;
}

export interface PaperBet {
  id: string;
  createdAt: string;
  fixtureId?: string;
  sport: SportId;
  leagueName: string;
  matchup: string;
  selection: string;
  decimalOdds: number;
  modelProb: number;
  expectedValue: number;
  stakeAmount: number;
  status: 'PENDING' | 'WON' | 'LOST' | 'VOID';
  closingOdds?: number;
}

export interface UserSettings {
  bankroll: number;
  kellyMultiplier: number; // default 0.25 (Quarter-Kelly)
  maxStakePct: number; // default 0.05 (5% hard cap)
  minEvThreshold: number; // default 0.03 (+3.0% EV)
  autoSyncWeekly: boolean; // default true (7-day interval)
  syncIntervalDays: number; // default 7
  enabledSports: SportId[];
}

export interface SyncedModelState {
  schemaVersion: number;
  lastSyncIso: string;
  lastSyncEpoch: number;
  isSeedData: boolean;
  totalHistoricalMatches: number;
  ratings: Record<string, TeamRating>;
  dixonColes: Record<string, DixonColesParams>;
  fixtures: UpcomingFixture[];
  backtests: LeagueBacktestSummary[];
  feedStatuses: FeedSyncStatus[];
}
