# Contributing

MahjongPaper's portable core and rule SDK use Java 21. The Paper/CraftEngine adapters and final plugin use Java 25 for Paper 26.2. Gradle Kotlin DSL and the CraftEngine bundle generator are the only Kotlin build-time code.

Dependency direction:

```text
mahjong-plugin
  -> Paper / CraftEngine / SQL / rule-runtime adapters
  -> mahjong-application
       -> mahjong-domain + mahjong-rule-spi
```

Rules:

- Do not add game-specific rule types or implementations to the core repository.
- Do not add unbounded queues, per-table threads, blocking waits on a Paper/Folia region thread, or global per-tick table scans.
- Do not put secret tile faces in Bukkit/CraftEngine world entities.
- Prefer CraftEngine YAML for furniture models, variants, hitboxes, seats, interactions, and culling. Java should only coordinate state, security and diffs that configuration cannot express.
- Rule-pack code may not access Bukkit, files, network, clocks or threads through the SPI.
- All accepted actions must be persisted through the ordered outbox and must be recoverable from the last committed sequence.
- Build and test changes through GitHub Actions on branch `2.0`; the final plugin must remain Java 25, rule-neutral, and legacy-free, while the rule SDK stays Java 21.

The root `architectureCheck` task enforces the most important boundaries.
