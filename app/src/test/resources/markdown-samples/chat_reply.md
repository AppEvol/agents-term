Issue #6 is an open bug about a flaky CI test, labeled `bug` and `P1`. You filed it yesterday and it has no comments yet.

**Title:** CI flake: emulator E2E tests fail with `RootViewWithoutFocusException` for the rest of a run

**What happened:** In CI run #33, 6 of the 13 emulator tests failed. Each one waited 10 seconds for the app window to get input focus, and it never did.

**What's known:**
- It hasn't happened again in the four full runs since (#35–#38).
- Run #33 was the first to include the screenshot-and-toast test (`appStateReporterAnswersVmRequestsOnlyWhileUserAllowsInspection`). That test passed.

**Already in place (since `8eaa859`):**
- Every test first sends a broadcast that closes system dialogs.
- When a test fails, the report adds a `[window focus at failure]` line showing which window held focus.

| Run | Result |
| --- | --- |
| #33 | 6 of 13 failed |
| #35–#38 | passed |

To reproduce:

```bash
gradle :app:connectedDebugAndroidTest --stacktrace
```

See [the issue](https://github.com/nedn/agents-term/issues/6) or [AgentsTermE2ETest.kt](file:///home/me/agents-term/app/src/androidTest/java/com/example/AgentsTermE2ETest.kt#L40).
