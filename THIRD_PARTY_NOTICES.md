# Third-Party Notices

MahjongPaper's own source code and project-created assets remain available under the root [MIT License](./LICENSE). That license does not relicense third-party code, assets, recordings, names, or trademarks identified below.

The runnable plugin links to GPL-3.0-only Sparrow YAML and embeds a relocated copy of GPL-3.0-only Sparrow Reflection. The combined runnable software distribution is therefore conveyed under GPL-3.0-only; the MIT grant remains an additional permission for MahjongPaper-authored portions, not an exception to the GPL terms that apply to the combined program. Anyone redistributing a binary must also satisfy the GPL corresponding-source and build-script requirements. The complete GPL text packaged with the jar is the governing text; this notice is not legal advice.

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

InvSync 2.x is an optional, separately supplied paid server plugin. It is not compiled into, bundled with, or redistributed by MahjongPaper. When present, MahjongPaper accesses only the public addon event API documented at <https://halo.xbaimiao.com/archives/invsynckai-fa-zhe-wen-dang>; when absent or incompatible, player rank storage falls back to MahjongPaper's existing database implementation.

The distribution also uses the following Xiao-MoMi ecosystem libraries as separate works. AntiGriefLib, sparrow-heart, and sparrow-yaml are resolved by Paper's plugin loader; sparrow-reflection and ASM are embedded under relocated package names to prevent plugin-classpath conflicts:

- **AntiGriefLib 1.0.14**, Copyright (c) 2024 XiaoMoMi, under the MIT License: <https://github.com/Xiao-MoMi/AntiGriefLib>.
- **sparrow-heart 0.72**, Copyright (c) 2024 XiaoMoMi, under the MIT License: <https://github.com/Xiao-MoMi/sparrow-heart>.
- **sparrow-reflection 0.33** under the GNU General Public License v3.0: <https://github.com/Xiao-MoMi/sparrow-reflection>. The complete license is stored at `third-party-licenses/sparrow-reflection-GPL-3.0.txt` and packaged as `META-INF/licenses/sparrow-reflection-GPL-3.0.txt`.
- **sparrow-yaml 1.0.7** under the GNU General Public License v3.0: <https://github.com/Xiao-MoMi/sparrow-yaml>. The same complete GPL text is also packaged as `META-INF/licenses/sparrow-yaml-GPL-3.0.txt`.
- **Mapping-IO 0.8.0** by FabricMC under the Apache License 2.0 is shaded inside Sparrow Reflection: <https://github.com/FabricMC/mapping-io>. The complete license is stored at `third-party-licenses/mapping-io-Apache-2.0.txt` and packaged as `META-INF/licenses/mapping-io-Apache-2.0.txt`.
- **ASM 9.9.1** under the BSD 3-Clause License: <https://gitlab.ow2.org/asm/asm>. The complete license is stored at `third-party-licenses/ASM-BSD-3-Clause.txt` and packaged as `META-INF/licenses/ASM-BSD-3-Clause.txt`.

`sparrow-metadata`, `sparrow-nbt`, and `sparrow-redis-message-broker` are not distributed or resolved by this version. They do not provide drop-in equivalents for global transactional SQL storage, optional InvSync player-profile events, or Bukkit persistent-data markers, so no unused dependency was added merely to mirror the upstream project list.

## Trademark and affiliation notice

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.** References to Mahjong Soul / 雀魂 describe rules or style only and do not imply affiliation, sponsorship, or endorsement. All names and trademarks belong to their respective owners.
