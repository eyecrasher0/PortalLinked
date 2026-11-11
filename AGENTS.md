# Agent Guide

Preserve the owner's scope and verify the exact release artifact. Keep private
`.agents/` and `.sober/` material out of commits. Each platform/version directory
is a standalone project; use its included Gradle wrapper.

When Codex contributes to a commit, retain the owner's Git author identity and
include this trailer after a blank line:

```text
Co-authored-by: Codex <codex@openai.com>
```
