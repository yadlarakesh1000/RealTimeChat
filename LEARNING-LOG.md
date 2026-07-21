# Learning Log

Every time I'm stuck for more than 20 minutes, log: **symptom → hypothesis → what was
actually wrong → the concept I was missing.**

---

## Phase 0 — Environment and mental model

### Self-check answers

**Q: If a client sends `"hello"` then `"world"` in two fast writes, is the server
guaranteed to `read()` them as two separate reads?**

_(answer here)_

**Q: What does `accept()` return, and how is it different from the object you called
it on?**

_(answer here)_

**Q: What happens to the second client if the server is single-threaded and the first
client never disconnects?**

_(answer here)_

### Notes

- `maven.compiler.release=17` instead of `source`/`target` — `release` also checks the
  API surface against JDK 17, so code that compiles cannot call a method that only
  exists in a newer JDK. `source`/`target` only set language level and bytecode
  version, which is how you get `NoSuchMethodError` at runtime from a clean build.
- JavaFX intentionally left out of `pom.xml` until Phase 7.

---

<!-- Phase 1 entries go below -->
