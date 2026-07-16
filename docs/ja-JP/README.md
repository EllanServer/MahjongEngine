# MahjongPaper 日本語ドキュメント

[プロジェクト概要へ戻る](../../README.ja-JP.md)

このディレクトリは、MahjongPaper を導入するサーバー管理者と、実際に卓へ参加するプレイヤー向けの日本語ガイドです。

## はじめに

- 初めてサーバーへ導入する場合：[インストールと初期設定](./installation.md)
- 雀魂風リーチ麻雀を遊ぶ場合：[リーチ麻雀 profile](./rules/riichi.md)
- 国標麻雀を遊ぶ場合：[国標麻雀 profile](./rules/gb.md)
- 四川麻雀を遊ぶ場合：[四川麻雀 profile](./rules/sichuan.md)

## 現在の製品境界

| 項目 | MahjongPaper の現在の動作 |
| --- | --- |
| Java | Java 21 以上 |
| サーバー | Paper / Folia。Paper 1.20.1 開発基準、`api-version: 1.20` |
| 必須依存 | CraftEngine 26.7 以上 |
| 任意依存 | InvSync 2.x |
| 既定モード | `MAJSOUL_HANCHAN` |
| 既定データベース | ローカル H2、有効 |
| 日本語表示 | プレイヤーのクライアント locale が `ja-JP` の場合に自動適用 |
| クライアント Mod | 不要 |

設定ファイルのコメントは、現時点では日本語化されていません。設定キーやコマンド、モード名は英語の識別子をそのまま使用します。

## 重要なルールの位置付け

MahjongPaper の三つのモードは、互いに独立した profile です。

- `MAJSOUL_*` は雀魂公式四人段位戦を基準にするリーチ麻雀です。
- `riichiProfile: EARLY_KAN_DORA` は槓ドラ表示時期だけを切り替えます。旧 `TOURNAMENT` は保存互換値で、完全な WRC 大会規則ではありません。
- `GB` は EMA/WMO Green Book を規範とする中国競技麻雀です。bundled `GB-Mahjong` は実装 backend であり、規則書と食い違う場合は backend と回帰テストを修正します。
- `SICHUAN` は T/TFMJ 01—2024 を規範とする四川血戦 profile です。MIL と地方大会規則は別 profile として扱います。
- `SICHUAN_TOURNAMENT` というモード別名は使用できません。

外部の書籍や動画は、一般的な麻雀、別の大会規則、地域ルール、または卓ごとの house rule を扱う場合があります。ゲーム内で最終的に適用される動作は、各ルールページの **MahjongPaper profile** を基準にしてください。

## プレイヤー向けクイックリンク

- [卓へ参加して準備する](./installation.md#最初の卓を作る)
- [基本コマンド](../../README.ja-JP.md#基本操作)
- [俯瞰表示「河を見る」](../../README.ja-JP.md#主な機能)
- [段位と InvSync](./installation.md#invsync-を使用する場合)

## サーバー管理者向けクイックリンク

- [動作要件](./installation.md#動作要件)
- [初回起動](./installation.md#初回起動)
- [CraftEngine bundle](./installation.md#craftengine-bundle-とリソース)
- [データベース](./installation.md#データベースと永続化)
- [ゲームルーム](./installation.md#最初の卓を作る)
- [更新と再起動](./installation.md#reload-と完全再起動)
- [トラブルシューティング](./installation.md#トラブルシューティング)

## 原文と検証資料

日本語文書は次のリポジトリ内資料と同じ製品境界を維持します。

- [English README](../../README.md)
- [简体中文 README](../../README.zh-CN.md)
- [中国語 Wiki](../wiki.zh-CN.md)
- [リーチ麻雀の round flow](../riichi-round-flow.md)
- [国標麻雀の rule source](../gb-mahjong-rules.md)
- [四川麻雀の T/TFMJ profile](../sichuan-rules.md)
- [全モードのルール検証表](../rule-verification-matrix.zh-CN.md)
- [InvSync プレイヤー段位接続ガイド](../invsync-player-rank.zh-CN.md)

ルール内容を更新する場合は、実装とテストに対応する検証表を最優先し、その後に各言語の説明と例を同期してください。

## ライセンスと再配布

- [LICENSE](../../LICENSE)
- [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)
- [リソースの帰属情報](../../resourcepack/ATTRIBUTION.md)
- [あみたろの声素材 使用報告案](../amitaro-usage-report.ja.md)

日本語文書はライセンス本文の代わりにはなりません。再配布時は、上記の原文と第三者条件を確認してください。
