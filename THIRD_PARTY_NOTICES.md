# Third-Party Notices

MahjongPaper's own code and project-created assets are licensed under the root [MIT License](./LICENSE). That license does not relicense third-party code, assets, recordings, names, or trademarks identified below.

## Material included in release artifacts

- **GB-Mahjong** by Zheng Fan is vendored and compiled into the GB rules JNI library under the MIT License. Copyright (c) 2019-2020 Zheng Fan `<i@fanzheng.org>`. The complete license is stored at `native/gbmahjong/vendor/GB-Mahjong/LICENSE` and packaged as `META-INF/licenses/GB-Mahjong-LICENSE.txt`.
- Mahjong tile textures and base item models are reused from **MahjongCraft** by `doublemoon1119` under the MIT License. Copyright (c) 2021 doublemoon1119. The complete notice is included in `resourcepack/ATTRIBUTION.md` and packaged as `META-INF/RESOURCEPACK_ATTRIBUTION.md`.
- Related tile-art provenance credits **mahjong_graphic** by `lietxia` and **I.Mahjong** by SyaoranHinata under the M+ Fonts License. Their documentation identifies GL-MahjongTile as earlier artwork/font lineage.
- Tile/table recordings are adapted from Freesound uploads `329098`, `329099`, and `329100` by **Macif**, `197868` by **Millavsb**, and `745024` by **poenia**, each marked CC0 1.0. File-level mappings and source URLs are in `resourcepack/ATTRIBUTION.md`.
- Riichi action voices are derived from `chii_01.wav`, `pon_01.wav`, `kan_01.wav`, `ri-chi_01.wav`, and `ron_01.wav` by **Amitaro's Voice Material Studio** under its custom terms, not MIT or CC0.
- Windows artifacts may include `libwinpthread-1.dll` from **mingw-w64 winpthreads**. Its MIT/BSD-style notice is packaged as `META-INF/licenses/winpthreads-COPYING.txt`.
- GCC runtime portions linked by the native build are licensed under GPL-3.0 with the GCC Runtime Library Exception 3.1: <https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html>.

Required Amitaro credit:

> Voice: Amitaro's Voice Material Studio (<https://amitaro.net/>)<br>
> 音声素材：あみたろの声素材工房 (<https://amitaro.net/>)

The Amitaro recordings may be redistributed only as part of a work such as this plugin/resource bundle and subject to the current terms at <https://amitaro.net/voice/voice_rule/>. Preserve the credit and terms link/readme, do not distribute the recordings as a standalone voice or sound pack, and complete any required post-release usage report.

## Separate platforms and runtime libraries

Paper, Folia, and CraftEngine are separate server components supplied by the server operator. Paper's plugin loader resolves mahjong-utils, MariaDB Connector/J, MySQL Connector/J, H2, HikariCP, Caffeine, Kotlin, and kotlinx.serialization as separate libraries under their respective upstream licenses. See the README and build files for links, versions, and license identifiers.

## Trademark and affiliation notice

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.** References to Mahjong Soul / 雀魂 describe rules or style only and do not imply affiliation, sponsorship, or endorsement. All names and trademarks belong to their respective owners.
