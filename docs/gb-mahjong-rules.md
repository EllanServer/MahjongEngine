# GB Mahjong Player Rules

[简体中文](./gb-mahjong-rules.zh-CN.md) · [日本語](./ja-JP/rules/gb.md)

> **Normative baseline:** the `GB` preset follows the EMA/WMO Mahjong Competition Rules (Green Book). The vendored [`GB-Mahjong`](https://github.com/zheng-fan/GB-Mahjong) code is an implementation dependency, not a competing rule authority. Explicitly changed generic table options are documented as house-rule compatibility rather than MCR.

## Quick Reference

| Item | Current plugin rule |
| --- | --- |
| Players | Exactly 4 |
| Tiles | 144, including all 8 flower tiles |
| Wall | One fully consumable wall; no 14-tile dead wall |
| Starting hand | Dealer 14 tiles; every other player 13, before flower replacement |
| Match ledger | Net score starts at 0; no target-score ending condition in the strict preset |
| Win floor | At least 8 non-flower fan |
| Calls | Chii, pon, minkan, ankan, kakan, ron, tsumo |
| Ron mode | Single winner |
| Match length | `GB` preset: 16 hands, East 1 through North 4; table rules may override length; no dealer repeats |

## 1. Tiles And Flowers

The 144-tile set contains:

- 108 suited tiles: characters, circles, and bamboo, 1 through 9, four copies each;
- 16 wind tiles: East, South, West, and North, four copies each;
- 12 dragon tiles: white, green, and red, four copies each;
- 8 single-copy flowers: plum, orchid, bamboo, chrysanthemum, spring, summer, autumn, and winter.

When a flower is dealt or drawn, its owner may use **Expose flower** and take a replacement from the back of the wall, or retain it and later discard it. A flower replacement that is itself a flower presents the same choice again. Exposed flowers score at settlement; an unexposed flower does not form part of a winning shape. A discarded flower cannot be claimed for chii, pon, kong, or a win.

## 2. Dice, Wall, And Deal

Two six-sided dice are rolled twice. The first sum chooses the wall side from the dealer, and the two sums together choose the break on that side. All 144 tiles remain part of the consumable wall; there is no Riichi-style dead wall or dora reserve.

The deal follows the physical table sequence:

1. East, South, West, and North each receive four tiles, repeated for three passes.
2. Each player receives one more tile.
3. The dealer takes the jump draw for a fourteenth tile.
4. The original deal remains intact. On the owner's turn, each held flower can be exposed for a tail replacement or retained for discard; this simultaneous-input adaptation does not force exposure.

Normal draws come from the front of the wall. Flower and kong replacement draws come from the back.

## 3. Turn Flow And Legal Actions

On a normal turn, draw a tile and discard one. The following actions are available when legal:

- **Chii:** only the next player in turn order may use the discard to complete a sequence.
- **Pon:** any opponent may use the discard to complete a triplet.
- **Minkan:** any opponent may use the discard to complete an open kong, then draw a replacement from the back.
- **Ankan:** declare four matching concealed tiles on your own turn, then draw a replacement.
- **Kakan:** add the fourth tile to an existing pon; the added tile may be robbed before the kong completes.
- **Ron:** win on another player's discard.
- **Tsumo:** win on your own draw.

Reaction priority is ron, then pon or minkan, then chii. If several players submit legal ron claims, this profile resolves a single winner rather than multi-ron. Competing same-priority calls go to the nearest eligible player in turn order.

There is no riichi declaration, ippatsu, furiten, dora, ura-dora, red-five bonus, honba, or riichi-stick pool in `GB`.

## 4. Winning Hands And The 8-Fan Floor

The native backend evaluates hand legality, waits, fan composition, and the reported total. It covers the MCR-style fan table supplied by the vendored source rather than a second Java scoring clone.

A win is legal only when the hand reaches at least **8 fan after flower fan is removed**. Flowers may increase the eventual payment, but they cannot make a sub-eight non-flower hand legal.

This distinction also applies to wait display. A seven-fan hand that becomes legal only by the self-draw fan can still appear as a valid self-draw wait; a hand that depends only on flower fan to cross eight cannot win.

The settlement view lists the fan entries returned by the native backend. `MINGANGANG` is an internal bridge key for the Green Book's special six-point “one melded kong and one concealed kong” case; it is not advertised as an 82nd official fan. Formal edge/closed/single-wait scoring includes exhausted alternative waits, so a formally multi-wait hand does not gain an improper single-wait point.

## 5. Settlement

Let `F` be the total fan reported for the winning hand, including flower fan after the hand has independently passed the eight non-flower-fan floor.

- **Self-draw:** each of the three opponents pays `F + 8`; the winner receives `3 × (F + 8)`.
- **Discard win:** the discarder pays `F + 8`; each of the other two opponents pays 8; the winner receives `F + 24`.

Every score application is zero-sum. A normal wall exhaustion has no Riichi-style tenpai/noten transfer.

## 6. Match Length And Dealer

The strict `GB` preset starts the match ledger at net 0 and uses four wind rounds:

- East 1 through East 4;
- South 1 through South 4;
- West 1 through West 4;
- North 1 through North 4.

With an unmodified `GB` preset there are exactly 16 hands, with the dealer rotating after every hand and no continuation. MCR has no 500-point starting offset and no target-score extension. The generic `startingPoints`, `minPointsToWin`, and `length` settings remain available only for saved-table and house-rule compatibility; changing them leaves the strict MCR match profile and must be identified as a custom room.

Other generic Riichi options do not rewrite this profile: the eight non-flower-fan floor, single ron, no red fives, and no dora remain fixed for `GB`.

## 7. What The Native Backend Does

MahjongPaper sends the concealed tiles, melds, winning tile, seat and round winds, flowers, win type, and contextual flags through its JNI bridge. The vendored native library returns legality, waits, fan entries, total fan, and score deltas. MahjongPaper then owns the reaction window, wall flow, player prompts, settlement screen, history, and atomic application of those deltas.

This architecture has two important consequences:

- the plugin should not silently replace GB fan logic with an unrelated local interpretation;
- use of the native source does **not** justify claiming perfect identity with every later MCR revision or every live tournament ruling.

When investigating an edge case, compare the exact request sent by MahjongPaper with the vendored native result and the external rule reference.

## 8. Project Profile Differences To Watch

External Mahjong tutorials often teach a family or regional Chinese game rather than MCR. In particular, do not import rules such as a dead wall, riichi, dora, dealer repeats, multi-ron, or a different payment table into this profile.

The plugin's player-facing contract is specifically:

- all 144 tiles, including all eight flowers;
- eight **non-flower** fan required to win;
- the `8 + fan` payment formula above;
- single ron;
- a strict net-zero, fixed 16-hand preset with no dealer repeats;
- fan interpretation supplied by the bundled vendored backend, with no promise of perfect coverage of every official edge case.

## 9. Real-World References And Tutorials

These resources provide real-world context. The rules documents go beyond a beginner video, while the videos are useful for learning tiles, turns, calls, and hand construction.

- **Normative MCR landing page:** [European Mahjong Association — Mahjong Competition Rules](https://mahjong-europe.org/portal/index.php?Itemid=167&id=31&option=com_content&view=article)
- **Normative Green Book:** [EMA/WMO Mahjong Competition Rules PDF](https://mahjong-europe.org/portal/images/docs/mcr_EN.pdf)
- **Tournament procedure:** [EMA MCR Regulations PDF](https://mahjong-europe.org/portal/images/docs/mcr_regulations.pdf)
- **Multilingual reference:** [WMO Mahjong Competition Rules 2021](https://www.mindmahjong.com/adobe/MCR2021.pdf)
- **Implementation only:** [zheng-fan/GB-Mahjong](https://github.com/zheng-fan/GB-Mahjong)
- **Primary video tutorial:** [Easiest Mahjong tutorial (for beginners)](https://youtu.be/pka0nVIahb0) — useful for physical basics, not a substitute for the MCR fan table.
- **Bilibili fallback 1:** [国标麻将教程（BV1p64y1z7wL）](https://www.bilibili.com/video/BV1p64y1z7wL/)
- **Bilibili fallback 2:** [国标麻将教程（BV1LS68YpE5X）](https://www.bilibili.com/video/BV1LS68YpE5X/)

Tutorial videos are learning aids, not rule authorities. For a disputed fan, flower choice, settlement, dealer rotation, or match structure, use the Green Book and EMA regulations first, then verify that the implementation and regression test agree.

## 10. Implementation And Regression Contract

This page is normative for the current plugin profile. A rule change is incomplete unless the controller/native mapping, the English/Chinese/Japanese versions of this document, and the protecting tests move together.

- Core implementation: `GbTableRoundController.java`, `GbRoundSupport.java`, `GbReactionResolver.java`, `GbRuleProfile.java`, and `native/gbmahjong/src/gbmahjong_jni.cpp`.
- Primary regression contracts: `GbOfficialRulesRegressionTest`, `GbTableRoundControllerTest`, `GbReactionResolverTest`, `GbMahjongRealWorldFanCoverageTest`, and `SessionRulePresetResolverTest`.
- Required invariants: 144 tiles with optional flower exposure; an eight-fan floor after removing flower fan; the `8 + fan` settlement; single ron; no dealer repeats; and a net-zero fixed 16-hand strict preset.
- Native-boundary invariant: the vendored backend must be corrected when it conflicts with the Green Book; it is not itself the rule authority.
