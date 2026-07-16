# Sichuan Bloody Battle Player Rules

[简体中文](./sichuan-rules.zh-CN.md) · [日本語](./ja-JP/rules/sichuan.md)

> **Normative baseline:** `SICHUAN` follows T/TFMJ 01—2024, *Sichuan Mahjong Competition Rules*, issued by the Sichuan Tianfu New Area Mahjong Sports Association. MIL and local “Tianfu Dragon” rules are comparisons, not mixed into the default profile.

## Quick Reference

| Item | Current plugin rule |
| --- | --- |
| Players | Exactly 4 |
| Tiles | 108 suited tiles; no honors, flowers, or red fives |
| Starting hand | Dealer 14 tiles; every other player 13 |
| Opening | Declare a missing suit directly; no exchange-three phase |
| Calls | Pon, minkan, ankan, kakan, ron, tsumo; no chii |
| Win flow | Bloody Battle: winners leave the hand; play continues |
| Winning shape | Four groups and a pair, or seven pairs; declared missing suit must be absent |
| Payment | `2^min(fan, 3)`; self-draw adds 1 base point per payer |
| Match length | One room match defaults to 8 hands; a full official event layer is not implemented |

## 1. Tiles And Deal

The wall contains four copies each of 1 through 9 characters, circles, and bamboo: 108 tiles in total. There are no wind tiles, dragon tiles, flower tiles, red fives, dead wall, dora, or riichi mechanics.

The dealer starts with 14 tiles and the other players with 13. One two-die roll opens the wall: the sum chooses the side and the lower die chooses the stack count (a double uses that value). Replacement tiles for kongs come from the back of the remaining wall.

## 2. Ding Que And Forced Discards

After the deal, every player directly declares one missing suit: characters, circles, or bamboo. There is no mandatory exchange-three step in the T/TFMJ profile. This is the suit that player must eliminate.

While the player still holds a tile of the declared missing suit, that suit must be discarded before any other suit. A winning hand is legal only if the declared suit is absent from the full hand, including concealed tiles and melds.

## 3. Normal Actions And Claim Priority

The profile permits pon, minkan, ankan, kakan, ron, and tsumo. Chii is never available.

Ron has priority over pon and minkan. Multiple legal ron claims on the same discard are allowed. A kakan can be robbed before it completes. The fourth tile used for kakan must be the tile just drawn on that turn.

Every kan requires an available replacement tile. The last four draws do not create a special forced-win or blanket no-call zone.

## 4. Bloody Battle Flow

Winning does not end the hand immediately. A winner is settled and leaves active play; the other players continue with the same wall and scores. The hand ends when only one active player remains or when the wall is exhausted. A hand can therefore produce as many as three winners.

On a discard with several winners, every legal winner is paid. Players who already won are no longer included as active opponents in later win, kong, or exhaustive-draw payments.

## 5. Passing A Win

At any point, a player may decline a legal win. Doing so blocks that player from taking the same or a lower fan value until their next draw. A strictly higher-fan win remains legal during that interval; this matters because Ping Hu is 0 fan and All Triplets is 1 fan.

Choosing pon or kan when ron was available also counts as passing that win. The comparison uses the evaluated fan before the self-draw `+1` payment; 0 fan and 1 fan are distinct levels.

## 6. The Last Wall Tiles

T/TFMJ does not impose a “final four must win” rule. A player may pass ron or tsumo, and otherwise legal pon or kong actions remain available while a replacement tile exists. Only the actual final wall tile grants the Hai Di modifier. Timeout automation does not force a win.

## 7. Winning Shapes And Fan

A legal hand must be missing the player's declared suit and must form either four groups plus a pair or seven pairs. Scoring fan is capped at 3.

| Fan entry | Raw fan | Plugin interpretation |
| --- | ---: | --- |
| Basic hand / Ping Hu | 0 | Ordinary winning hand; base score 1 |
| All Triplets / Dui Dui Hu | 1 | Four triplet-or-kong groups and a pair |
| Full Flush / Qing Yi Se | 2 | Every tile comes from one suit |
| Seven Pairs | 2 | Seven concealed pairs; each four-of-a-kind is also a root |
| Root / Gen | +1 each | Four identical tiles, including inside Seven Pairs |
| Golden Hook | +1 | All-triplets hand with four fixed melds and a single pair wait |
| Hai Di | +1 | Win on the final wall tile or its final discard |
| Kong Bloom | +1 | Self-draw on a kong replacement |
| Shoot After Kong | +1 | Win on the discard immediately following a kong |
| Robbing The Kong | +1 | Win on an attempted added kong |

The highest applicable base pattern is used, then legal modifiers are added and the scoring total is capped at 3. Jiang Dui and Dragon Seven-Pairs tiers are not separate T/TFMJ fan: a 2/5/8 triplet hand is All Triplets, while a rooted Seven Pairs is `QI_DUI + GEN`.

## 8. Win Payments

Let `P = 2^min(fan, 3)`. The possible basic units are 1, 2, 4, and 8.

- **Discard win:** only the discarder pays `P` to the winner.
- **Self-draw:** every opponent who is still active pays `P + 1` to the winner.
- **Multi-ron:** the discarder pays `P` separately to every resolved winner.

## 9. Kong Payments And Call Transfer

