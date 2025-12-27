# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd onboard` to get started.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --status in_progress  # Claim work
bd close <id>         # Complete work
bd sync               # Sync with git
```

## Landing the Plane (Session Completion)

**MANDATORY WORKFLOW:**

1. **Create tests** - If appropriate
2. **Only work on one issue at a time**
2. **File issues for remaining work** - Create issues for anything that needs follow-up
3. **Run quality gates** (if code changed) - Tests, linters, builds
4. **Commit to version control, and only commit one issue at time**
5. **Update issue status** - Close finished work, update in-progress items
6. **Hand off** - Provide context for next session

## Tech stack
- Clojurescript UI
- Clojure back end
- Websockets
- Canvas for rendering graphics
- Version control using jj

