import type { FullMarketPrediction } from './engine/marketPredictor';
import type { HeadToHeadSummary, RichTeamProfile } from './engine/teamStats';

export type SportId = 'football' | 'nfl' | 'nba' | 'nhl' | 'mlb' | 'tennis';
export type MarketType = '1X2' | 'H2H' | 'OVER_25' | 'BTTS';
export type TennisSurface = 'Hard' | 'Clay' | 'Grass';

export interface HistoricalMatch {
  id: string;
  sport: SportId;
  leagueId: string;
  date: string;
  homeTeam: string;
  awayTeam: string;
  homeScore: number;
  awayScore: number;
  surface?: TennisSurface;
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
  probDraw: number;
  probAway: number;
  expectedHomeGoals?: number;
  expectedAwayGoals?: number;
  probOver25?: number;
  probBtts?: number;
  topScorelines?: ScorelineProb[];
  modelSource: 'dixon-coles+elo' | 'elo-mov' | 'surface-elo';
  confidenceScore: number;
}

export interface ValueSignal {
  side: 'HOME' | 'DRAW' | 'AWAY';
  label: string;
  modelProb: number;
  impliedProb: number;
  fairMarketProb: number;
  decimalOdds: number;
  expectedValue: number;
  kellyFraction: number;
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
  /** Comprehensive multi-market predictions (Double Chance, Over/Under, 1st Half, Corners, Cards, Spreads, Sets) */
  markets: FullMarketPrediction;
  /** Plain-English 3–4 bullet reasons explaining why we lean toward the pick */
  plainInsights: string[];
  /** Head-to-head summary between these two teams/players */
  headToHead?: HeadToHeadSummary;
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
  kellyMultiplier: number; // 0.15 Safe, 0.25 Balanced, 0.40 Bold
  maxStakePct: number;
  minEvThreshold: number;
  autoSyncWeekly: boolean;
  syncIntervalDays: number;
  enabledSports: SportId[];
}

export interface SyncedModelState {
  schemaVersion: number;
  lastSyncIso: string;
  lastSyncEpoch: number;
  isSeedData: boolean;
  totalHistoricalMatches: number;
  ratings: Record<string, TeamRating>;
  teamProfiles: Record<string, RichTeamProfile>;
  dixonColes: Record<string, DixonColesParams>;
  fixtures: UpcomingFixture[];
  backtests: LeagueBacktestSummary[];
  feedStatuses: FeedSyncStatus[];
}
