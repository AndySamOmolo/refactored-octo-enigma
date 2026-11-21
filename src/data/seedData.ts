import { HistoricalMatch, SportId, TennisSurface } from '../types';

export interface SeedUpcomingFixture {
  id: string;
  sport: SportId;
  leagueId: string;
  leagueName: string;
  daysFromNow: number;
  homeTeam: string;
  awayTeam: string;
  surface?: TennisSurface;
  marketOdds: {
    home: number;
    draw?: number;
    away: number;
    source: string;
  };
}

export const SEED_CLUB_ELO: Record<string, number> = {
  Arsenal: 2048,
  'Bayern Munich': 2041,
  Barcelona: 2034,
  'Manchester City': 2003,
  'Real Madrid': 2002,
  'Paris Saint-Germain': 1990,
  'Inter Milan': 1949,
  Liverpool: 1938,
  'Atletico Madrid': 1911,
  Brighton: 1910,
  'Borussia Dortmund': 1901,
  'Aston Villa': 1899,
  Chelsea: 1886,
  'Bayer Leverkusen': 1863,
  'Newcastle United': 1853,
  'Manchester United': 1852,
  'RB Leipzig': 1834,
  Juventus: 1830,
  'AC Milan': 1820,
  Napoli: 1818,
  Tottenham: 1814,
  'Athletic Club': 1795,
  Villarreal: 1790,
  'Real Sociedad': 1790,
  Roma: 1865,
  Lazio: 1809,
};

interface SeedLeagueConfig {
  sport: SportId;
  leagueId: string;
  teams: Array<{ name: string; strength: number }>;
  surfaces?: TennisSurface[];
}

