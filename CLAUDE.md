@AGENTS.md

## Claude Code specifics

- Run `.\dev.ps1 <cmd>` with the PowerShell tool (pre-approved in `.claude/settings.json`).
- Long jobs (`apk`, first `build`) go in the background; you are notified when they finish.
- Use `Grep`/`Glob` (they respect `.ignore`) and `Read` with offsets on files over 300 lines.
