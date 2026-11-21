import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const wfPath = path.resolve('.github/workflows/android-rolling-release.yml');
assert.ok(fs.existsSync(wfPath), 'Missing GitHub Actions rolling release workflow');
const wf = fs.readFileSync(wfPath, 'utf8');

const requiredSnippets = [
  'permissions:',
  'contents: write',
  'schedule:',
  'sdkmanager" --licenses',
  'npx expo prebuild --platform android --clean',
  './gradlew assembleRelease',
  'git tag -f latest',
  'git push -f origin refs/tags/latest',
  'softprops/action-gh-release@v2',
  'tag_name: latest',
  'dist/edgeledger-android-latest.apk',
];

for (const snippet of requiredSnippets) {
  assert.ok(wf.includes(snippet), `Workflow missing required directive: ${snippet}`);
}

assert.ok(!wf.includes("cache: 'npm'"), 'Workflow must not set cache: npm without package-lock.json');
assert.ok(!wf.includes('android-actions/setup-android'), 'Workflow must not use broken setup-android@v3');

console.log('GITHUB ACTIONS ROLLING RELEASE WORKFLOW VERIFIED (WITH BOTH CI FIXES)');