const SEED_LEAGUES: SeedLeagueConfig[] = [
  {
    sport: 'football',
    leagueId: 'E0',
    teams: [
      { name: 'Arsenal', strength: 1.95 },
      { name: 'Manchester City', strength: 1.9 },
      { name: 'Liverpool', strength: 1.84 },
      { name: 'Aston Villa', strength: 1.62 },
      { name: 'Chelsea', strength: 1.6 },
      { name: 'Brighton', strength: 1.56 },
      { name: 'Newcastle United', strength: 1.54 },
      { name: 'Tottenham', strength: 1.5 },
      { name: 'Manchester United', strength: 1.48 },
      { name: 'Nottingham Forest', strength: 1.38 },
      { name: 'West Ham United', strength: 1.32 },
      { name: 'Wolverhampton', strength: 1.22 },
    ],
  },
  {
    sport: 'football',
    leagueId: 'SP1',
    teams: [
      { name: 'Barcelona', strength: 1.94 },
      { name: 'Real Madrid', strength: 1.92 },
      { name: 'Atletico Madrid', strength: 1.72 },
      { name: 'Athletic Club', strength: 1.56 },
      { name: 'Villarreal', strength: 1.54 },
      { name: 'Real Sociedad', strength: 1.5 },
      { name: 'Real Betis', strength: 1.44 },
      { name: 'Sevilla', strength: 1.32 },
    ],
  },
  {
    sport: 'football',
    leagueId: 'D1',
    teams: [
      { name: 'Bayern Munich', strength: 2.0 },
      { name: 'Bayer Leverkusen', strength: 1.76 },
      { name: 'Borussia Dortmund', strength: 1.7 },
      { name: 'RB Leipzig', strength: 1.66 },
      { name: 'Stuttgart', strength: 1.56 },
      { name: 'Eintracht Frankfurt', strength: 1.52 },
      { name: 'Freiburg', strength: 1.44 },
      { name: 'Wolfsburg', strength: 1.34 },
    ],
  },
  {
    sport: 'football',
    leagueId: 'I1',
    teams: [
      { name: 'Inter Milan', strength: 1.88 },
      { name: 'Napoli', strength: 1.74 },
      { name: 'Juventus', strength: 1.68 },
      { name: 'AC Milan', strength: 1.65 },
      { name: 'Roma', strength: 1.62 },
      { name: 'Lazio', strength: 1.56 },
      { name: 'Atalanta', strength: 1.64 },
      { name: 'Fiorentina', strength: 1.48 },
    ],
  },
  {
    sport: 'nfl',
    leagueId: 'NFL',
    teams: [
      { name: 'Kansas City Chiefs', strength: 1.85 },
      { name: 'Buffalo Bills', strength: 1.8 },
      { name: 'Baltimore Ravens', strength: 1.78 },
      { name: 'Detroit Lions', strength: 1.76 },
      { name: 'Philadelphia Eagles', strength: 1.72 },
      { name: 'San Francisco 49ers', strength: 1.64 },
      { name: 'Green Bay Packers', strength: 1.6 },
      { name: 'Houston Texans', strength: 1.54 },
    ],
  },
  {
    sport: 'nba',
    leagueId: 'NBA',
    teams: [
      { name: 'Boston Celtics', strength: 1.88 },
      { name: 'Oklahoma City Thunder', strength: 1.86 },
      { name: 'Cleveland Cavaliers', strength: 1.76 },
      { name: 'Denver Nuggets', strength: 1.72 },
      { name: 'New York Knicks', strength: 1.68 },
      { name: 'Minnesota Timberwolves', strength: 1.65 },
      { name: 'Dallas Mavericks', strength: 1.62 },
      { name: 'Los Angeles Lakers', strength: 1.58 },
    ],
  },
  {
    sport: 'nhl',
    leagueId: 'NHL',
    teams: [
      { name: 'Florida Panthers', strength: 1.8 },
      { name: 'Edmonton Oilers', strength: 1.78 },
      { name: 'Dallas Stars', strength: 1.75 },
      { name: 'Carolina Hurricanes', strength: 1.72 },
      { name: 'Winnipeg Jets', strength: 1.7 },
      { name: 'New York Rangers', strength: 1.64 },
    ],
  },
  {
    sport: 'mlb',
    leagueId: 'MLB',
    teams: [
      { name: 'Los Angeles Dodgers', strength: 1.84 },
      { name: 'New York Yankees', strength: 1.74 },
      { name: 'Philadelphia Phillies', strength: 1.7 },
      { name: 'Baltimore Orioles', strength: 1.66 },
      { name: 'Atlanta Braves', strength: 1.65 },
      { name: 'Houston Astros', strength: 1.62 },
    ],
  },
  {
    sport: 'tennis',
    leagueId: 'ATP',
    surfaces: ['Hard', 'Clay', 'Grass'],
    teams: [
      { name: 'Jannik Sinner', strength: 1.95 },
      { name: 'Carlos Alcaraz', strength: 1.93 },
      { name: 'Alexander Zverev', strength: 1.8 },
      { name: 'Novak Djokovic', strength: 1.78 },
      { name: 'Taylor Fritz', strength: 1.7 },
      { name: 'Daniil Medvedev', strength: 1.68 },
      { name: 'Alex de Minaur', strength: 1.62 },
      { name: 'Casper Ruud', strength: 1.6 },
    ],
  },
];

function deterministicNoise(seed: number): number {
  const x = Math.sin(seed * 12.9898 + 78.233) * 43758.5453;
  return x - Math.floor(x);
}