Kong payments are immediate and zero-sum:

- **Ankan:** every other active player pays 2.
- **Minkan:** the player whose discard completed the kong pays 2.
- **Kakan:** every other active player pays 1.

If a kong declarer immediately deals into a win, the income from that kong and any consecutive kong chain is transferred to the winner; the original payers are not refunded. With multiple winners, each receives `ceil(total kong income / winner count)`, and the discarder supplies any rounding difference. A non-winning ordinary discard clears the chain. At wall exhaustion, a not-ready player returns all kong income collected during the hand to its original payers.

## 10. Wall Exhaustion: Hua Zhu And Cha Jiao

Only players still active are evaluated when the wall is exhausted.

1. Every active player's final hand is recalculated as ready or not ready. Old wait caches are not reused, and a physically impossible fifth copy is not a wait.
2. A player who naturally still holds the declared missing suit is simply not ready for cha-jiao; the final snapshot does not create a fixed 16/48-point hua-zhu penalty.
3. Each not-ready player pays every ready player that ready player's best theoretical winning unit, capped at 8.
4. Not-ready kong income is refunded before cha-jiao.

An actual live-tournament violation requires referee state and an external penalty; it cannot be inferred merely from a naturally unfinished missing suit at wall exhaustion.

## 11. Match Length And Dealer

T/TFMJ defines a room match as normally 50 minutes or eight hands, with event regulations allowed to use six. MahjongPaper defaults to eight hands and displays net score from zero; it does not yet implement the full eight-match championship layer or time-based tournament procedure. Generic length or point overrides are custom-room compatibility, not the strict preset.

Other generic Riichi options do not rewrite this profile: Sichuan's 0-fan Ping Hu, multi-winner Bloody Battle flow, no chii, no red fives, and no dora remain fixed.

The next dealer is determined by the preceding hand:

- the first winner becomes dealer;
- if the first win event was multi-ron, the discarder becomes dealer;
- if nobody won, the current dealer remains.

The default UI labels these as **Hand 1** through **Hand 8**, not East/South riichi rounds. Dealer position is maintained separately from that hand number.

## 12. Recognized Profile Differences

The default does not mix conflicting profiles. MIL uses different kong-shot refund and fixed external hua-zhu-penalty concepts. The Tianfu Dragon local event rules add final-four compulsory wins and different direct-kong payments. Those require separately named future profiles; `SICHUAN_TOURNAMENT` is not a valid alias for either.

## 13. Real-World References And Tutorials

- **Normative standard:** [T/TFMJ 01—2024 standard record](https://www.ttbz.org.cn/StandardManage/Detail/112870/) and its [platform-indexed PDF](https://www.ttbz.org.cn/Home/PdfFileStreamGet/c3QsMTEyODcw) (the PDF may need to be opened from the standard record)
- **Unofficial reading mirror:** [Biaozhuns copy](https://www.biaozhuns.com/archives/20240808/show-380782-108-1.html) — convenience only; rulings continue to follow the official standard above
- **Secondary comparison:** [Mahjong International League — Brief Introduction to Bloody Mahjong](https://mahjong-mil.org/wp-content/uploads/2024/08/Brief_Introduction_to_Bloody_Mahjong-1.pdf)
- **YouTube learning video:** [How to Play Sichuan Mahjong: Blood Battle to the End](https://www.youtube.com/watch?v=49i-XzRZCSQ) — useful for the broad flow; compare every profile detail with T/TFMJ.
- **Additional Chinese course:** [四川麻将高级技术套路教程（爱奇艺）](https://www.iqiyi.com/a_19rrhwgx9d.html)
- **Bilibili fallback 1:** [成都麻将 / 血战到底教学：从入门到精通（BV1c6421V7Yr）](https://www.bilibili.com/video/BV1c6421V7Yr/)
- **Bilibili fallback 2:** [四川麻将血战到底：超详细入门教学（BV1qY411g7Hi）](https://www.bilibili.com/video/BV1qY411g7Hi/)

Videos are learning aids, not authorities. Rule disputes use T/TFMJ first; MIL and local-event differences must remain explicitly separated.

## 14. Implementation And Regression Contract

This page records the T/TFMJ-backed default. A rule change is incomplete unless the controller/rules engine, all three language versions, and the protecting tests move together.

- Core implementation: `GbTableRoundController.java`, `SichuanPreparationFlow.java`, `DefaultSichuanRulesEngine.java`, and `SichuanHuEvaluator.java`.
- Primary regression contracts: `SichuanRulesEngineTest`, `SichuanRealWorldFanCoverageTest`, `SichuanRuleInvariantTest`, `GbTableRoundControllerTest`, `SessionActionDeadlineCoordinatorTest`, and `SessionRulePresetResolverTest`.
- Fan invariant: Ping Hu is 0, All Triplets is 1, Full Flush and Seven Pairs are 2; legal modifiers stack and scoring is capped at 3, so the maximum basic unit is 8.
- Kong-payment invariant: ankan collects 2 from every other active player; minkan collects 2 only from the discarder; kakan collects 1 from every other active player.
- Flow invariant: direct ding que, no chii, no default final-four compulsion, call transfer, cha-jiao, and Bloody Battle continuation are part of the T/TFMJ profile; the default room match is eight hands.
