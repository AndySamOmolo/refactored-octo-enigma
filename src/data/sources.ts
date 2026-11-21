import { SportId } from '../types';

export interface PublicDataFeed {
  id: string;
  label: string;
  sport: SportId;
  leagueId: string;
  leagueName: string;
  kind: 'football-data-csv' | 'clubelo-csv' | 'nflverse-csv' | 'tml-tennis-csv' | 'espn-scoreboard';
  buildUrl: (now?: Date) => string;
}

/**
 * Computes Football-Data.co.uk 4-digit season code (e.g., Aug 2026 -> "2627").
 */
export function getFootballSeasonCode(now: Date = new Date()): string {
  const year = now.getUTCFullYear();
  const month = now.getUTCMonth() + 1; // 1..12
  const startYear = month >= 7 ? year : year - 1;
  const endYear = startYear + 1;
  return `${String(startYear).slice(-2)}${String(endYear).slice(-2)}`;
}

export function formatIsoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function formatEspnDateRange(now: Date = new Date(), daysAhead = 7): string {
  const start = now.toISOString().slice(0, 10).replace(/-/g, '');
  const endDate = new Date(now.getTime() + daysAhead * 86400000);
  const end = endDate.toISOString().slice(0, 10).replace(/-/g, '');
  return `${start}-${end}`;
}

export const FOOTBALL_LEAGUES: Array<{
  leagueId: string;
  code: string;
  espnSlug: string;
  name: string;
}> = [
  { leagueId: 'E0', code: 'E0', espnSlug: 'eng.1', name: 'Premier League' },
  { leagueId: 'E1', code: 'E1', espnSlug: 'eng.2', name: 'EFL Championship' },
  { leagueId: 'SP1', code: 'SP1', espnSlug: 'esp.1', name: 'La Liga' },
  { leagueId: 'D1', code: 'D1', espnSlug: 'ger.1', name: 'Bundesliga' },
  { leagueId: 'I1', code: 'I1', espnSlug: 'ita.1', name: 'Serie A' },
  { leagueId: 'F1', code: 'F1', espnSlug: 'fra.1', name: 'Ligue 1' },
  { leagueId: 'N1', code: 'N1', espnSlug: 'ned.1', name: 'Eredivisie' },
  { leagueId: 'P1', code: 'P1', espnSlug: 'por.1', name: 'Primeira Liga' },
  { leagueId: 'SC0', code: 'SC0', espnSlug: 'sco.1', name: 'Scottish Premiership' },
];

/**
 * 100% Free, Keyless Public Data Feeds fetched directly on-device once per week.
 */
