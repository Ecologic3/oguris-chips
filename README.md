# Oguri's Chips

Android app for live poker home games. Real cards are dealt on the table;
the app runs the chips: stacks, betting rounds, pots and settlement.
One phone in the middle, 2–6 players, money = chips 1:1.

## Games

- **Texas Hold'em** — classical no-limit: blinds, preflop, exact
  minimum-raise rules (a raise must be at least the size of the previous
  raise), heads-up rules, always one board.
- **Omaha Bomb Pots** — everyone antes, no blinds, no preflop. The flop is
  dealt and betting starts there, pot-limit, on 1–8 boards (default 2).
  The minimum opening bet is the ante. Chopped boards are allowed and any
  unsplittable remainder carries into the next pot automatically.

## Features

- Side pots and uncalled-bet returns handled by the engine
- Undo that steps back exactly one action per tap
- Manual save plus auto-save after every action; resume on launch
- Rebuys between hands, game switching between hands with stacks and
  seats carried over
- End-of-session standings with a who-pays-whom settlement

Player names are not stored anywhere outside the phone: the four default
seats start empty and are named by whoever uses the app.

## Building

No Gradle, no Android Studio: the app is plain Java built with the
manual aapt2 → javac → d8 pipeline in `build.sh`. It needs JDK 17 and
the Android SDK (platform android-34, build-tools 34.0.0). The script
generates a local signing keystore on first run (kept out of git).

```
./build.sh
```

Engine tests (pure JVM, no Android needed):

```
javac -encoding UTF-8 -d build-test app/src/dev/oguri/chips/engine/Engine.java tests/EngineTest.java
java -cp build-test EngineTest
```