export function buildSeedHistoricalMatches(now: Date = new Date()): HistoricalMatch[] {
  const matches: HistoricalMatch[] = [];
  let seq = 1;

  for (const lg of SEED_LEAGUES) {
    const n = lg.teams.length;
    let dayOffset = 140;

    for (let round = 0; round < 2; round++) {
      for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
          const home = round % 2 === 0 ? lg.teams[i] : lg.teams[j];
          const away = round % 2 === 0 ? lg.teams[j] : lg.teams[i];
          const u1 = deterministicNoise(seq * 17 + i * 31 + j * 13);
          const u2 = deterministicNoise(seq * 29 + i * 19 + j * 41);
          const u3 = deterministicNoise(seq * 43 + i * 7 + j * 23);

          const matchDate = new Date(now.getTime() - dayOffset * 86400000)
            .toISOString()
            .slice(0, 10);
          dayOffset = Math.max(2, dayOffset - 2);

          const m: HistoricalMatch = {
            id: `seed-${lg.leagueId}-${seq}`,
            sport: lg.sport,
            leagueId: lg.leagueId,
            date: matchDate,
            homeTeam: home.name,
            awayTeam: away.name,
            homeScore: 0,
            awayScore: 0,
          };

          if (lg.sport === 'football') {
            const expH = Math.max(0.4, 1.35 * (home.strength / away.strength) * 1.15);
            const expA = Math.max(0.3, 1.15 * (away.strength / home.strength));
            m.homeScore = Math.max(0, Math.min(5, Math.round(expH + (u1 - 0.48) * 2.2)));
            m.awayScore = Math.max(0, Math.min(4, Math.round(expA + (u2 - 0.5) * 2.0)));
            m.homeHalfTimeGoals = Math.min(m.homeScore, Math.round(m.homeScore * (0.35 + u3 * 0.3)));
            m.awayHalfTimeGoals = Math.min(m.awayScore, Math.round(m.awayScore * (0.35 + u2 * 0.3)));
            m.homeShots = Math.round(10 + home.strength * 3.2 + (u1 - 0.5) * 6);
            m.awayShots = Math.round(8 + away.strength * 2.8 + (u2 - 0.5) * 6);
            m.homeShotsOnTarget = Math.min(m.homeShots, Math.max(m.homeScore, Math.round(m.homeShots * 0.36)));
            m.awayShotsOnTarget = Math.min(m.awayShots, Math.max(m.awayScore, Math.round(m.awayShots * 0.34)));
            m.homeCorners = Math.max(1, Math.round(4 + home.strength * 1.6 + (u3 - 0.5) * 4));
            m.awayCorners = Math.max(1, Math.round(3.5 + away.strength * 1.3 + (u1 - 0.5) * 4));
            m.homeFouls = Math.round(10 + u2 * 5);
            m.awayFouls = Math.round(11 + u3 * 5);
            m.homeYellowCards = Math.round(1.3 + u2 * 2);
            m.awayYellowCards = Math.round(1.6 + u3 * 2);
            m.homeRedCards = u3 > 0.94 ? 1 : 0;
            m.awayRedCards = u1 > 0.93 ? 1 : 0;

            const pH = Math.min(0.76, Math.max(0.18, 0.44 + (home.strength - away.strength) * 0.48));
            const pD = 0.26;
            const pA = Math.max(0.1, 1 - pH - pD);
            m.oddsHome = Number((1 / (pH * 1.05)).toFixed(2));
            m.oddsDraw = Number((1 / (pD * 1.05)).toFixed(2));
            m.oddsAway = Number((1 / (pA * 1.05)).toFixed(2));
            m.avgOver25Odds = Number((1.72 + u3 * 0.4).toFixed(2));
            m.avgUnder25Odds = Number((1.88 + (1 - u3) * 0.35).toFixed(2));
          } else if (lg.sport === 'nfl') {
            const diff = (home.strength - away.strength) * 18 + 2.5;
            m.homeScore = Math.max(7, Math.round(23 + diff * 0.5 + (u1 - 0.5) * 16));
            m.awayScore = Math.max(3, Math.round(21 - diff * 0.5 + (u2 - 0.5) * 16));
            if (m.homeScore === m.awayScore) m.homeScore += 3;
            m.spreadLine = Number((diff * 0.7).toFixed(1));
            m.totalLine = Number((44.5 + (u3 - 0.5) * 8).toFixed(1));
            m.homeRestDays = u1 > 0.8 ? 10 : u1 < 0.15 ? 4 : 7;
            m.awayRestDays = u2 > 0.8 ? 10 : u2 < 0.15 ? 4 : 7;
            const pH = Math.min(0.8, Math.max(0.22, 0.53 + (home.strength - away.strength) * 0.7));
            m.oddsHome = Number((1 / (pH * 1.045)).toFixed(2));
            m.oddsAway = Number((1 / ((1 - pH) * 1.045)).toFixed(2));
          } else if (lg.sport === 'nba') {
            const diff = (home.strength - away.strength) * 16 + 3;
            m.homeScore = Math.round(114 + diff * 0.5 + (u1 - 0.5) * 18);
            m.awayScore = Math.round(111 - diff * 0.5 + (u2 - 0.5) * 18);
            if (m.homeScore === m.awayScore) m.homeScore += 2;
            const pH = Math.min(0.82, Math.max(0.2, 0.55 + (home.strength - away.strength) * 0.8));
            m.oddsHome = Number((1 / (pH * 1.045)).toFixed(2));
            m.oddsAway = Number((1 / ((1 - pH) * 1.045)).toFixed(2));
          } else if (lg.sport === 'nhl') {
            m.homeScore = Math.max(0, Math.round(3.1 + (home.strength - away.strength) * 2 + (u1 - 0.5) * 3));
            m.awayScore = Math.max(0, Math.round(2.7 + (away.strength - home.strength) * 2 + (u2 - 0.5) * 3));
            if (m.homeScore === m.awayScore) m.homeScore += 1;
            const pH = Math.min(0.74, Math.max(0.28, 0.53 + (home.strength - away.strength) * 0.6));
            m.oddsHome = Number((1 / (pH * 1.045)).toFixed(2));
            m.oddsAway = Number((1 / ((1 - pH) * 1.045)).toFixed(2));
          } else if (lg.sport === 'mlb') {
            m.homeScore = Math.max(0, Math.round(4.6 + (home.strength - away.strength) * 2.5 + (u1 - 0.5) * 5));
            m.awayScore = Math.max(0, Math.round(4.2 + (away.strength - home.strength) * 2.5 + (u2 - 0.5) * 5));
            if (m.homeScore === m.awayScore) m.homeScore += 1;
            const pH = Math.min(0.7, Math.max(0.32, 0.52 + (home.strength - away.strength) * 0.5));
            m.oddsHome = Number((1 / (pH * 1.04)).toFixed(2));
            m.oddsAway = Number((1 / ((1 - pH) * 1.04)).toFixed(2));
          } else {
            m.surface = lg.surfaces?.[seq % 3] ?? 'Hard';
            const pH = Math.min(0.84, Math.max(0.18, 0.5 + (home.strength - away.strength) * 0.9));
            const homeWon = u1 < pH;
            m.homeScore = homeWon ? 2 : 0;
            m.awayScore = homeWon ? 0 : 2;
            m.homeAces = Math.round(5 + home.strength * 2.4 + u2 * 5);
            m.awayAces = Math.round(4 + away.strength * 2.2 + u3 * 5);
            m.homeDoubleFaults = Math.round(1.5 + u3 * 2.5);
            m.awayDoubleFaults = Math.round(1.8 + u2 * 2.5);
            m.homeFirstServeIn = 46;
            m.homeFirstServeWon = Math.round(31 + home.strength * 3.2);
            m.awayFirstServeIn = 45;
            m.awayFirstServeWon = Math.round(30 + away.strength * 3.0);
            m.oddsHome = Number((1 / (pH * 1.05)).toFixed(2));
            m.oddsAway = Number((1 / ((1 - pH) * 1.05)).toFixed(2));
          }

          matches.push(m);
          seq++;
        }
      }
    }
  }

  return matches;
}

