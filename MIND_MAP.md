# Icehouse Project Mind Map

> **For AI Agents:** This file is the project knowledge index. Start with overview nodes [1-5], then follow references [N] to the subsystem you are changing. Keep node IDs stable, update stale nodes in the same commit as code changes, and add new nodes only when genuinely new concepts appear.

[1] **Project Snapshot** - Icehouse is a real-time multiplayer browser game (Looney Pyramids) with a Clojure backend, ClojureScript frontend, WebSocket transport, canvas rendering, and EDN replay storage [2][3][4][5].
[2] **Backend Surface** - `src/clj/icehouse/server.clj` serves static assets and `/ws`, while gameplay services live in lobby/game/websocket/storage namespaces and shared logging config [1][6][7][8][9][31].
[3] **Frontend Surface** - `src/cljs/icehouse/core.cljs` mounts Reagent UI, connects WebSocket, and switches between lobby/game/replay views based on shared state atoms [1][10][11][12].
[4] **Shared Domain Layer** - `src/cljc/icehouse/*` holds protocol constants, message IDs, geometry, game logic, schemas, and utility helpers consumed by both backend and frontend [1][13][14][15].
[5] **Operational Workflow** - Project workflow uses Beads (`bd`) for issue tracking and Jujutsu (`jj`) for commits; sync issues before commit and keep one issue per commit [1][28][29].
[6] **Server Entry Point** - `server.clj` defines Ring/Compojure routes (`/`, `/ws`), starts http-kit on port 3000, and exposes reset helpers for test/dev state cleanup [2][7][18].
[7] **WebSocket Router (Backend)** - `src/clj/icehouse/websocket.clj` parses JSON, validates `ClientMessage`, dispatches by message type, and emits structured errors for invalid/unknown inputs [2][14][16].
[8] **Lobby Management** - `src/clj/icehouse/lobby.clj` tracks room membership, names/colours/ready flags, room options, and game start coordination plus disconnect handling [2][17][18].
[9] **Game Module Facade** - `src/clj/icehouse/game.clj` owns `games` atom and re-exports functions from split modules (`game-state`, `game-rules`, `game-targeting`, `game-handlers`, `game/*` helpers for validators/mutations/query/replay) [2][19][20][21][22].
[10] **Frontend Entry/App Shell** - `core.cljs` initializes WebSocket connection, renders connection status, and chooses `lobby-view`, `game-view`, or `replay-view` from `current-view` and replay atoms [3][11][12].
[11] **Client State Model** - `src/cljs/icehouse/state.cljs` defines Reagent atoms for player/session/game/UI/replay and named transitions (`start-game!`, `start-replay!`, `show-game-list!`) [3][10][12].
[12] **Frontend Network Adapter** - `src/cljs/icehouse/websocket.cljs` validates incoming/outgoing schema messages, routes server message types to state mutations, and reconnects automatically [3][7][11][14].
[13] **Message Taxonomy** - `src/cljc/icehouse/messages.cljc` centralizes message type/error code constants used by backend dispatch and frontend handler maps [4][7][12].
[14] **Schema Contracts** - `src/cljc/icehouse/schema.cljc` defines Malli schemas (`ClientMessage`, `ServerMessage`, `GameState`, `ReplayState`, etc.) used as guardrails at both boundaries [4][7][11][12][27].
[15] **Geometry and Rules Primitives** - `src/cljc/icehouse/geometry.cljc` and `game_logic.cljc` provide shape/pip/icing calculations reused by game validation, gameplay rendering hints, and replay analysis [4][20][22][24].
[16] **Message Flow (Runtime)** - Browser opens `/ws`, sends `join` and user actions, backend validates and mutates room/game state, then broadcasts canonical updates (`players`, `game-start`, `piece-placed`, `game-over`) [7][8][9][12][13].
[17] **Lobby Options and Defaults** - Room options include `:icehouse-rule`, `:timer-enabled`, `:timer-duration`, and `:placement-throttle`; options broadcast to all room members before and during game setup [8][11][12].
[18] **Start Conditions and Disconnect Semantics** - Ready-state checks trigger `game/start-game!`; disconnects remove player, notify room, and end active game to avoid stuck sessions [6][8][9][16].
[19] **Game State Construction** - `game-state.clj` defines board dimensions, initial stash counts, timers, player ID derivation, and game creation/validation helpers [9][14][20][22].
[20] **Placement/Capture Validation** - `src/clj/icehouse/game/validators.clj` composes placement and capture checks (bounds, overlap, first-two-defensive, line-of-sight, range, icing/capture limits) and returns structured rule errors; `game-handlers.clj` orchestrates websocket flow [9][15][21][22].
[21] **Targeting Algorithms** - `game-targeting.clj` computes potential targets, closest valid targets, in-range checks, and blocked line-of-sight used by both validation and auto-target assignment [9][20][22].
[22] **Game Lifecycle and End Conditions** - `game-rules.clj` and handlers compute icehouse status, scoring, finish conditions (all placed, all finished, timer/disconnect), and final record payloads [9][15][20][24].
[23] **Canvas Game UI** - `src/cljs/icehouse/game.cljs` and `src/cljs/icehouse/game/*` own board rendering, hover/drag interactions, placement previews, capture visuals, and keyboard-driven controls [3][11][15].
[24] **Replay Pipeline** - Backend persists completed records; frontend replay reconstructs board by replaying moves and renders snapshots with timeline controls and speed settings [9][15][25][26].
[25] **Persistence Layer** - `src/clj/icehouse/storage.clj` stores records under `data/games/*.edn`, validates UUID IDs, enforces max record size, validates schema on load/save, and lists available game IDs [2][14][24].
[26] **Replay UI Surface** - `src/cljs/icehouse/replay.cljs` provides saved game list, load/refresh actions, move stepping, autoplay, and end-state summary rendering [3][12][24].
[27] **Test Coverage Map** - Backend tests cover game handlers, lobby, websocket, storage, schema, and integrations; frontend tests cover state/websocket/game/geometry/utils behavior [14][20][23][25].
[28] **Build and Run Commands** - Dev loop: `clojure -M:run` (backend) + `npx shadow-cljs watch app` (frontend); tests via `make test` or `clojure -M:test`; production jar via `make uberjar` [1][2][3].
[29] **Agent Guardrails** - Prefer `bd ready` at session start, update issue status as work progresses, run quality gates for code changes, and commit with `jj commit -m` after syncing issues [5][27][28].
[30] **Known Hotspots** - Current hotspots include lobby start-condition behavior (dev-mode minimum players) and broad schema error payload verbosity in logs [8][9][20][29][31].
[31] **Backend Logging** - `src/clj/icehouse/logging.clj` configures Timbre with `ICEHOUSE_LOG_LEVEL`; backend namespaces now log with structured levels (`info/warn/error`) instead of raw `println` [2][6][7][9][25].
