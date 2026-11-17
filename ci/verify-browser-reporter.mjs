import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
export function verify(mode = 'failure') {
const fixture = mkdtempSync(path.join(tmpdir(), 'bitbrush-reporter-'));
let valid = false;
try {
  const sentinel = `SECRET_${randomUUID()}`;
  const report = path.join(fixture, 'safe.xml');
  const child = spawnSync(process.execPath,
    ['node_modules/@playwright/test/cli.js', 'test', '--config=playwright.reporter.config.ts'], {
      cwd: path.resolve('e2e'), timeout: mode === 'timeout' ? 2000 : 30000, encoding: 'utf8', maxBuffer: 1024 * 1024,
      env: { ...process.env, REPORTER_SENTINEL: sentinel, REPORTER_FIXTURE_MODE: mode, SAFE_REPORT_FILE: report,
        SAFE_RAW_DIR: path.join(fixture, 'raw') },
    });
  const published = readFileSync(report, 'utf8');
  valid = child.status === 1 && !child.error &&
    !`${child.stdout}${child.stderr}${published}`.includes(sentinel) &&
    (published.match(/<testcase /g) || []).length === 1 &&
    published.includes('reporter-fixture/credential.spec.ts:2:5') &&
    (published.match(/<failure message="E_TEST_FAILURE"\/>/g) || []).length === 1 &&
    !published.includes('E_RUNNER') && !published.includes('<skipped');
} catch { /* No child diagnostics leave the isolated fixture. */ }
finally { rmSync(fixture, { recursive: true, force: true }); }
return valid;
}
const valid = verify() && !verify('success') && !verify('unrelated') && !verify('timeout');
console.log(valid ? 'SAFE_REPORTER_OK' : 'SAFE_REPORTER_INVALID');
process.exitCode = valid ? 0 : 1;
