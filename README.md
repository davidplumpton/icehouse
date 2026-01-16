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

## Installation

Requires Java and Node.js.

```bash
# Install ClojureScript dependencies
npm install -g shadow-cljs

# Install Clojure dependencies
clojure -P
```

## Usage

Start the backend server:
```bash
clojure -M:run
```

Start the ClojureScript dev build (in another terminal):
```bash
shadow-cljs watch app
```

Open http://localhost:3000 in your browser.

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
