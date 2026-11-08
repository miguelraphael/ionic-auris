---
name: Auris Dark
description: Apple Music-inspired dark presentation for native Android media
colors:
  background: "#000000"
  surface: "#1C1C1E"
  surface-high: "#2C2C2E"
  surface-highest: "#3A3A3C"
  accent: "#F0F0F2"
  active-playback: "#BCCBBB"
  foreground: "#F5F5F7"
  secondary: "#A1A1A6"
typography:
  display:
    fontFamily: SF Pro
    fontSize: 34sp
    fontWeight: 700
    lineHeight: 41sp
  body:
    fontFamily: SF Pro
    fontSize: 17sp
    fontWeight: 400
    lineHeight: 24sp
rounded:
  artwork: 12dp
  artwork-large: 16dp
  artwork-small: 8dp
  button: 12dp
  sheet: 24dp
---

## Overview

User-approved direction: Apple Music-inspired styling, dark mode only. Native Android behavior remains authoritative. Artwork, clear titles, and readable metadata lead; neutral chrome supports them. Auris retains its name and multicolor logo; interactive chrome is neutral.

## Colors

Use black for the app base and opaque charcoal for grouped surfaces. Material container roles provide increasing neutral elevation. Use off-white for primary actions, sliders, links, and navigation selection. Reserve muted sage for enabled playback modes, with subtle tinted backgrounds. Keep warning and error colors semantic. Missing artwork uses neutral backgrounds rather than a blue fallback. Artwork contributes a restrained tint behind content. The shared backdrop has no decorative brand sweep; specialized legacy screens may still call BrandGlow directly.

## Typography

Reuse the bundled SF Pro family and Material typography roles. Large titles are 34sp/41sp bold; medium displays 28sp/36sp. Body large is 17sp/24sp; body medium 15sp/21sp. Avoid new per-screen type scales. All text remains scalable in sp.

## Layout

Retain existing media journeys and screen structure. Full-width controls share alignment with their content. Floating navigation uses 20dp horizontal insets, a 64dp bar, and 11sp labels. Preserve system insets and Android Back behavior. Interactive targets remain at least 48dp.

## Elevation & Depth

Prefer neutral surface steps over glowing decoration. The shared panel helper retains a subtle 1dp rim; its default upper and lower fills are both opaque charcoal. Artwork backgrounds fade into black with reduced saturation. No new blur dependency.

## Shapes

Artwork uses 12dp corners, larger covers 16dp, compact artwork 8dp. Buttons and chips use 12dp; sheets 24dp. Transport buttons remain circular and navigation remains a pill.

## Components

Shared theme and component tokens apply across the app. Search and segmented controls use neutral grouped surfaces. Home offers equally styled watch/listen/read actions. Player metadata and transport remain prominent. Preserve loading, empty, retry, selection, and disabled states.

Now Playing uses a 240ms cover scale transition between 96% (paused) and 100% (playing), plus a 150ms transport-state crossfade. Shared press feedback compresses over 100ms and releases over 150ms without bounce. Compose animations honor Android duration scaling; zero scale settles immediately. No decorative motion loops.

On shorter player viewports (available height below 700dp), identify the track before showing the smaller cover. Keep close, utility, transport, and volume controls outside the scrollable artwork/metadata area. Artist and album occupy independent two-line text blocks. Library and episode progress indicators are passive, never seek controls.

## Do's and Don'ts

- Use artwork as the visual subject and keep controls legible above it.
- Preserve native Android navigation, semantics, and accessible touch areas.
- Keep factual media metadata and playback behavior intact.
- Avoid introducing Liquid Glass effects or a light theme without a new request.
- Treat emulator fixtures as layout evidence, not live content or playback proof.
