Run `ls -la` first. Code next to punctuation: (`status`), `logs`, and `"ready": true`.

**Title:** CI flake: emulator E2E tests fail with `RootViewWithoutFocusException` for the rest of a run

Run #33 was the first to include the screenshot-and-toast test (`appStateReporterAnswersVmRequestsOnlyWhileUserAllowsInspection`). That test passed.

The renderer lives in `app/src/main/java/com/example/ui/markdown/MarkdownText.kt` and its tests in `app/src/test/java/com/example/ui/markdown/`.

3. **Check from the VM:** `python3 app_state_cli/app_state_cli.py status` should report `"ready": true`. Then try `state`, `logs` and `screenshot -o /tmp/s.png`.

### A heading with `inlineCode` in it

Bold **`code`**, a link [`file.kt`](file:///a/file.kt), and double backticks around a tick: `` a`b ``.
