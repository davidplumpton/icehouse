# Malli Schema Validation

This document describes the schema validation system added to the Icehouse codebase using [Malli](https://github.com/metosin/malli).

## Overview

Malli schemas are defined in `src/cljc/icehouse/schema.cljc` and shared between frontend (ClojureScript) and backend (Clojure). They provide:

- **Runtime validation**: Catch data structure errors at runtime
- **Self-documenting code**: Schemas serve as documentation of expected data shapes
- **Error messages**: Clear validation error messages when data is invalid
- **Type safety**: Validate data during development and in production

## Schema Definitions

### Core Data Structures

#### GameState
The complete game state, shared between server and clients:
```clojure
{:game-id "uuid"
 :room-id "default"
 :players {"player-id" {:name "Alice"
                        :colour "#ff6b6b"
                        :pieces {:small 5 :medium 5 :large 5}
                        :captured []}}
 :board [{:id "uuid"
          :player-id "player-id"
          :colour "#ff6b6b"
          :x 500 :y 375
          :size :small
          :orientation :standing
          :angle 0.5
          :target-id nil}]
 :moves []
 :options {:icehouse-rule true :timer-enabled true}
 :started-at 1234567890
 :ends-at 1234567950}
```

#### Piece
A pyramid on the board:
```clojure
{:id "uuid"
 :player-id "player-id"
 :colour "#ff6b6b"
 :x 500
 :y 375
 :size :small          ; :small, :medium, or :large
 :orientation :standing ; :standing or :pointing
 :angle 0.5            ; radians
 :target-id nil}       ; optional, for attacking pieces
```

#### Player
Player data within a game:
```clojure
{:name "Alice"
 :colour "#ff6b6b"
 :pieces {:small 5 :medium 5 :large 5}  ; remaining pieces
 :captured []}                           ; list of captured pieces
```

### WebSocket Messages

#### Client -> Server

- `JoinMessage`: `{:type "join"}`
- `SetNameMessage`: `{:type "set-name" :name "Alice"}`
- `SetColourMessage`: `{:type "set-colour" :colour "#ff6b6b"}`
- `SetOptionMessage`: `{:type "set-option" :key "icehouse-rule" :value true}`
- `ReadyMessage`: `{:type "ready"}`
- `PlacePieceMessage`: `{:type "place-piece" :x 500 :y 375 :size :small :orientation :standing :angle 0 :target-id nil :captured false}`
- `CapturePieceMessage`: `{:type "capture-piece" :piece-id "uuid"}`
- `ListGamesMessage`: `{:type "list-games"}`
- `LoadGameMessage`: `{:type "load-game" :game-id "uuid"}`

#### Server -> Client

- `JoinedMessage`: `{:type "joined" :player-id "uuid" :room-id "default" :name "Alice" :colour "#ff6b6b"}`
- `PlayersMessage`: `{:type "players" :players [...]}`
- `OptionsMessage`: `{:type "options" :options {...}}`
- `GameStartMessage`: `{:type "game-start" :game {...}}`
- `PiecePlacedMessage`: `{:type "piece-placed" :game {...}}`
- `PieceCapturedMessage`: `{:type "piece-captured" :game {...}}`
- `GameOverMessage`: `{:type "game-over" :scores {...} :icehouse-players [...] :over-ice {...}}`
- `ErrorMessage`: `{:type "error" :message "..."}`

### UI State

Frontend-only schema for UI interaction state:
```clojure
{:selected-piece {:size :small :orientation :standing :captured? false}
 :drag nil  ; or DragState for active dragging
 :hover-pos nil  ; or HoverPos for mouse position
 :zoom-active false
 :show-help false}
```

## Using Schemas in Code

### Backend (Clojure)

**Validate game state:**
```clojure
(require '[icehouse.schema :as schema])
(require '[malli.core :as m])

(if (m/validate schema/GameState game-state)
  (println "Valid game state")
  (println "Invalid game state:" (m/explain schema/GameState game-state)))
```

**Validate incoming messages (done automatically in websocket.clj):**
```clojure
(validate-incoming-message {:type "join"})  ; returns message or nil
```

**Validate outgoing messages (done automatically in utils.clj):**
```clojure
(validate-outgoing-message {:type "joined" :player-id "..." ...})  ; returns message or nil
```

### Frontend (ClojureScript)

**Validate incoming messages (done automatically in websocket.cljs):**
```clojure
(validate-incoming-message {:type "game-start" :game {...}})  ; returns message or nil
```

**Validate outgoing messages (done automatically):**
```clojure
(validate-outgoing-message {:type "place-piece" :x 500 ...})  ; returns message or nil
```

## Validation Workflow

### Request/Response Flow

```
Client                          Server
  │                              │
  ├─ validate outgoing ──────────┐
  │                         validate incoming
  │  receive                      │
  ├─────────────────────────────┤
  │  validate incoming           │
  │  process                     │
  │                    validate outgoing
  ├─ receive ──────────────────┤
  │  validate incoming          │
  │  update state               │
  │                              │
```

1. **Client sends message**: Validated before sending via `validate-outgoing-message`
2. **Server receives**: Validated in `websocket.clj` via `validate-incoming-message`
3. **Server processes**: Game state may be validated via `validate-game-state`
4. **Server sends response**: Validated before sending via `validate-outgoing-message` in `utils.clj`
5. **Client receives**: Validated in `websocket.cljs` via `validate-incoming-message`

## Error Handling

When validation fails:

**Backend**: Logs error details and sends `{:type "error" :message "Invalid message format"}` to client
**Frontend**: Logs error details and displays error message briefly to user

## Adding New Schemas

1. Define the schema in `src/cljc/icehouse/schema.cljc` using Malli syntax
2. Add it to the appropriate union type (e.g., `ClientMessage`, `ServerMessage`)
3. Write tests in `test/clj/icehouse/schema_test.clj`
4. Validation happens automatically in websocket handlers

## Performance Considerations

- Schemas are compiled once at runtime startup
- Validation is O(n) in the size of the data structure
- Overhead is minimal for typical message sizes (< 1KB)
- In production, disable verbose error logging for performance

## Debugging

Enable verbose logging to see all validation details:

```clojure
(require '[malli.core :as m])
(m/explain schema/GameState invalid-data)  ; returns detailed error info
```

Example output:
```
[:map
 [:game-id string?]
 [0 "failed to validate"]
 [:players map-of?]
 [1 "failed to validate"]]
```

## References

- [Malli Documentation](https://github.com/metosin/malli)
- [Clojure Data Validation](https://clojure.org/guides/math)
- [API Design with Schemas](https://clojure.org/guides/api_design)
