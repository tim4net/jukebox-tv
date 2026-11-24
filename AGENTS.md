# AGENTS.md: rules for agents working on jukebox-tv

## Testing and correctness rules (from Nonc Bob's reviews of PRs #10-#13)

- **Use monotonic clocks for elapsed time.** Measure durations and ages with `SystemClock.elapsedRealtime()` (or `System.nanoTime()` in pure-JVM code), never `System.currentTimeMillis()`. Wall-clock time is only for comparing against server timestamps.
- **Leave no static test state behind.** Any test that touches static or singleton state (for example `JukeboxRoom`, `JukeboxUpdateCard.sVisibleCard` or presenter instances) must reset it in `@After`. Tests must pass in any order.
- **Every resume or dedupe path needs an exactly-once test.** Any code that opens, resumes, re-offers or deduplicates (video opens, install offers, card shows) needs a test proving the action happens exactly once, not "at least once".
