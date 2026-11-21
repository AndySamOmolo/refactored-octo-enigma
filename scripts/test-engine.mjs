import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

function runV2Checks() {
  const requiredFiles = [
    'src/engine/csvParser.ts',
    'src/engine/dixonColes.ts',
    'src/engine/eloEngine.ts',
    'src/engine/kelly.ts',
    'src/engine/backtester.ts',
    'src/engine/teamStats.ts',
    'src/engine/marketPredictor.ts',
    'src/engine/syncPipeline.ts',
    'src/components/TeamAvatar.tsx',
    'src/components/ProbBar.tsx',
    'src/components/FormDots.tsx',
    'src/components/StatCompareBar.tsx',
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
    assert.ok(fs.existsSync(path.resolve(rel)), `Missing required v2 file: ${rel}`);
  }

  // Verify rich CSV columns are parsed in csvParser.ts
  const csvSrc = fs.readFileSync(path.resolve('src/engine/csvParser.ts'), 'utf8');
  for (const col of ['HTHG', 'HST', 'HC', 'HY', 'Avg>2.5', 'spread_line', 'home_rest', 'w_ace', 'w_1stWon']) {
    assert.ok(csvSrc.includes(col), `Expected free dataset column extraction: ${col}`);
  }

  // Verify UI screens avoid raw technical jargon in user-facing labels
  const uiFiles = [
    'app/(tabs)/index.tsx',
    'app/(tabs)/simulator.tsx',
    'app/(tabs)/audit.tsx',
    'app/(tabs)/ledger.tsx',
    'app/match/[id].tsx',
  ];
  for (const f of uiFiles) {
    const src = fs.readFileSync(path.resolve(f), 'utf8');
    for (const banned of ['Brier Score', 'Log-Loss', 'Dixon-Coles', 'Q-KELLY', 'Quarter-Kelly']) {
      assert.ok(!src.includes(banned), `Found technical jargon "${banned}" in ${f}`);
    }
  }

  // Verify typed-route navigation fix in index.tsx
  const indexSrc = fs.readFileSync(path.resolve('app/(tabs)/index.tsx'), 'utf8');
  assert.ok(indexSrc.includes("pathname: '/match/[id]'"), 'Expected typed router.push object in index.tsx');

  console.log('ALL V2 ENGINE, RICH DATASET & PLAIN-ENGLISH UI CHECKS PASSED');
}

runV2Checks();
