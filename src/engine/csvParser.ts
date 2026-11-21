import { HistoricalMatch, SportId, TennisSurface } from '../types';

/**
 * RFC-4180 compliant zero-dependency CSV parser that handles quoted fields,
 * escaped quotes (""), and CRLF/LF line endings cleanly on-device.
 */
export function parseCsv(text: string): Record<string, string>[] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;
  const cleaned = text.replace(/^\uFEFF/, '');

  for (let i = 0; i < cleaned.length; i++) {
    const ch = cleaned[i];
    if (inQuotes) {
      if (ch === '"') {
        if (cleaned[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      row.push(field.trim());
      field = '';
    } else if (ch === '\n') {
      row.push(field.trim());
      if (row.some((cell) => cell.length > 0)) {
        rows.push(row);
      }
      row = [];
      field = '';
    } else if (ch !== '\r') {
      field += ch;
    }
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field.trim());
    if (row.some((cell) => cell.length > 0)) {
      rows.push(row);
    }
  }

  if (rows.length < 2) return [];
  const headers = rows[0];
  const out: Record<string, string>[] = [];
  for (let r = 1; r < rows.length; r++) {
    const vals = rows[r];
    const obj: Record<string, string> = {};
    for (let c = 0; c < headers.length; c++) {
      obj[headers[c]] = vals[c] ?? '';
    }
    out.push(obj);
  }
  return out;
}

const TEAM_ALIASES: Record<string, string> = {
  'man city': 'Manchester City',
  'manchester city': 'Manchester City',
  'man united': 'Manchester United',
  'man utd': 'Manchester United',
  'manchester united': 'Manchester United',
  "nott'm forest": 'Nottingham Forest',
  'nottingham forest': 'Nottingham Forest',
  wolves: 'Wolverhampton',
  'wolverhampton wanderers': 'Wolverhampton',
  spurs: 'Tottenham',
  'tottenham hotspur': 'Tottenham',
  newcastle: 'Newcastle United',
  'west ham': 'West Ham United',
  brighton: 'Brighton',
  'brighton & hove albion': 'Brighton',
  'ath madrid': 'Atletico Madrid',
  'atlético madrid': 'Atletico Madrid',
  'ath bilbao': 'Athletic Club',
  'athletic bilbao': 'Athletic Club',
  'real sociedad': 'Real Sociedad',
  sociedad: 'Real Sociedad',
  betis: 'Real Betis',
  'paris sg': 'Paris Saint-Germain',
  psg: 'Paris Saint-Germain',
  'bayern munich': 'Bayern Munich',
  'bayern münchen': 'Bayern Munich',
  dortmund: 'Borussia Dortmund',
  leverkusen: 'Bayer Leverkusen',
  'rb leipzig': 'RB Leipzig',
  inter: 'Inter Milan',
  internazionale: 'Inter Milan',
  milan: 'AC Milan',
  'ac milan': 'AC Milan',
  juve: 'Juventus',
  'la clippers': 'LA Clippers',
  'los angeles clippers': 'LA Clippers',
  'la lakers': 'Los Angeles Lakers',
};

export function canonicalizeTeamName(raw: string): string {
  const trimmed = raw.trim().replace(/\s+/g, ' ');
  if (!trimmed) return trimmed;
  const key = trimmed.toLowerCase();
  return TEAM_ALIASES[key] ?? trimmed;
}

/**
 * Parses Football-Data.co.uk date strings (DD/MM/YYYY or DD/MM/YY) into YYYY-MM-DD.
 */
export function parseFootballDataDate(raw: string): string | null {
  const s = raw.trim();
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  const parts = s.split('/');
  if (parts.length !== 3) return null;
  const dd = parts[0].padStart(2, '0');
  const mm = parts[1].padStart(2, '0');
  const yyyy = parts[2].length === 2 ? `20${parts[2]}` : parts[2];
  return `${yyyy}-${mm}-${dd}`;
}

function parsePositiveFloat(raw?: string): number | undefined {
  if (!raw) return undefined;
  const n = Number.parseFloat(raw);
  return Number.isFinite(n) && n > 1.01 ? n : undefined;
}

/**
 * Parses Football-Data.co.uk CSV into HistoricalMatch rows with closing market odds.
 */
export function parseFootballDataCsv(
  csvText: string,
  leagueId: string
): HistoricalMatch[] {
  const rows = parseCsv(csvText);
  const matches: HistoricalMatch[] = [];

  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    const date = parseFootballDataDate(r.Date || '');
    const homeTeam = canonicalizeTeamName(r.HomeTeam || r.Home || '');
    const awayTeam = canonicalizeTeamName(r.AwayTeam || r.Away || '');
    const fthg = Number.parseInt(r.FTHG ?? r.HG ?? '', 10);
    const ftag = Number.parseInt(r.FTAG ?? r.AG ?? '', 10);

    if (!date || !homeTeam || !awayTeam || Number.isNaN(fthg) || Number.isNaN(ftag)) {
      continue;
    }

    const oddsHome =
      parsePositiveFloat(r.AvgH) ??
      parsePositiveFloat(r.B365H) ??
      parsePositiveFloat(r.PSH);
    const oddsDraw =
      parsePositiveFloat(r.AvgD) ??
      parsePositiveFloat(r.B365D) ??
      parsePositiveFloat(r.PSD);
    const oddsAway =
      parsePositiveFloat(r.AvgA) ??
      parsePositiveFloat(r.B365A) ??
      parsePositiveFloat(r.PSA);

    matches.push({
      id: `fd-${leagueId}-${date}-${homeTeam}-${awayTeam}`,
      sport: 'football',
      leagueId,
      date,
      homeTeam,
      awayTeam,
      homeScore: fthg,
      awayScore: ftag,
      oddsHome,
      oddsDraw,
      oddsAway,
    });
  }

  return matches;
}

