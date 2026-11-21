import {
  DixonColesParams,
  FeedSyncStatus,
  HistoricalMatch,
  LeagueBacktestSummary,
  SportId,
  SyncedModelState,
  TeamRating,
  TennisSurface,
  UpcomingFixture,
  UserSettings,
} from '../types';
import {
  FOOTBALL_LEAGUES,
  PUBLIC_DATA_FEEDS,
  SPORT_META,
} from '../data/sources';
import {
  buildSeedHistoricalMatches,
  SEED_CLUB_ELO,
  SEED_UPCOMING_FIXTURES,
} from '../data/seedData';
import {
  parseClubEloCsv,
  parseEspnScoreboardJson,
  parseFootballDataCsv,
  parseNflverseGamesCsv,
  parseTmlTennisCsv,
} from './csvParser';
import { fitDixonColesLeague, predictDixonColesMatch } from './dixonColes';
import {
  blendFootballPredictions,
  fitMultiSportElo,
  predictEloMatch,
  ratingKey,
} from './eloEngine';
import { evaluateMatchSignals } from './kelly';
import { runWalkForwardBacktest } from './backtester';

export const DEFAULT_USER_SETTINGS: UserSettings = {
  bankroll: 1000,
  kellyMultiplier: 0.25,
  maxStakePct: 0.05,
  minEvThreshold: 0.03,
  autoSyncWeekly: true,
  syncIntervalDays: 7,
  enabledSports: ['football', 'nfl', 'nba', 'nhl', 'mlb', 'tennis'],
};

/**
 * Returns true if the local model pack is older than the configured weekly interval
 * (default 7 days) and auto-sync is enabled.
 */
export function shouldAutoSyncWeekly(
  state: SyncedModelState | null,
  settings: UserSettings,
  nowEpoch: number = Date.now()
): boolean {
  if (!settings.autoSyncWeekly) return false;
  if (!state || state.isSeedData) return true;
  const intervalMs = Math.max(1, settings.syncIntervalDays) * 86400000;
  return nowEpoch - state.lastSyncEpoch >= intervalMs;
}

export function getDaysSinceSync(
  state: SyncedModelState,
  nowEpoch: number = Date.now()
): number {
  return Math.max(0, Math.floor((nowEpoch - state.lastSyncEpoch) / 86400000));
}

function getLeagueDisplayName(sport: SportId, leagueId: string): string {
  const fb = FOOTBALL_LEAGUES.find((l) => l.leagueId === leagueId);
  if (fb) return fb.name;
  return SPORT_META[sport].label;
}

/**
 * Pure on-device modeling orchestrator: fits Dixon-Coles for every football league,
 * Multi-Sport Elo + ClubElo + Surface Tennis Elo across all sports, runs walk-forward
 * backtests, and scores all upcoming fixtures with calibrated probabilities & EV signals.
 */
