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

```bash
clojure -M:test
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
