# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd onboard` to get started.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --status in_progress  # Claim work
bd close <id>         # Complete work
bd sync               # Sync issues with version control
```

## Landing the Plane (Session Completion)

**MANDATORY WORKFLOW:**

1. **Only work on one issue at a time**
2. **Create tests** - If appropriate
3. **File issues for remaining work** - Create issues for anything that needs follow-up
4. **Run quality gates** (if code changed) - Tests, linters, builds
5. **Commit to version control, and only commit one issue at a time**
6. **Update issue status** - Close finished work, update in-progress items
7. **Hand off** - Provide context for next session

## Tech stack
- Clojurescript UI
- Clojure back end
- Websockets
- Canvas for rendering graphics
- Version control using jj, never use git commands

### Best Practices

- Check `bd ready` at session start to find available work
- Update status as you work (in_progress → closed)
- Create new issues with `bd create` when you discover tasks
- Use descriptive titles and set appropriate priority/type, and dependencies between related items
- Always `bd sync` before committing
- Commit between finishing one beads issue and starting another
- Use jj commit with a description, don't use jj new with a description
- Add comments to newly created methods so their purpose is easy to understand later