/**
 * Parses ClubElo daily CSV into a canonical team -> Elo map.
 */
export function parseClubEloCsv(csvText: string): Record<string, number> {
  const rows = parseCsv(csvText);
  const map: Record<string, number> = {};
  for (const r of rows) {
    const club = canonicalizeTeamName(r.Club || '');
    const elo = Number.parseFloat(r.Elo || '');
    if (club && Number.isFinite(elo)) {
      map[club] = Math.round(elo);
    }
  }
  return map;
}

/**
 * Parses nflverse games.csv into HistoricalMatch records (recent 3 seasons).
 */
export function parseNflverseGamesCsv(
  csvText: string,
  minSeasonYear: number = 2023
): HistoricalMatch[] {
  const rows = parseCsv(csvText);
  const matches: HistoricalMatch[] = [];

  for (const r of rows) {
    const season = Number.parseInt(r.season || '', 10);
    if (Number.isNaN(season) || season < minSeasonYear) continue;
    const date = (r.gameday || '').slice(0, 10);
    const homeTeam = canonicalizeTeamName(r.home_team || '');
    const awayTeam = canonicalizeTeamName(r.away_team || '');
    const homeScore = Number.parseInt(r.home_score || '', 10);
    const awayScore = Number.parseInt(r.away_score || '', 10);
    if (!date || !homeTeam || !awayTeam || Number.isNaN(homeScore) || Number.isNaN(awayScore)) {
      continue;
    }

    matches.push({
      id: r.game_id || `nfl-${date}-${homeTeam}-${awayTeam}`,
      sport: 'nfl',
      leagueId: 'NFL',
      date,
      homeTeam,
      awayTeam,
      homeScore,
      awayScore,
      oddsHome: americanToDecimal(Number.parseFloat(r.home_moneyline || '')),
      oddsAway: americanToDecimal(Number.parseFloat(r.away_moneyline || '')),
    });
  }
  return matches;
}

export function americanToDecimal(ml: number): number | undefined {
  if (!Number.isFinite(ml) || ml === 0) return undefined;
  if (ml > 0) return Number((1 + ml / 100).toFixed(3));
  return Number((1 + 100 / Math.abs(ml)).toFixed(3));
}

function normalizeSurface(raw: string): TennisSurface {
  const s = raw.toLowerCase();
  if (s.includes('clay')) return 'Clay';
  if (s.includes('grass')) return 'Grass';
  return 'Hard';
}

/**
 * Parses TennisMyLife / Sackmann-style ATP CSV into HistoricalMatch records.
 */