export const PUBLIC_DATA_FEEDS: PublicDataFeed[] = [
  // Football historical CSVs + closing odds (Football-Data.co.uk)
  ...FOOTBALL_LEAGUES.map((lg) => ({
    id: `fd-${lg.code}`,
    label: `Football-Data.co.uk (${lg.name})`,
    sport: 'football' as SportId,
    leagueId: lg.leagueId,
    leagueName: lg.name,
    kind: 'football-data-csv' as const,
    buildUrl: (now = new Date()) =>
      `https://www.football-data.co.uk/mmz4281/${getFootballSeasonCode(now)}/${lg.code}.csv`,
  })),

  // Football upcoming fixtures (ESPN public keyless scoreboard)
  ...FOOTBALL_LEAGUES.slice(0, 6).map((lg) => ({
    id: `espn-soccer-${lg.code}`,
    label: `ESPN Fixtures (${lg.name})`,
    sport: 'football' as SportId,
    leagueId: lg.leagueId,
    leagueName: lg.name,
    kind: 'espn-scoreboard' as const,
    buildUrl: (now = new Date()) =>
      `https://site.api.espn.com/apis/site/v2/sports/soccer/${lg.espnSlug}/scoreboard?dates=${formatEspnDateRange(now, 7)}`,
  })),

  // ClubElo external European club strength ratings
  {
    id: 'clubelo-daily',
    label: 'ClubElo Global Ratings CSV',
    sport: 'football',
    leagueId: 'GLOBAL_FOOTBALL',
    leagueName: 'European Clubs',
    kind: 'clubelo-csv',
    buildUrl: (now = new Date()) => `https://api.clubelo.com/${formatIsoDate(now)}`,
  },

  // NFL historical games + spreads (nflverse)
  {
    id: 'nflverse-games',
    label: 'nflverse Historical Games CSV',
    sport: 'nfl',
    leagueId: 'NFL',
    leagueName: 'NFL',
    kind: 'nflverse-csv',
    buildUrl: () => 'https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv',
  },

  // NFL upcoming scoreboard (ESPN public keyless)
  {
    id: 'espn-nfl',
    label: 'ESPN NFL Scoreboard',
    sport: 'nfl',
    leagueId: 'NFL',
    leagueName: 'NFL',
    kind: 'espn-scoreboard',
    buildUrl: (now = new Date()) =>
      `https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?dates=${formatEspnDateRange(now, 7)}`,
  },

  // NBA recent + upcoming games (ESPN public keyless)
  {
    id: 'espn-nba',
    label: 'ESPN NBA Scoreboard',
    sport: 'nba',
    leagueId: 'NBA',
    leagueName: 'NBA',
    kind: 'espn-scoreboard',
    buildUrl: (now = new Date()) =>
      `https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=${formatEspnDateRange(now, 7)}`,
  },

  // NHL recent + upcoming games (ESPN public keyless)
  {
    id: 'espn-nhl',
    label: 'ESPN NHL Scoreboard',
    sport: 'nhl',
    leagueId: 'NHL',
    leagueName: 'NHL',
    kind: 'espn-scoreboard',
    buildUrl: (now = new Date()) =>
      `https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard?dates=${formatEspnDateRange(now, 7)}`,
  },

  // MLB recent + upcoming games (ESPN public keyless)
  {
    id: 'espn-mlb',
    label: 'ESPN MLB Scoreboard',
    sport: 'mlb',
    leagueId: 'MLB',
    leagueName: 'MLB',
    kind: 'espn-scoreboard',
    buildUrl: (now = new Date()) =>
      `https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard?dates=${formatEspnDateRange(now, 7)}`,
  },

  // ATP Tennis match history CSV (Tennismylife TML-Database)
  {
    id: 'tml-atp',
    label: 'TennisMyLife ATP Match CSV',
    sport: 'tennis',
    leagueId: 'ATP',
    leagueName: 'ATP Tour',
    kind: 'tml-tennis-csv',
    buildUrl: (now = new Date()) =>
      `https://raw.githubusercontent.com/Tennismylife/TML-Database/master/${now.getUTCFullYear()}.csv`,
  },

  // ATP upcoming matches (ESPN public keyless)
  {
    id: 'espn-atp',
    label: 'ESPN ATP Scoreboard',
    sport: 'tennis',
    leagueId: 'ATP',
    leagueName: 'ATP Tour',
    kind: 'espn-scoreboard',
    buildUrl: () => 'https://site.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard',
  },
];

export const SPORT_META: Record<
  SportId,
  { label: string; short: string; hasDraw: boolean; color: string }
> = {
  football: { label: 'Football', short: 'SOC', hasDraw: true, color: '#3B82F6' },
  nfl: { label: 'NFL', short: 'NFL', hasDraw: false, color: '#10B981' },
  nba: { label: 'NBA', short: 'NBA', hasDraw: false, color: '#F59E0B' },
  nhl: { label: 'NHL', short: 'NHL', hasDraw: false, color: '#06B6D4' },
  mlb: { label: 'MLB', short: 'MLB', hasDraw: false, color: '#8B5CF6' },
  tennis: { label: 'ATP Tennis', short: 'ATP', hasDraw: false, color: '#EC4899' },
};
