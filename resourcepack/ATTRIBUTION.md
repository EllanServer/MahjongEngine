This resource pack reuses Mahjong tile textures and base item models from the upstream project:

- Project: `doublemoon1119/MahjongCraft`
- Repository: https://github.com/doublemoon1119/MahjongCraft
- License: MIT
- Copyright: Copyright (c) 2021 doublemoon1119

Related tile-art provenance retained by Paper-plugin upstreams:

- `lietxia/mahjong_graphic`: https://github.com/lietxia/mahjong_graphic (M+ Fonts License)
- `SyaoranHinata/I.Mahjong`: https://github.com/SyaoranHinata/I.Mahjong (M+ Fonts License); its documentation identifies GL-MahjongTile as an earlier source.

The additional `assets/mahjongcraft/items/**` files and the `mahjong_tile_back.json` item model were added for Paper `item_model` support.

Dice textures, table textures, dice item models, and the table furniture model are project-generated assets for MahjongPaper.

## Sound Effects

The sound effects under `assets/mahjongcraft/sounds/` use low-risk redistributable sources:

- Real tile/table sounds are from Freesound recordings marked Creative Commons 0 (CC0 1.0).
- Action call voices are from Amitaro's Voice Material Studio. These are not CC0 or MIT; use requires credit, a terms link/readme, and a post-release usage report within the period stated by the current Amitaro terms when redistributed in a Minecraft/CraftEngine bundle.
- GB and Sichuan variant-specific sounds are derived only from the CC0 Freesound recordings listed below; they do not include third-party voice material.
- The T-STUDIO Mahjong sound pack is intentionally not included because its redistribution terms are not a good fit for a merged CraftEngine resource bundle.

| File | Source | License / Terms | Usage | Notes |
|---|---|---|---|---|
| `tile_draw.ogg` | Freesound `329100` - `Domino pieces 2` by Macif | CC0 1.0 | Draw tile | Cropped and normalized from a real domino/tile recording. |
| `tile_discard.ogg` | Freesound `329099` - `Domino pieces 1` by Macif | CC0 1.0 | Discard tile | Cropped and normalized from a real domino/tile recording. |
| `tile_shuffle.ogg` | Freesound `197868` - `domino shuffle.WAV` by Millavsb | CC0 1.0 | Shuffle / round start | Cropped and normalized from a real shuffle recording. |
| `turn_change.ogg` | Freesound `745024` - `Domino_sfx_UIButton` by poenia | CC0 1.0 | Turn change | Trimmed and normalized. |
| `round_draw.ogg` | Freesound `329098` - `Domino pieces hit` by Macif | CC0 1.0 | Draw round | Built from cropped real tile/domino hits. |
| `reaction_chi.ogg` | Amitaro's Voice Material Studio `chii_01.wav` | Amitaro terms | Chi | Female voice; converted to Ogg Vorbis, trimmed, and normalized. |
| `reaction_pon.ogg` | Amitaro's Voice Material Studio `pon_01.wav` | Amitaro terms | Pon | Female voice; converted to Ogg Vorbis, trimmed, and normalized. |
| `reaction_kan.ogg` | Amitaro's Voice Material Studio `kan_01.wav` | Amitaro terms | Kan | Female voice; converted to Ogg Vorbis, trimmed, and normalized. |
| `riichi.ogg` | Amitaro's Voice Material Studio `ri-chi_01.wav` | Amitaro terms | Riichi | Female voice; converted to Ogg Vorbis, trimmed, and normalized. |
| `round_win.ogg` | Amitaro's Voice Material Studio `ron_01.wav` | Amitaro terms | Win | Female voice; converted to Ogg Vorbis, trimmed, and normalized. |
| `gb_tile_draw.ogg`, `gb_tile_discard.ogg`, `gb_tile_shuffle.ogg`, `gb_turn_change.ogg`, `gb_round_draw.ogg` | Freesound CC0 recordings listed below | CC0 1.0 | GB table sounds | Variant-specific crops, combinations, and normalization from real domino/tile recordings. |
| `gb_reaction_chi.ogg`, `gb_reaction_pon.ogg`, `gb_reaction_kan.ogg`, `gb_round_win.ogg` | Freesound CC0 recordings listed below | CC0 1.0 | GB action cues | Variant-specific non-voice tile cues. |
| `sichuan_tile_draw.ogg`, `sichuan_tile_discard.ogg`, `sichuan_tile_shuffle.ogg`, `sichuan_turn_change.ogg`, `sichuan_round_draw.ogg` | Freesound CC0 recordings listed below | CC0 1.0 | Sichuan table sounds | Variant-specific crops, combinations, filtering, and normalization from real domino/tile recordings. |
| `sichuan_reaction_chi.ogg`, `sichuan_reaction_pon.ogg`, `sichuan_reaction_kan.ogg`, `sichuan_round_win.ogg` | Freesound CC0 recordings listed below | CC0 1.0 | Sichuan action cues | Variant-specific non-voice tile cues. |

Sources:

- Freesound `329098` - Domino pieces hit by Macif: https://freesound.org/s/329098/
- Freesound `329099` - Domino pieces 1 by Macif: https://freesound.org/s/329099/
- Freesound `329100` - Domino pieces 2 by Macif: https://freesound.org/s/329100/
- Freesound `197868` - domino shuffle.WAV by Millavsb: https://freesound.org/s/197868/
- Freesound `745024` - Domino_sfx_UIButton by poenia: https://freesound.org/s/745024/
- CC0 1.0 deed: https://creativecommons.org/publicdomain/zero/1.0/
- Amitaro's Voice Material Studio game voices: https://amitaro.net/voice/game_01/
- Amitaro's Voice Material Studio terms: https://amitaro.net/voice/voice_rule/
- English terms: https://amitaro.net/voice/terms/

Required voice credit:

- Voice: Amitaro's Voice Material Studio (https://amitaro.net/)
- 音声素材：あみたろの声素材工房 (https://amitaro.net/)

The Amitaro recordings may be redistributed only as part of a work such as this plugin/resource bundle and subject to the current terms. Do not redistribute them as a standalone voice or sound pack. Preserve this attribution and the terms link/readme. The repository's MIT license does not relicense these recordings.

## MahjongCraft MIT License Notice

MIT License

Copyright (c) 2021 doublemoon1119

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
