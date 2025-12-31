# Icehouse Architecture

A real-time multiplayer browser implementation of Classic Icehouse (Looney Pyramids) for 3-4 players.

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Browser (ClojureScript)                   │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │  Lobby  │  │  Game   │  │ Canvas  │  │ Replay  │            │
│  │   UI    │  │ State   │  │Renderer │  │ System  │            │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘            │
│       └────────────┴────────────┴────────────┘                  │
│                          │                                       │
│                   ┌──────┴──────┐                                │
│                   │  WebSocket  │                                │
│                   │   Client    │                                │
│                   └──────┬──────┘                                │
└──────────────────────────┼──────────────────────────────────────┘
                           │ JSON/WebSocket
┌──────────────────────────┼──────────────────────────────────────┐
│                   ┌──────┴──────┐                                │
│                   │  WebSocket  │         Server (Clojure)       │
│                   │   Router    │                                │
│                   └──────┬──────┘                                │
│       ┌──────────────────┼──────────────────┐                   │
│  ┌────┴────┐       ┌─────┴─────┐      ┌─────┴─────┐             │
│  │  Lobby  │       │   Game    │      │  Storage  │             │
│  │ Manager │       │  Engine   │      │  (EDN)    │             │
│  └─────────┘       └───────────┘      └───────────┘             │
└─────────────────────────────────────────────────────────────────┘
```

## Project Structure

```
src/
├── clj/icehouse/           # Backend (Clojure)
│   ├── server.clj          # HTTP + WebSocket server
│   ├── websocket.clj       # Message routing
│   ├── lobby.clj           # Room & player management
│   ├── game.clj            # Game logic & rules
│   ├── storage.clj         # Game persistence
│   └── utils.clj           # Messaging utilities
└── cljs/icehouse/          # Frontend (ClojureScript)
    ├── core.cljs           # App entry point
    ├── state.cljs          # Reagent atoms
    ├── websocket.cljs      # WebSocket client
    ├── lobby.cljs          # Lobby UI
    ├── game.cljs           # Canvas rendering
    ├── replay.cljs         # Game replay
    ├── theme.cljs          # Colors
    └── utils.cljs          # Utilities
```

## Technology Stack

| Layer | Technology |
|-------|------------|
| Frontend | ClojureScript, Reagent, HTML5 Canvas |
| Backend | Clojure, http-kit, Ring, Compojure |
| Transport | WebSocket (JSON) |
| Storage | EDN files |
| Build | shadow-cljs, deps.edn |

---

## Backend Architecture

### Server (`server.clj`)

- Runs on port 3000
- Routes: `/ws` (WebSocket), `/` (static files)
- Entry point: `-main`

### WebSocket Router (`websocket.clj`)

Routes messages to handlers based on `type` field:

| Type | Handler |
|------|---------|
| `join` | `lobby/handle-join` |
| `set-name` | `lobby/handle-set-name` |
| `set-colour` | `lobby/handle-set-colour` |
| `set-option` | `lobby/handle-set-option` |
| `ready` | `lobby/handle-ready` |
| `place-piece` | `game/handle-place-piece` |
| `capture-piece` | `game/handle-capture-piece` |
| `list-games` | `game/handle-list-games` |
| `load-game` | `game/handle-load-game` |

### Lobby Manager (`lobby.clj`)

- Manages players joining/leaving rooms
- Assigns default names and colors
- Tracks ready status
- Broadcasts player list and game options
- Starts game when all players ready (3-4 players)

### Game Engine (`game.clj`)

Core game logic including:
- Piece placement validation
- Collision detection (Separating Axis Theorem)
- Attack mechanics (ray casting, range checking)
- Icing calculation (attacker pips > defender pips)
- Scoring and game termination
- Move history for replay

### Storage (`storage.clj`)

Saves completed games to `data/games/{game-id}.edn` for replay functionality.

---

## Frontend Architecture

### State Management (`state.cljs`)

Reagent atoms organized by concern:

```clojure
;; Player identity
current-player     ; {:id :name :colour}

;; Session
current-view       ; :lobby or :game
ws-status          ; :connecting, :connected, :disconnected
players            ; List of players in room
game-options       ; {:icehouse-rule :timer-enabled}

;; Game
game-state         ; Full game state from server
game-result        ; Final scores when game ends
current-time       ; For timer display

;; UI interaction
ui-state           ; {:selected-piece :drag :hover-pos :zoom-active :show-help}