export const SEED_UPCOMING_FIXTURES: SeedUpcomingFixture[] = [
  {
    id: 'seed-fix-e0-1',
    sport: 'football',
    leagueId: 'E0',
    leagueName: 'Premier League',
    daysFromNow: 1,
    homeTeam: 'Arsenal',
    awayTeam: 'Chelsea',
    marketOdds: { home: 1.76, draw: 3.85, away: 4.6, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-e0-2',
    sport: 'football',
    leagueId: 'E0',
    leagueName: 'Premier League',
    daysFromNow: 1,
    homeTeam: 'Liverpool',
    awayTeam: 'Manchester City',
    marketOdds: { home: 2.38, draw: 3.45, away: 2.95, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-e0-3',
    sport: 'football',
    leagueId: 'E0',
    leagueName: 'Premier League',
    daysFromNow: 2,
    homeTeam: 'Aston Villa',
    awayTeam: 'Brighton',
    marketOdds: { home: 2.25, draw: 3.5, away: 3.15, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-sp1-1',
    sport: 'football',
    leagueId: 'SP1',
    leagueName: 'La Liga',
    daysFromNow: 2,
    homeTeam: 'Barcelona',
    awayTeam: 'Atletico Madrid',
    marketOdds: { home: 1.88, draw: 3.7, away: 4.1, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-d1-1',
    sport: 'football',
    leagueId: 'D1',
    leagueName: 'Bundesliga',
    daysFromNow: 2,
    homeTeam: 'Bayern Munich',
    awayTeam: 'Bayer Leverkusen',
    marketOdds: { home: 1.82, draw: 3.9, away: 4.2, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-i1-1',
    sport: 'football',
    leagueId: 'I1',
    leagueName: 'Serie A',
    daysFromNow: 3,
    homeTeam: 'Inter Milan',
    awayTeam: 'Napoli',
    marketOdds: { home: 1.95, draw: 3.45, away: 3.95, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-nfl-1',
    sport: 'nfl',
    leagueId: 'NFL',
    leagueName: 'NFL',
    daysFromNow: 2,
    homeTeam: 'Buffalo Bills',
    awayTeam: 'Kansas City Chiefs',
    marketOdds: { home: 1.92, away: 1.96, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-nfl-2',
    sport: 'nfl',
    leagueId: 'NFL',
    leagueName: 'NFL',
    daysFromNow: 2,
    homeTeam: 'Detroit Lions',
    awayTeam: 'Philadelphia Eagles',
    marketOdds: { home: 1.85, away: 2.04, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-nba-1',
    sport: 'nba',
    leagueId: 'NBA',
    leagueName: 'NBA',
    daysFromNow: 1,
    homeTeam: 'Boston Celtics',
    awayTeam: 'Denver Nuggets',
    marketOdds: { home: 1.62, away: 2.42, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-nba-2',
    sport: 'nba',
    leagueId: 'NBA',
    leagueName: 'NBA',
    daysFromNow: 1,
    homeTeam: 'Oklahoma City Thunder',
    awayTeam: 'New York Knicks',
    marketOdds: { home: 1.58, away: 2.52, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-nhl-1',
    sport: 'nhl',
    leagueId: 'NHL',
    leagueName: 'NHL',
    daysFromNow: 1,
    homeTeam: 'Florida Panthers',
    awayTeam: 'Edmonton Oilers',
    marketOdds: { home: 1.86, away: 2.02, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-mlb-1',
    sport: 'mlb',
    leagueId: 'MLB',
    leagueName: 'MLB',
    daysFromNow: 3,
    homeTeam: 'Los Angeles Dodgers',
    awayTeam: 'New York Yankees',
    marketOdds: { home: 1.74, away: 2.18, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-atp-1',
    sport: 'tennis',
    leagueId: 'ATP',
    leagueName: 'ATP Tour',
    daysFromNow: 1,
    homeTeam: 'Jannik Sinner',
    awayTeam: 'Carlos Alcaraz',
    surface: 'Hard',
    marketOdds: { home: 1.84, away: 2.04, source: 'Offline Baseline' },
  },
  {
    id: 'seed-fix-atp-2',
    sport: 'tennis',
    leagueId: 'ATP',
    leagueName: 'ATP Tour',
    daysFromNow: 2,
    homeTeam: 'Alexander Zverev',
    awayTeam: 'Taylor Fritz',
    surface: 'Hard',
    marketOdds: { home: 1.72, away: 2.22, source: 'Offline Baseline' },
  },
];
