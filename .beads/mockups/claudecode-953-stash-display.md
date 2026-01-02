# Mockup: Unplayed Pieces Display (claudecode-953)

## Current Layout
```
┌─────────────────────────────────────────────────────────┐
│                      Icehouse                           │
├─────────────────────────────────────────────────────────┤
│  Size: [small] [medium] [large]   Mode: [Defend] [Attack]│
├─────────────────────────────────────────────────────────┤
│                                                         │
│              ┌─────────────────────────┐                │
│              │                         │                │
│              │      800 x 600          │                │
│              │      Game Board         │                │
│              │       (canvas)          │                │
│              │                         │                │
│              └─────────────────────────┘                │
│                                                         │
│              Your pieces: Small: 5 | Medium: 5 | Large: 5│
└─────────────────────────────────────────────────────────┘
```

## Proposed Layout
```
┌──────────────────────────────────────────────────────────────────────────┐
│                              Icehouse                                     │
├──────────────────────────────────────────────────────────────────────────┤
│  Size: [small] [medium] [large]     Mode: [Defend] [Attack]              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────┐    ┌─────────────────────────────────┐    ┌──────────┐    │
│  │ Player 1 │    │                                 │    │ Player 2 │    │
│  │  (red)   │    │                                 │    │  (teal)  │    │
│  │          │    │                                 │    │          │    │
│  │  ▲ ▲ ▲   │    │                                 │    │  ▲ ▲ ▲   │    │
│  │  ▲ ▲ ▲   │    │         Game Board              │    │  ▲ ▲ ▲   │    │
│  │  ▲ ▲ ▲   │    │         800 x 600               │    │  ▲ ▲ ▲   │    │
│  │  ▲ ▲     │    │                                 │    │  ▲ ▲     │    │
│  │  ▲       │    │                                 │    │  ▲       │    │
│  │          │    │                                 │    │          │    │
│  └──────────┘    └─────────────────────────────────┘    └──────────┘    │
│                                                                          │
│  ┌──────────┐                                           ┌──────────┐    │
│  │ Player 3 │                                           │ Player 4 │    │
│  │ (yellow) │                                           │  (mint)  │    │
│  │  ▲ ▲ ▲   │                                           │  ▲ ▲ ▲   │    │
│  │  ▲ ▲ ▲   │                                           │  ▲ ▲ ▲   │    │
│  │  ▲ ▲ ▲   │                                           │  ▲ ▲ ▲   │    │
│  │  ▲ ▲     │                                           │  ▲ ▲     │    │
│  │  ▲       │                                           │  ▲       │    │
│  └──────────┘                                           └──────────┘    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

## Stash Panel Detail
```
┌─────────────────┐
│   Player Name   │
│    (colour)     │
├─────────────────┤
│  Large (5):     │
│   ▲  ▲  ▲  ▲  ▲ │
│                 │
│  Medium (5):    │
│  ▲ ▲ ▲ ▲ ▲     │
│                 │
│  Small (5):     │
│ ▲▲▲▲▲           │
└─────────────────┘

After playing some pieces:
┌─────────────────┐
│     Alice       │
│     (red)       │
├─────────────────┤
│  Large (3):     │
│   ▲  ▲  ▲       │
│                 │
│  Medium (4):    │
│  ▲ ▲ ▲ ▲       │
│                 │
│  Small (2):     │
│ ▲▲               │
└─────────────────┘
```

## Implementation Notes

1. **Layout**: Use CSS flexbox/grid to position stash panels on left/right of canvas
2. **Rendering**: Could render stashes on canvas OR as separate DOM elements
3. **Sizing**: Stash panels ~120px wide, pyramids scaled down from board size
4. **Colors**: Each player's stash uses their chosen color
5. **Interactivity**: Clicking a piece in stash could select that size