;; Replay
replay-state       ; {:record :current-move :playing? :speed}
game-list          ; Saved game IDs
```

### Canvas Rendering (`game.cljs`)

- 1000x750 pixel play area
- 50px grid overlay
- Piece rendering (squares for standing, triangles for pointing)
- Real-time preview during placement
- Attack range visualization
- Capture highlighting

### Keyboard Controls

| Key | Action |
|-----|--------|
| 1/2/3 | Select piece size |
| A/D | Attack/Defend mode |
| C | Capture piece / toggle captured mode |
| Z | Toggle 4x zoom |
| Shift+Drag | Fine position adjustment |
| ? | Toggle help |
| Escape | Cancel action |

---

## WebSocket Protocol

### Client to Server

```json
// Lobby
{"type": "join"}
{"type": "set-name", "name": "Alice"}
{"type": "set-colour", "colour": "#ff6b6b"}
{"type": "ready"}
{"type": "set-option", "key": "icehouse-rule", "value": true}

// Game
{"type": "place-piece", "x": 500, "y": 375, "size": "small",
 "orientation": "standing", "angle": 0, "target-id": null, "captured": false}
{"type": "capture-piece", "piece-id": "uuid"}

// Replay
{"type": "list-games"}
{"type": "load-game", "game-id": "uuid"}
```

### Server to Client

```json
// Session
{"type": "joined", "player-id": "hash", "room-id": "default",
 "name": "Alice", "colour": "#ff6b6b"}
{"type": "players", "players": [...]}
{"type": "options", "options": {...}}

// Game
{"type": "game-start", "game": {...}}
{"type": "piece-placed", "game": {...}}
{"type": "piece-captured", "game": {...}}
{"type": "game-over", "scores": {...}, "icehouse-players": [...], "over-ice": {...}}

// Replay
{"type": "game-list", "games": ["uuid1", ...]}
{"type": "game-record", "record": {...}}

// Error
{"type": "error", "message": "..."}
```

---

## Data Structures

### Game State

```clojure
{:game-id "uuid"
 :room-id "default"
 :players {"player-id" {:name "Alice"
                        :colour "#ff6b6b"
                        :pieces {:small 5 :medium 5 :large 5}
                        :captured [{:size :small :colour "#..."}]}}
 :board [{:id "uuid"
          :player-id "player-id"
          :colour "#ff6b6b"
          :x 500 :y 375
          :size :small
          :orientation :standing  ; or :pointing
          :angle 0.5              ; radians
          :target-id "uuid"}]     ; for attacking pieces
 :moves [...]                     ; move history
 :options {:icehouse-rule true :timer-enabled true}
 :started-at 1234567890
 :ends-at 1234567950000}          ; nil if no timer
```

### Piece Geometry

| Type | Shape | Vertices |
|------|-------|----------|
| Standing | Square with X | 4 corners at half-size |
| Pointing | Triangle | Tip at 0.75×base, two corners at rear |

Sizes: small=40px, medium=50px, large=60px (base width)

---

## Coordinate System

```
(0,0) ────────────────────────► X (1000)
  │
  │    Canvas coordinate system
  │    Origin at top-left
  │
  ▼
  Y (750)
```

- Pieces positioned by center point
- Collision detection uses polygon vertices
- Angles in radians (0 = right, π/2 = down)

---

## Game Rules

### Placement
1. Click and drag to place piece with rotation
2. Must be within play area bounds
3. Cannot overlap existing pieces
4. Attacking requires valid target in range

### Attack Mechanics
- Pointing pieces attack in their direction
- Attack range = piece height (2 × 0.75 × base-size)
- Target must be opponent's standing piece
- No pieces can block the attack ray

### Icing
- Defender is "iced" when total attacker pips > defender pips
- Pips: small=1, medium=2, large=3
- "Over-iced" when excess pips > 0 (can capture attackers)

### Icehouse Rule
- If enabled and player has 8+ pieces placed
- AND all their defenders are iced
- Player scores 0 points

### Scoring
- Sum of pips from uniced standing pieces
- Icehouse players get 0

---

## Key Design Decisions

1. **Server-authoritative**: All validation on backend, client shows previews only
2. **Immediate broadcast**: State changes sent to all players instantly
3. **Duplicated geometry**: Same calculations client and server for preview accuracy
4. **Move history**: Full replay from recorded moves, not snapshots
5. **Cached calculations**: Iced pieces pre-computed to avoid per-frame recalculation
