# Task 13 report: production smoke identity provisioning

## Change

- Production widget smoke tests now require `BITBRUSH_E2E_UUID` during test-file collection.
- The fixture seeds only `bitbrush_widget_uuid`; it removes any username value so the deployed widget obtains the authoritative username through `/api/users/reconnect`.
- Added the explicit `npm run test:production` command using `playwright.config.ts`.

## Fail-fast verification

Command (run from `e2e`, with the environment variable absent):

```text
env -u BITBRUSH_E2E_UUID npm run test:production
```

Observed result: exit code `1`, with `Error: BITBRUSH_E2E_UUID is required` reported while loading `bitbrush-widget.spec.ts`, before any test or browser navigation. Playwright subsequently reports `No tests found` because collection stopped at the required guard.

## Discovery evidence

- The production Playwright config targets `https://mark.dingwall.com.au` and matches `bitbrush-widget.spec.ts`.
- The widget implementation already POSTs to `/api/users/reconnect` when a saved UUID is present.
- The previous production fixture generated a random UUID and stored the committed username `PlaywrightBot`; both behaviors were removed.

## Privacy self-review

- No UUID, PIN, or credential value was added to source, fixtures, reports, screenshots, traces, or logs.
- The environment value is passed only to the browser's UUID storage operation at runtime; it is not printed or persisted by the test code.
- Production navigation was not attempted because the required environment variable was absent.

## Files

- `e2e/tests/bitbrush-widget.spec.ts`
- `e2e/package.json`
- `.superpowers/sdd/2026-09-12-username-pin-recovery/task-13-report.md`

## Concerns

The full production smoke suite was intentionally not run: doing so requires the provisioned identity and would navigate to the deployed site. Only the mandated local fail-fast path was executed.