export function buildModelState(params: {
  historicalMatches: HistoricalMatch[];
  clubEloMap: Record<string, number>;
  rawUpcoming: Array<{
    id: string;
    sport: SportId;
    leagueId: string;
    leagueName: string;
    kickoffIso: string;
    homeTeam: string;
    awayTeam: string;
    surface?: TennisSurface;
    marketOdds?: { home: number; draw?: number; away: number; source: string };
  }>;
  feedStatuses: FeedSyncStatus[];
  settings: UserSettings;
  isSeedData: boolean;
  now?: Date;
}): SyncedModelState {
  const now = params.now ?? new Date();
  const todayIso = now.toISOString().slice(0, 10);

  // 1. Fit Multi-Sport Elo + ClubElo anchor + Surface Tennis Elo
  const ratings: Record<string, TeamRating> = fitMultiSportElo(
    params.historicalMatches,
    params.clubEloMap
  );

  // 2. Group matches by league for Dixon-Coles & Walk-Forward Backtests
  const byLeague = new Map<string, { sport: SportId; matches: HistoricalMatch[] }>();
  for (const m of params.historicalMatches) {
    const existing = byLeague.get(m.leagueId);
    if (existing) {
      existing.matches.push(m);
    } else {
      byLeague.set(m.leagueId, { sport: m.sport, matches: [m] });
    }
  }

  const dixonColes: Record<string, DixonColesParams> = {};
  const backtests: LeagueBacktestSummary[] = [];

  for (const [leagueId, bucket] of byLeague.entries()) {
    if (bucket.sport === 'football' && bucket.matches.length >= 10) {
      const dcParams = fitDixonColesLeague(leagueId, bucket.matches, todayIso);
      dixonColes[leagueId] = dcParams;

      // Attach attack/defense strengths onto TeamRating records for UI inspection
      for (const team of Object.keys(dcParams.attack)) {
        const key = ratingKey('football', team);
        if (ratings[key]) {
          ratings[key].attack = dcParams.attack[team];
          ratings[key].defense = dcParams.defense[team];
        }
      }
    }

    if (bucket.matches.length >= 12) {
      backtests.push(
        runWalkForwardBacktest(
          bucket.sport,
          leagueId,
          getLeagueDisplayName(bucket.sport, leagueId),
          bucket.matches
        )
      );
    }
  }

  // 3. Score upcoming fixtures & attach ValueSignals
  const fixtures: UpcomingFixture[] = params.rawUpcoming.map((fx) => {
    const rH = ratings[ratingKey(fx.sport, fx.homeTeam)];
    const rA = ratings[ratingKey(fx.sport, fx.awayTeam)];
    const eloPred = predictEloMatch(fx.sport, rH, rA, fx.surface);

    let prediction = eloPred;
    if (fx.sport === 'football' && dixonColes[fx.leagueId]) {
      const dcPred = predictDixonColesMatch(
        dixonColes[fx.leagueId],
        fx.homeTeam,
        fx.awayTeam
      );
      prediction = blendFootballPredictions(dcPred, eloPred, 0.7);
    }

    const evalRes = fx.marketOdds
      ? evaluateMatchSignals(prediction, fx.homeTeam, fx.awayTeam, fx.marketOdds, {
          kellyMultiplier: params.settings.kellyMultiplier,
          maxStakePct: params.settings.maxStakePct,
          minEvThreshold: params.settings.minEvThreshold,
        })
      : { signals: [], bestSignal: undefined };

    return {
      ...fx,
      prediction,
      bestSignal: evalRes.bestSignal,
    };
  });

  // Sort fixtures: highest EV value signals first, then chronological kickoff
  fixtures.sort((a, b) => {
    const evA = a.bestSignal?.expectedValue ?? -999;
    const evB = b.bestSignal?.expectedValue ?? -999;
    if (evA !== evB) return evB - evA;
    return a.kickoffIso.localeCompare(b.kickoffIso);
  });

  backtests.sort((a, b) => a.modelBrier - b.modelBrier);

  return {
    schemaVersion: 1,
    lastSyncIso: now.toISOString(),
    lastSyncEpoch: now.getTime(),
    isSeedData: params.isSeedData,
    totalHistoricalMatches: params.historicalMatches.length,
    ratings,
    dixonColes,
    fixtures,
    backtests,
    feedStatuses: params.feedStatuses,
  };
}

/**
 * Builds the initial offline seed model state immediately with zero network requests.
 */
export function buildInitialSeedState(
  settings: UserSettings = DEFAULT_USER_SETTINGS,
  now: Date = new Date()
): SyncedModelState {
  const historicalMatches = buildSeedHistoricalMatches(now);
  const rawUpcoming = SEED_UPCOMING_FIXTURES.map((s) => ({
    id: s.id,
    sport: s.sport,
    leagueId: s.leagueId,
    leagueName: s.leagueName,
    kickoffIso: new Date(now.getTime() + s.daysFromNow * 86400000).toISOString(),
    homeTeam: s.homeTeam,
    awayTeam: s.awayTeam,
    surface: s.surface,
    marketOdds: s.marketOdds,
  }));

  const feedStatuses: FeedSyncStatus[] = PUBLIC_DATA_FEEDS.map((f) => ({
    feedId: f.id,
    label: f.label,
    sport: f.sport,
    url: f.buildUrl(now),
    status: 'cached',
    recordsParsed: 0,
    lastAttemptIso: now.toISOString(),
  }));

  return buildModelState({
    historicalMatches,
    clubEloMap: SEED_CLUB_ELO,
    rawUpcoming,
    feedStatuses,
    settings,
    isSeedData: true,
    now,
  });
}

async function fetchTextWithTimeout(
  url: string,
  timeoutMs: number = 12000,
  fetchImpl: typeof fetch = fetch
): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetchImpl(url, {
      signal: controller.signal,
      headers: { Accept: 'text/csv, application/json, text/plain, */*' },
    });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Executes a full on-device weekly sync directly against all free keyless public feeds,
 * refits all statistical models locally, and falls back gracefully if any feed is unreachable.
 */
