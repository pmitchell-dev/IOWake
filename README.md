<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# IOWake — Cognitive Discipline Alarm Clock

IOWake is a premium, accountability-enforcing alarm clock application built natively for Android. Designed specifically for heavy sleepers, IOWake ensures cognitive wakefulness by requiring users to solve mental puzzles before an alarm can be dismissed, backed by customizable snooze restrictions and decaying interval penalties.

---

## 🌟 Key Features

### 1. Interactive Cognitive Missions (Puzzles)
Choose from three types of waking missions to trigger your brain's logic centers:
*   **Math Mission**: Solve consecutive arithmetic equations.
    *   *Easy*: Double-digit addition/subtraction (with an optional addition-only mode).
    *   *Medium*: Double-digit multiplication combined with order-of-operations (e.g., `(14 × 4) + 19`).
    *   *Hard*: Multi-step equations and double multiplications (e.g., `(16 × 6) + (14 × 3)`).
*   **Memory Mission**: A classic card-matching memory matrix using emojis.
    *   *Easy*: 3 pairs (6 cards)
    *   *Medium*: 4 pairs (8 cards)
    *   *Hard*: 6 pairs (12 cards)
*   **Sequence Mission**: A pattern recall game ("Simon Says") that tests short-term visual memory.
    *   *Easy*: Sequence of 3
    *   *Medium*: Sequence of 4
    *   *Hard*: Sequence of 6

### 2. Strict Anti-Snooze Safeguards
Prevent oversleeping with customizable accountability rules:
*   **Snooze Lockdown**: Disable snoozing entirely, forcing you to solve the puzzle to silence the alarm.
*   **Snooze Limit**: Restrict the total number of times you are allowed to snooze (configurable).
*   **Decaying Snooze Intervals (Penalty Mode)**: For every snooze, the interval dynamically shrinks (e.g., `10 minutes -> 5 minutes -> 3 minutes...`), accelerating urgency until the snooze button locks out completely.

### 3. Advanced Audio & Ringer Engine
*   **Procedural Sound Synthesis**: Features custom synthesizers utilizing the low-level Android `AudioTrack` API to generate high-fidelity alerts on the fly:
    *   *Classic Digital Beep*: A piercing 1200Hz digital pulse sequence.
    *   *Gentle Chimes*: Decaying arpeggios using physical/harmonic resonance simulation.
    *   *Laser Siren*: A rapid frequency-sweep siren designed to prevent habituation.
*   **Volume Ramping**: Gradually increases volume from 10% to 100% in 15% steps every 5 seconds to reduce morning shock.
*   **System Ringtone Support**: Option to select built-in system alarms, ringtones, or notification audio.
*   **Continuous Physical Vibration**: Strong haptic pulses that run in tandem with audio streams.

### 4. Premium Compose Dashboard
*   Sleek dark-theme dashboard featuring a live-ticking clock, arm status indicators, and recurrence organizers.
*   **Sandbox Practice Mode**: Instantly launch and test any puzzle type and difficulty level directly from the home screen without waiting for an alarm.

---

## 🛠️ Tech Stack

*   **UI Framework**: Android Jetpack Compose with custom Slate, Electric Teal, and Amber color styling.
*   **Database**: Room (SQLite) with clean Repository patterns for managing and persisting alarm entities.
*   **Concurrency**: Kotlin Coroutines and Flows for real-time background services and live-ticking UI clocks.
*   **Audio Pipeline**: Low-level `AudioTrack` PCM 16-bit Mono synthesis combined with Android `MediaPlayer`.
*   **Backgrounding**: Android Foreground Services to guarantee alarm delivery even under device doze modes.

---

## 🚀 Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio.
2. Select **Open** and choose the directory containing this project.
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the root directory and set `GEMINI_API_KEY` (see `.env.example` for reference).
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or a physical device.

---

View your app in AI Studio: https://ai.studio/apps/2605d050-3bae-447d-9a09-ba180e2bf667

