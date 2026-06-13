# thuruummmm

> **Build by feel, crash on purpose** — a haptics-first tower game where five-finger gestures mint the bricks and the collapse is the whole reward.

A building game for Android, played in landscape with both hands. You don't tap buttons — you make **five-finger gestures** that mint **bricks**, stack them under a real physics engine, and chase the one thing every other block game punishes: **the collapse.** Here, the collapse is the point.

Built for the 8x Mobile App Hackathon, 2026-06-13.

## How it plays

- Hold the phone in landscape, both hands on the glass.
- A **gesture** mints a brick — *gather* (**tappy**), *twist into a smaller circle* (**twisty**), or *swipe* in one of seven directions (**swipey**). Every gesture ends in a uniform **flourish** that commits it.
- A physics engine decides whether your structure holds — support, load, cantilevers, and a chain-reaction collapse.
- You play **by feel.** The haptics carry it: a two-beat *thur · rummmm* on every placement, and a big **THRUUMMMM** when it all comes down. Glance at the screen now and then; your thumbs do the rest.
- Harder gestures mint stronger materials. Build something tangled and ambitious — and the collapse rings like a bell.

No score. No fail state. No tutorial. You discover the gestures by hand.

## Stack

Native Android — Kotlin 2.4, Jetpack Compose, a pure-Kotlin physics engine, and the raw `Vibrator` API for custom single-motor haptics. Single activity, Compose `Canvas` + a `withFrameNanos` game loop.

## Run it

Needs a real Android device — the haptics cannot be felt on an emulator.

```bash
./gradlew installDebug
adb shell am start -n com.thrum/.MainActivity
```

*Hackathon MVP — actively under construction.*
