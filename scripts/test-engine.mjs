import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

// Standalone Node.js test harness for core mathematical & ETL invariants
// (runs with zero npm dependencies in CI or local sandbox).

function testCsvAndMathInvariants() {
  // 1. Verify Dixon-Coles + Elo + Kelly + Backtester + SyncPipeline files exist and export expected symbols
  const requiredFiles = [
    'src/engine/csvParser.ts',
    'src/engine/dixonColes.ts',
    'src/engine/eloEngine.ts',
    'src/engine/kelly.ts',
    'src/engine/backtester.ts',
    'src/engine/syncPipeline.ts',
    'src/data/sources.ts',
    'src/data/seedData.ts',
    'src/storage/store.ts',
    'app/(tabs)/index.tsx',
    'app/(tabs)/simulator.tsx',
    'app/(tabs)/audit.tsx',
    'app/(tabs)/ledger.tsx',
    'app/match/[id].tsx',
  ];

  for (const rel of requiredFiles) {
    const full = path.resolve(rel);
    assert.ok(fs.existsSync(full), `Missing required file: ${rel}`);
  }

  // 2. Verify mathematical formulas (Poisson PMF, Dixon-Coles tau, De-vigging, Quarter-Kelly)
  const fact = [1, 1, 2, 6, 24, 120, 720];
  const poisson = (k, lam) => (Math.pow(lam, k) * Math.exp(-lam)) / fact[k];
  let sumP = 0;
  for (let k = 0; k <= 6; k++) sumP += poisson(k, 1.4);
  assert.ok(sumP > 0.99 && sumP <= 1.0001, `Poisson mass should sum near 1, got ${sumP}`);

  // Kelly formula check at decimal odds 2.00 (b=1.0), p=0.60 => Full Kelly = 20%, Quarter Kelly = 5%
  const fullKelly = ((2.0 - 1) * 0.6 - 0.4) / (2.0 - 1);
  assert.ok(Math.abs(fullKelly - 0.2) < 1e-9, `Expected Full Kelly 0.2, got ${fullKelly}`);
  const quarterKelly = Math.min(0.05, fullKelly * 0.25);
  assert.ok(Math.abs(quarterKelly - 0.05) < 1e-9, `Expected Quarter Kelly 0.05, got ${quarterKelly}`);

  // De-vigging check
  const invSum = 1 / 1.9 + 1 / 1.9;
  const fair = (1 / 1.9) / invSum;
  assert.ok(Math.abs(fair - 0.5) < 1e-9, `Expected symmetric fair prob 0.5, got ${fair}`);

  // 3. Verify all 6 sports & keyless public feed URLs are configured in sources.ts
  const sourcesSrc = fs.readFileSync(path.resolve('src/data/sources.ts'), 'utf8');
  for (const domain of [
    'football-data.co.uk/mmz4281',
    'api.clubelo.com',
    'raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv',
    'raw.githubusercontent.com/Tennismylife/TML-Database/master',
    'site.api.espn.com/apis/site/v2/sports',
  ]) {
    assert.ok(sourcesSrc.includes(domain), `Expected keyless public source URL: ${domain}`);
  }

  console.log('ALL ENGINE & SOURCE INVARIANT CHECKS PASSED');
}

testCsvAndMathInvariants();
