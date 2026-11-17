import type { Reporter, TestCase, TestResult, FullResult } from '@playwright/test/reporter';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

// This is an allowlist serializer, not a scrubber. Never read errors, attachments,
// titles (which can contain parameter values), stdout/stderr or browser state.
export default class SafeReporter implements Reporter {
  private rows: string[] = [];
  private runnerError = false;
  constructor(private options: { outputFile?: string } = {}) {}
  printsToStdio() { return true; }
  onStdOut() {}
  onStdErr() {}
  onError() { this.runnerError = true; }
  onTestEnd(test: TestCase, result: TestResult) {
    const relative = path.relative(process.cwd(), test.location.file).replaceAll('\\', '/');
    const file = /^[\w./-]+$/.test(relative) ? relative : 'test';
    const location = `${file}:${test.location.line}:${test.location.column}`;
    const outcome = result.status === 'passed' ? '' : result.status === 'skipped'
      ? '<skipped/>' : '<failure message="E_TEST_FAILURE"/>';
    this.rows.push(`<testcase classname="browser" name="${location}" time="${result.duration / 1000}">${outcome}</testcase>`);
    process.stdout.write(`${location} ${result.status} ${result.duration}ms${outcome.includes('failure') ? ' E_TEST_FAILURE' : ''}\n`);
  }
  onEnd(result: FullResult) {
    if (this.runnerError || (result.status !== 'passed' && !this.rows.some(row => row.includes('<failure')))) {
      this.rows.push('<testcase classname="browser" name="runner"><failure message="E_RUNNER"/></testcase>');
      process.stdout.write('E_RUNNER\n');
    }
    const target = this.options.outputFile || 'safe-results/browser/results.xml';
    mkdirSync(path.dirname(target), { recursive: true });
    writeFileSync(target, `<?xml version="1.0" encoding="UTF-8"?><testsuite>${this.rows.join('')}</testsuite>\n`);
  }
}
