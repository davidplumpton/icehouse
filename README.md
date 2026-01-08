# Icehouse

> Multiplayer browser-based Classic Icehouse (Looney Pyramids) game

## Overview

A real-time multiplayer implementation of Classic Icehouse for 3-4 players. Players connect via browser, join a lobby, and compete by placing pyramids to attack and defend.

## Features

- Real-time multiplayer via WebSockets
- Browser-based with HTML5 Canvas rendering
- Lobby system with player names and colours
- 3-4 player support
- Classic Icehouse rules (standing = defense, pointing = attack)

## Prerequisites

- Java 11+ (for Clojure backend)
- Node.js 16+ (for ClojureScript tooling)
- Clojure CLI tools (`brew install clojure/tools/clojure` on macOS)
- [Jujutsu (jj)](https://martinvonz.github.io/jj/) for version control
- [Beads (bd)](https://github.com/mads-hartmann/beads) for issue tracking

## Development Workflow

### Issue Tracking
This project uses **Beads (bd)** for issue tracking.
```bash
bd onboard            # Get started
bd ready              # Find available work
bd update <id> --status in_progress  # Claim work
bd close <id>         # Complete work
bd sync               # Sync issues with git
```

### Version Control
This project uses **Jujutsu (jj)**. Prefer `jj` commands over standard `git`.
```bash
jj commit -m "description"  # Record changes
jj status                   # Check current state
```

## Installation

```bash
# Install shadow-cljs globally
npm install -g shadow-cljs

# Download Clojure dependencies
clojure -P
```

## Running the App

You need two terminal windows:

**Terminal 1 - Backend server:**
```bash
clojure -M:run
```
This starts the WebSocket server on port 3000.

**Terminal 2 - Frontend dev build:**
```bash
npx shadow-cljs watch app
```
This compiles ClojureScript and watches for changes.

Once both are running, open http://localhost:3000 in your browser.

## Running Tests

### Backend (Clojure)
Run the backend test suite:
```bash
make test
# or
clojure -M:test
```

### Frontend (ClojureScript)
The frontend tests run in the browser.

1. Start the test watcher:
   ```bash
   npx shadow-cljs watch test
   ```
2. Open **http://localhost:8022** in your browser.
3. Check the browser console for detailed test output.

You can also watch both the app and tests simultaneously:
```bash
npx shadow-cljs watch app test
```

## Project Structure

```
src/
  clj/icehouse/       # Clojure backend
    server.clj        # HTTP + WebSocket server
    websocket.clj     # WebSocket message handling
    lobby.clj         # Lobby/room management
    game.clj          # Game state and rules
  cljs/icehouse/      # ClojureScript frontend
    core.cljs         # App entry point
    state.cljs        # Client state atoms
    websocket.cljs    # WebSocket client
    lobby.cljs        # Lobby UI
    game.cljs         # Game canvas UI
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

See [LICENSE](LICENSE) for details.
