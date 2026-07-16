# Riichi Mahjong Player Rules

[简体中文](./riichi-rules.zh-CN.md) · [日本語](./ja-JP/rules/riichi.md)

> **Normative profile:** `MAJSOUL` follows the official four-player Mahjong Soul ranked rules for the items documented here. `EARLY_KAN_DORA` changes only open-kan dora timing. The legacy persisted name `TOURNAMENT` is accepted for compatibility, but neither name is a complete WRC ruleset.

## Quick Reference

| Item | Current plugin rule |
| --- | --- |
| Players | Exactly 4 |
| Tiles | 136; default preset uses one red five in each suit (3 total) |
| Wall | Before dealing, 136 tiles split into a 122-tile live section and a 14-tile dead wall |
| Starting hand | Dealer 14 tiles; every other player 13 |
| Starting / return / first-place requirement | 25,000 / 25,000 / 30,000 |
| Win floor | At least 1 yaku han by default; dora cannot satisfy the floor |
| Calls | Chii, pon, minkan, ankan, kakan, ron, tsumo |
| Ron mode | `MULTI_RON` by default; `HEAD_BUMP` is configurable |
| Base match length | Tonpuu: 4 hands; hanchan: 8 hands, before repeats or extension |

## 1. Goal And Winning Shapes

A normal winning hand contains four groups and one pair. A group is a sequence, triplet, or kan. The evaluator also recognizes the special closed shapes supported by standard Riichi play, including seven pairs and thirteen orphans.

Completing a shape is not enough by itself. The hand must contain a yaku and meet `rule.minimumHan`. The default is one yaku han. Visible dora, ura-dora, and red-five dora add to the final score, but they are bonuses rather than yaku and cannot satisfy the minimum-han requirement.

Open tanyao is enabled in the default presets. Local-yaku data is retained only for old-save compatibility; this build does not expose or enable a separate Mahjong Soul local-yaku package.

## 2. Tiles, Wall, And Deal

The game uses the three numbered suits, four winds, and three dragons. Each ordinary tile has four copies. The default three-red preset replaces one normal five in each suit with a red five.

Fourteen tiles at the back of the shuffled wall form the dead wall. They are not used for normal draws. Dora indicators and rinshan replacement tiles come from this reserve. Normal draws consume the front of the live wall.

The dealer begins with 14 tiles and discards first. The other players begin with 13. Physical table positions remain East, South, West, and North, while the current dealer and scoring seat winds advance with the hand.

## 3. Normal Turns And Discards

On an uninterrupted turn, draw one tile from the live wall and discard one tile. A discard becomes public and remains part of that player's discard history even if another player claims it.

After declaring riichi, a player normally discards only the tile just drawn. Legal kan choices are still validated by the engine; an attempted kan that would violate the riichi state is not offered or accepted.

After chii or pon, the caller does not take a normal draw and must discard directly. Kuikae is enforced: after pon, the claimed tile kind cannot be discarded immediately; after chii, the claimed tile and the prohibited same-sequence swap tile cannot be discarded immediately. The restriction clears after the caller makes a legal discard.

## 4. Calls And Reaction Priority

When a discard can be claimed, all eligible players receive a reaction window.

- **Ron:** win on another player's discard.
- **Pon:** any opponent may claim a legal triplet.
- **Minkan:** any opponent may claim the fourth tile for an open kan when a replacement draw is available.
- **Chii:** only the next player in turn order may claim a legal sequence.
- **Skip:** decline the offered reaction.

Competing claims resolve in this order: ron, then pon or minkan, then chii. Pon and minkan at the same priority go to the nearer player in turn order. The final live-wall discard permits ron only; chii, pon, and minkan are closed.

`rule.ronMode` controls simultaneous ron claims:

- `MULTI_RON`: every legal claimant wins, including double or triple ron.
- `HEAD_BUMP`: only the nearest legal claimant in turn order wins.

There is no separate triple-ron abort in `MULTI_RON`; all three legal claimants are settled as winners.

## 5. Riichi

Riichi may be declared only when all of the following are true:

- the hand is closed;
- the player is not already in riichi;
- the declaration discard leaves a legal tenpai shape;
- the player has at least 1,000 points;
- at least four tiles remain in the live wall.

The declaration deducts one 1,000-point stick exactly once. A winning claimant receives the carried riichi pool according to the settlement rules below. If nobody wins, the sticks remain for a later hand.

A declaration made before any call has interrupted the first go-around may qualify as double riichi. Ippatsu is canceled by any chii, pon, minkan, ankan, or kakan.

## 6. Furiten And Passing Ron

A player is furiten when any current winning tile appears among that player's own discards. A furiten player cannot ron, but may still tsumo a legal hand.

Passing a legal ron also creates furiten:

- a non-riichi player is temporarily furiten until their next draw;
- a riichi player remains furiten for the rest of the hand.

Choosing chii, pon, or kan while ron was also available counts as passing that ron opportunity.

## 7. Kan, Rinshan, Chankan, And Dora Timing

An ankan, minkan, or kakan requires an available rinshan replacement and may not take the total kan count above four. A successful kan draws from the dead-wall side and moves one live-wall tile into the dead wall. A rinshan draw is never haitei, and the following discard is never houtei. Ankan and kakan are unavailable after the final live-wall draw.

Kakan can be robbed by chankan. Ankan can be robbed only for a legal thirteen-orphans win. A robbed kan never reveals an extra dora indicator.

The profile setting changes only successful-kan indicator timing:

- **`MAJSOUL`:** ankan dora is revealed when the kan succeeds. For minkan and kakan, the extra indicator is revealed when the caller next discards, before ron eligibility and scoring for that discard are evaluated. A rinshan win therefore does not use that open-kan dora.
- **`EARLY_KAN_DORA`:** every successful kan reveals its extra indicator before the rinshan draw, so a rinshan win uses it.

The two profiles otherwise use the same wall, final-discard, and kan-availability behavior in this build. Old saved `TOURNAMENT` values behave like `EARLY_KAN_DORA`; the misleading competitive/JPML aliases are no longer offered. WRC 2025 is a separate ruleset and is not implemented by this switch.

## 8. Winning And Scoring

The plugin evaluates yaku, yakuman, han, fu, dora, ura-dora, red fives, dealer status, honba, and responsibility payments through its Riichi rules backend.

- **Ron:** the discarder pays each resolved winner. In multi-ron, only the winner nearest the discarder receives the honba and carried riichi-stick pool.
- **Tsumo:** all three opponents pay, with dealer/non-dealer shares applied. Honba is collected from each payer. The winner receives the riichi-stick pool.
- **Pao:** supported responsibility cases are included in the payment breakdown.

The settlement screen is authoritative for the exact yaku, han, fu, limit, and payment split of a completed hand.

## 9. Draws And Abortive Draws

When the live wall is exhausted without a win, the engine first checks for nagashi mangan. Otherwise it performs a normal exhaustive draw:

- if one to three players are tenpai, a total of 3,000 points moves from noten players to tenpai players;
- if everybody or nobody is tenpai, no noten payment is made;
- a tenpai dealer remains dealer; a noten dealer rotates;
- riichi sticks carry over.

Supported abortive draws include nine terminals and honors, four identical first-round wind discards, four players in riichi, and four kans made across more than one player. Abortive draws keep the dealer and carry riichi sticks.

A sole wait that would require a fifth physical copy of a tile already held four times in the concealed hand is not tenpai and cannot be used to declare riichi. Ordinary empty waits remain valid when their real winning copies are merely visible or exhausted. A multi-player four-kan abort is resolved only after the fourth-kan player's discard has first been offered for ron; one player making all four kans does not abort the hand.

## 10. Match Length, Dealer, And End Conditions

The preset names describe **base length**, not an exact number of hands:

- `MAJSOUL_TONPUU` starts at East 1 and has a four-hand East-round base.
- `MAJSOUL_HANCHAN` starts at East 1 and has an eight-hand East-and-South base.

The dealer repeats after a dealer win and after an exhaustive draw in which the dealer is tenpai. Every repeat adds honba, so a tonpuu or hanchan can contain more than four or eight hands.

At base all-last, the match can end when the leader has reached the 30,000 **first-place requirement**. A leading dealer at or above that requirement ends on a dealer continuation; otherwise play continues. If the dealer rotates while nobody has reached it, the match extends: tonpuu may continue through South 4, and hanchan through West 4. In extension, settlement ends the match as soon as first place reaches 30,000; a multi-ron that includes the dealer keeps the official dealer-continuation priority. Dealer repeats can still add hands within the maximum labels.

The match also ends after a hand resolution leaves any player below zero. Any riichi sticks still on the table when the match ends are awarded to first place.

Final placement is ordered by points. If players are tied, the fixed starting-seat order breaks the tie: initial East, then South, West, and North. The order does not depend on the dealer position at the final hand.

## 11. Real-World References And Tutorials

These links teach real-world Riichi Mahjong. They are useful background, but they contain rules and table conventions that may differ from this plugin profile.

- **Normative ranked reference:** [Official Mahjong Soul four-player rules](https://mahjongsoul.com/news/46) and [official FAQ](https://mahjongsoul.com/faq)
- **Separate comparison only:** [World Riichi Championship Rules 2025](https://ooyamaneko.net/download/mahjong/riichi/WRC_Rules_2025_en.pdf) and its [official clarification](https://ooyamaneko.net/download/mahjong/riichi/WRC_Rules_2025_-_Clarification_en.pdf)
- **Primary video tutorial:** [Light Grunty's Riichi Mahjong Guide videos on YouTube](https://www.youtube.com/channel/UC2E_GfC7EwgBrYtbDzonZew)
- **Bilibili fallback:** [零基础入门立直麻将（BV1AK411Z7d2）](https://www.bilibili.com/video/BV1AK411Z7d2/)

Use those resources to learn general tile shapes, yaku, defense, and scoring. For kan-dora timing, match length, ron mode, enabled options, and any other disagreement at a MahjongPaper table, use this page and the table's displayed rule settings.

## 12. Implementation And Regression Contract

This page is normative for the current plugin profile. A rule change is incomplete unless implementation, the English/Chinese/Japanese player documents, and the protecting regression tests move together.

- Core implementation: `RiichiRoundEngine.kt`, `RiichiPlayerState.kt`, `CoreModels.kt`, and `SessionRulePresetResolver.java`.
- Primary regression contracts: `RiichiRoundEngineTest`, `RiichiPlayerStateTest`, `RiichiRealWorldYakuCoverageTest`, and `SessionRulePresetResolverTest`.
- Match-length invariant: tonpuu has a four-hand base and may advance no farther than the **South 4 label**; hanchan has an eight-hand base and may advance no farther than the **West 4 label**. Dealer repeats can add hands even at those labels, so neither preset has a hard total-hand cap.
- Profile invariant: `MAJSOUL` and `EARLY_KAN_DORA` differ only in the successful-kan dora timing stated in section 7; legacy `TOURNAMENT` is compatibility data and must never imply WRC coverage.