export function parseTmlTennisCsv(csvText: string): HistoricalMatch[] {
  const rows = parseCsv(csvText);
  const matches: HistoricalMatch[] = [];

  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    const winner = canonicalizeTeamName(r.winner_name || '');
    const loser = canonicalizeTeamName(r.loser_name || '');
    const rawDate = (r.tourney_date || '').trim();
    if (!winner || !loser || rawDate.length < 8) continue;

    const date = `${rawDate.slice(0, 4)}-${rawDate.slice(4, 6)}-${rawDate.slice(6, 8)}`;
    matches.push({
      id: `atp-${date}-${i}-${winner}-${loser}`,
      sport: 'tennis',
      leagueId: 'ATP',
      date,
      homeTeam: winner,
      awayTeam: loser,
      homeScore: 2,
      awayScore: 0,
      surface: normalizeSurface(r.surface || 'Hard'),
    });
  }

  return matches;
}

export interface ParsedEspnEvent {
  id: string;
  kickoffIso: string;
  completed: boolean;
  homeTeam: string;
  awayTeam: string;
  homeScore?: number;
  awayScore?: number;
  oddsHome?: number;
  oddsDraw?: number;
  oddsAway?: number;
}

/**
 * Parses ESPN public keyless scoreboard JSON across Soccer, NFL, NBA, NHL, MLB, and Tennis.
 */
export function parseEspnScoreboardJson(
  payload: unknown,
  sport: SportId
): ParsedEspnEvent[] {
  const data = payload as { events?: Array<Record<string, unknown>> } | null;
  if (!data || !Array.isArray(data.events)) return [];
  const results: ParsedEspnEvent[] = [];

  for (const ev of data.events) {
    const id = String(ev.id ?? '');
    const kickoffIso = String(ev.date ?? new Date().toISOString());
    const competitions = ev.competitions as Array<Record<string, unknown>> | undefined;
    const comp = competitions?.[0];
    if (!comp) continue;

    const competitors = comp.competitors as Array<Record<string, unknown>> | undefined;
    if (!competitors || competitors.length < 2) continue;

    const home =
      competitors.find((c) => c.homeAway === 'home') ?? competitors[0];
    const away =
      competitors.find((c) => c.homeAway === 'away') ?? competitors[1];

    const extractName = (c: Record<string, unknown>): string => {
      const team = c.team as Record<string, unknown> | undefined;
      const athlete = c.athlete as Record<string, unknown> | undefined;
      return canonicalizeTeamName(
        String(team?.displayName ?? team?.shortDisplayName ?? athlete?.displayName ?? '')
      );
    };

    const homeTeam = extractName(home);
    const awayTeam = extractName(away);
    if (!homeTeam || !awayTeam) continue;

    const status = (comp.status as Record<string, unknown> | undefined)?.type as
      | Record<string, unknown>
      | undefined;
    const completed = Boolean(status?.completed);

    const homeScore =
      home.score !== undefined ? Number.parseInt(String(home.score), 10) : undefined;
    const awayScore =
      away.score !== undefined ? Number.parseInt(String(away.score), 10) : undefined;

    // Optional ESPN embedded odds (if available in public payload)
    let oddsHome: number | undefined;
    let oddsDraw: number | undefined;
    let oddsAway: number | undefined;
    const oddsArr = comp.odds as Array<Record<string, unknown>> | undefined;
    const oddsObj = oddsArr?.[0];
    if (oddsObj) {
      const homeTeamOdds = oddsObj.homeTeamOdds as Record<string, unknown> | undefined;
      const awayTeamOdds = oddsObj.awayTeamOdds as Record<string, unknown> | undefined;
      const drawOdds = oddsObj.drawOdds as Record<string, unknown> | undefined;
      if (homeTeamOdds?.moneyLine !== undefined) {
        oddsHome = americanToDecimal(Number(homeTeamOdds.moneyLine));
      }
      if (awayTeamOdds?.moneyLine !== undefined) {
        oddsAway = americanToDecimal(Number(awayTeamOdds.moneyLine));
      }
      if (sport === 'football' && drawOdds?.moneyLine !== undefined) {
        oddsDraw = americanToDecimal(Number(drawOdds.moneyLine));
      }
    }

    results.push({
      id: `espn-${sport}-${id}`,
      kickoffIso,
      completed,
      homeTeam,
      awayTeam,
      homeScore: Number.isNaN(homeScore ?? NaN) ? undefined : homeScore,
      awayScore: Number.isNaN(awayScore ?? NaN) ? undefined : awayScore,
      oddsHome,
      oddsDraw,
      oddsAway,
    });
  }

  return results;
}
