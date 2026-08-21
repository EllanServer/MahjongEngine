# Third-Party Notices

MahjongPaper-authored source is offered under the root MIT license. Third-party libraries and assets retain their own licenses.

## Resolved for the thin plugin at runtime

The plugin JAR does not embed third-party classes or nested JARs. Paper's plugin loader downloads these libraries from their Maven repositories into the server `libraries/` cache:

- HikariCP (Apache-2.0).
- H2 Database Engine (MPL-2.0 or EPL-1.0).
- MariaDB Connector/J (LGPL-2.1-or-later).
- MySQL Connector/J (GPL-2.0 with the Universal FOSS Exception).
- AntiGriefLib 1.0.17 (MIT).
- Sparrow MiniMessage 0.5 (MIT-derived from Adventure), Sparrow Reflection 0.34 and Sparrow YAML 1.0.12. Sparrow Reflection and Sparrow YAML are GPL-3.0.
- ASM (BSD-3-Clause) and transitive libraries declared by those artifacts.

Available license and notice texts remain packaged under `META-INF/licenses`. Operators and redistributors using GPL-covered runtime components must satisfy the applicable source and notice obligations. This notice is not legal advice.

## Included resources

- Shared Mahjong table/tile resource models derived from MahjongCraft and the visual sources listed in `resourcepack/ATTRIBUTION.md`; that attribution is packaged in the plugin JAR.

## Supplied separately by the server operator

- Paper/Folia.
- CraftEngine 26.8 (pinned internal API line).
- The signed Riichi, MCR and Sichuan pure rule JARs and their separately signed resource ZIPs. Each rule resource ZIP carries its own sound attribution and terms.

No GB-Mahjong native source/library, `mahjong-utils`, Kotlin runtime or InvSync is distributed by MahjongPaper 2.0.