export async function runOnDeviceWeeklySync(
  settings: UserSettings,
  onProgress?: (completed: number, total: number, currentLabel: string) => void,
  fetchImpl: typeof fetch = fetch,
  now: Date = new Date()
): Promise<SyncedModelState> {
  const activeFeeds = PUBLIC_DATA_FEEDS.filter((f) =>
    settings.enabledSports.includes(f.sport)
  );

  // Start with seed history as baseline so partially offline syncs never leave a sport blank
  const seedMatches = buildSeedHistoricalMatches(now);
  const fetchedMatches: HistoricalMatch[] = [];
  const leaguesWithLiveHistory = new Set<string>();
  const clubEloMap: Record<string, number> = { ...SEED_CLUB_ELO };
  const liveUpcoming: Array<{
    id: string;
    sport: SportId;
    leagueId: string;
    leagueName: string;
    kickoffIso: string;
    homeTeam: string;
    awayTeam: string;
    surface?: TennisSurface;
    marketOdds?: { home: number; draw?: number; away: number; source: string };
  }> = [];

  const feedStatuses: FeedSyncStatus[] = [];
  let succeededCount = 0;

  for (let i = 0; i < activeFeeds.length; i++) {
    const feed = activeFeeds[i];
    const url = feed.buildUrl(now);
    onProgress?.(i, activeFeeds.length, feed.label);

    try {
      const raw = await fetchTextWithTimeout(url, 12000, fetchImpl);
      let count = 0;

      if (feed.kind === 'football-data-csv') {
        const parsed = parseFootballDataCsv(raw, feed.leagueId);
        if (parsed.length > 0) {
          fetchedMatches.push(...parsed);
          leaguesWithLiveHistory.add(feed.leagueId);
          count = parsed.length;
        }
      } else if (feed.kind === 'clubelo-csv') {
        const parsedElo = parseClubEloCsv(raw);
        Object.assign(clubEloMap, parsedElo);
        count = Object.keys(parsedElo).length;
      } else if (feed.kind === 'nflverse-csv') {
        const parsed = parseNflverseGamesCsv(raw, now.getUTCFullYear() - 2);
        if (parsed.length > 0) {
          fetchedMatches.push(...parsed);
          leaguesWithLiveHistory.add('NFL');
          count = parsed.length;
        }
      } else if (feed.kind === 'tml-tennis-csv') {
        const parsed = parseTmlTennisCsv(raw);
        if (parsed.length > 0) {
          fetchedMatches.push(...parsed);
          leaguesWithLiveHistory.add('ATP');
          count = parsed.length;
        }
      } else if (feed.kind === 'espn-scoreboard') {
        const json = JSON.parse(raw);
        const events = parseEspnScoreboardJson(json, feed.sport);
        for (const ev of events) {
          if (ev.completed && ev.homeScore !== undefined && ev.awayScore !== undefined) {
            fetchedMatches.push({
              id: ev.id,
              sport: feed.sport,
              leagueId: feed.leagueId,
              date: ev.kickoffIso.slice(0, 10),
              homeTeam: ev.homeTeam,
              awayTeam: ev.awayTeam,
              homeScore: ev.homeScore,
              awayScore: ev.awayScore,
              oddsHome: ev.oddsHome,
              oddsDraw: ev.oddsDraw,
              oddsAway: ev.oddsAway,
            });
          } else {
            liveUpcoming.push({
              id: ev.id,
              sport: feed.sport,
              leagueId: feed.leagueId,
              leagueName: feed.leagueName,
              kickoffIso: ev.kickoffIso,
              homeTeam: ev.homeTeam,
              awayTeam: ev.awayTeam,
              surface: feed.sport === 'tennis' ? 'Hard' : undefined,
              marketOdds:
                ev.oddsHome && ev.oddsAway
                  ? {
                      home: ev.oddsHome,
                      draw: ev.oddsDraw,
                      away: ev.oddsAway,
                      source: 'ESPN Public Feed',
                    }
                  : undefined,
            });
          }
        }
        count = events.length;
      }

      succeededCount += 1;
      feedStatuses.push({
        feedId: feed.id,
        label: feed.label,
        sport: feed.sport,
        url,
        status: 'ok',
        recordsParsed: count,
        lastAttemptIso: now.toISOString(),
      });
    } catch (err) {
      feedStatuses.push({
        feedId: feed.id,
        label: feed.label,
        sport: feed.sport,
        url,
        status: 'failed',
        recordsParsed: 0,
        lastAttemptIso: now.toISOString(),
        errorMessage: err instanceof Error ? err.message : 'Network unavailable',
      });
    }
  }

  onProgress?.(activeFeeds.length, activeFeeds.length, 'Fitting models locally...');

  // Combine live historical matches with seed fallback for any league that failed to fetch
  const mergedHistory: HistoricalMatch[] = [
    ...fetchedMatches,
    ...seedMatches.filter((m) => !leaguesWithLiveHistory.has(m.leagueId)),
  ];

  // Use live upcoming fixtures if available; otherwise keep seed slate so UI is never empty
  const upcomingToUse =
    liveUpcoming.length > 0
      ? liveUpcoming
      : SEED_UPCOMING_FIXTURES.filter((s) =>
          settings.enabledSports.includes(s.sport)
        ).map((s) => ({
          id: s.id,
          sport: s.sport,
          leagueId: s.leagueId,
          leagueName: s.leagueName,
          kickoffIso: new Date(now.getTime() + s.daysFromNow * 86400000).toISOString(),
          homeTeam: s.homeTeam,
          awayTeam: s.awayTeam,
          surface: s.surface,
          marketOdds: s.marketOdds,
        }));

  return buildModelState({
    historicalMatches: mergedHistory,
    clubEloMap,
    rawUpcoming: upcomingToUse,
    feedStatuses,
    settings,
    isSeedData: succeededCount === 0,
    now,
  });
}
