# Auris

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Anime and music enthusiasts are the primary audience. Auris welcomes everyone, including people exploring unfamiliar titles, artists, or manga; navigation and terminology should not require fandom knowledge.

## Product Purpose

Auris brings anime watching, music listening, and manga reading into one native Android app. Help people find something they want, start watching, listening, or reading, and return to their place later.

The confirmed priorities, in order, are reliable anime/music playback and manga reading, clear navigation, and discovery. Reliability is a product goal, not a claim that every source or playback path currently works.

## Operating Context

The current app uses Kotlin, Jetpack Compose, and Material 3. Its top-level destinations are Home, Anime, Manga, Music, and Library. Supporting flows include media details, catalogue search and source selection, playback, manga reading, settings, and extensions.

Core journeys are discovering or searching for media, opening its details, choosing an episode/chapter/track, consuming it, and resuming from saved library progress. Music also has album, artist, and queue flows.

These are implementation observations, not evidence of device-tested completeness.

## Capabilities and Constraints

- Anime and manga catalogues use source/provider integrations; music has catalogue and stream-resolution integrations. Availability depends on external services. Show actionable loading and failure states rather than implying unavailable content is playable.
- The app has local library/progress storage and background synchronization infrastructure. Local progress and network content availability are separate concerns; local storage does not imply offline media availability.
- Playback uses Android Media3; music has a media-session-backed player, and anime uses an episode player. Manga has chapter/page reading and retry handling.
- Preserve native Android interaction expectations when future changes are authorized. Existing code is the implementation baseline, not authorization to redesign it.
- This initialization establishes product context only. It does not change app code, redesign screens, or establish a new visual system.

## Brand Commitments

The product name is Auris. Address enthusiasts while welcoming newcomers. Confirmed visual preference: Apple Music-inspired presentation, dark mode only, while preserving native Android behavior and Auris identity. Subsequent color refinement favors neutral controls and artwork-led color, replacing blue action fills. No additional voice or competitive differentiation has been confirmed.

## Evidence on Hand

- `README.md` describes the product and architecture; verify implementation details against current code because some status notes are historical.
- `app/src/main/java/com/auris/app/ui/navigation/` provides destination and route evidence.
- `app/src/main/java/com/auris/app/ui/` contains the current catalogue, library, music, player, and reader implementations.
- `UI/` contains existing visual reference assets; their presence does not establish the current rendered result.

No new runtime verification or performance measurements were performed for this context initialization. Do not invent reliability metrics, testimonials, or market claims.

## Product Principles

1. Make watching, listening, and reading dependable before adding discovery or presentation complexity.
2. Keep destinations and next actions clear, including routes back to media and saved progress.
3. Help people discover titles and music with an understandable path from discovery to consumption.
4. Welcome enthusiasts and newcomers with understandable labels and useful feedback.

## Accessibility & Inclusion

Welcoming everyone is a confirmed product commitment. Apply native Android accessibility guidance in future UI work, including readable scalable text, meaningful control labels, adequate touch targets, and system navigation support. These are implementation expectations, not a claim of audited compliance. No specific certification target or additional audience requirements have been established.

## Open Decisions

Distinctive market positioning, formal success metrics, and further brand commitments remain undecided. Future work should not infer them from the existing aesthetic.
