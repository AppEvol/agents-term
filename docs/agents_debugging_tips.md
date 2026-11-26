# Debugging a tab from a screenshot

Read the small tab ID immediately above the message composer. Use the entire
ID, including the `tab_` prefix, to find that conversation on the VM.

```sh
root="${AGENTSTERM_HOME:-$HOME/.agents-term}"
tab_id=tab_2b9959750a80  # Replace with the ID from the screenshot.
jq --arg id "$tab_id" 'select(.id == $id)' "$root/agents_tabs/tabs.jsonl"
less "$root/agents_tabs/$tab_id/messages.json"
```

The index entry includes the tab's working directory, agent, and status.
The per-tab directory holds the saved chat history. Treat daemon-managed
state as read-only. For live phone inspection, read `$root/live-debugging.md`.
