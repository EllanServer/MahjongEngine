# MahjongPaper

> このプロジェクトは、全体が AI によって作成されています。

> **本プロジェクトは公式の Minecraft 製品ではなく、Mojang または Microsoft の承認・提携を受けていません。**
>
> `Mahjong Soul` / `雀魂` という名称は、互換性を意識したルールや表示スタイルを説明する目的でのみ使用しています。MahjongPaper は雀魂およびその権利者とは無関係であり、承認や後援を受けていません。各製品名・商標は、それぞれの権利者に帰属します。

[English](./README.md) | [简体中文](./README.zh-CN.md) | **日本語**

詳しい日本語ドキュメントは [docs/ja-JP](./docs/ja-JP/README.md) を参照してください。

ゲーム内メッセージは英語、簡体字中国語、繁体字中国語（台湾・香港・マカオ）、日本語（`ja-JP`）に対応しています。表示言語はプレイヤーの Minecraft クライアント locale に従います。サーバー設定ファイルのコメントは、現時点では英語・簡体字中国語・繁体字中国語のみです。

## 概要

MahjongPaper は、`MahjongCraft` を参考に Paper/Folia 向けに再構成した麻雀プラグインです。Paper の Display Entity と CraftEngine を利用し、次の三つの独立したゲームモードを提供します。

- 雀魂風リーチ麻雀：東風戦・半荘戦
- 国標麻雀（中国競技麻雀 / GB）
- 四川麻雀（T/TFMJ 01—2024 を規範とする血戦到底 profile）

新規卓の既定モードは `MAJSOUL_HANCHAN` です。

## 主な機能

- 東・南・西・北の固定席、クリック着席、準備確認、4 人揃った時点での自動開始
- 卓主と管理者向けの卓管理 GUI
- 空席を補う Bot と 4 Bot テスト卓
- 打牌、リーチ、ツモ、ロン、チー、ポン、明槓、暗槓、加槓
- 観戦、非公開手牌表示、HUD、局結果画面、ローカライズされた案内
- 対局中に右側の **「河を見る」** を選ぶ俯瞰表示
- 俯瞰表示中の **「席に戻る」** 操作と、復帰できない場合の Shift フォールバック
- クライアント Mod 不要の表示・操作
- 卓の位置、卓主、モード、ルール設定の永続化
- H2、MariaDB、MySQL による対局履歴・順位履歴・ランキング投影
- 任意の InvSync 2.x によるオンラインプレイヤーの段位 profile 同期。現在、自動更新は 4 人の人間プレイヤーによるリーチ対局だけ
- CraftEngine による牌アイテム、家具 hitbox、リソース bundle、culling 連携

サーバー再起動後に復元されるのは卓の位置、卓主、モード、ルール設定です。停止時点の手牌、牌山、点数、反応待ちウィンドウなど、進行中の一局そのものは再開されません。

## 動作要件

| 項目 | 要件 |
| --- | --- |
| Java | Java 21 以上。配布物は Java 21 bytecode |
| サーバー | Paper / Folia。開発基準は Paper 1.20.1、`api-version: 1.20` |
| 必須プラグイン | CraftEngine 26.7 以上 |
| 任意プラグイン | InvSync 2.x |
| クライアント Mod | 不要 |

詳細な導入手順は [インストールと初期設定](./docs/ja-JP/installation.md) を参照してください。

## クイックスタート

1. Java 21 以上で Paper または Folia サーバーを用意します。
2. CraftEngine 26.7 以上をインストールします。
3. MahjongPaper の配布 JAR をサーバーの `plugins` ディレクトリへ配置します。
4. サーバーを完全起動し、`plugins/CraftEngine/resources/mahjongpaper` に bundle が出力されたことを確認します。
5. 管理者権限 `mahjongpaper.admin` を持つプレイヤーで、ゲームルームと卓を作成します。

```text
/mahjong room wand
# 部屋の一方の角を左クリックし、反対側の角を右クリック
/mahjong room create main-hall メインホール
/mahjong create
```

既定設定では `gameRooms.enabled: true` と `gameRooms.restrictNewTables: true` のため、新しい卓はゲームルーム内でのみ作成できます。選択範囲を使わず、現在位置を中心に簡易ルームを作ることもできます。

```text
/mahjong room create quick-room
/mahjong create
```

## 三つのゲームモード

| 項目 | 雀魂風リーチ麻雀 | 国標麻雀 | 四川麻雀 |
| --- | --- | --- | --- |
| モード | `MAJSOUL_TONPUU` / `MAJSOUL_HANCHAN` | `GB` | `SICHUAN` |
| 牌 | 136 枚、赤五筒・赤五萬・赤五索 | 144 枚、花牌を含む | 108 枚、萬・筒・索のみ |
| 基本の試合長 | 東風戦または半荘戦 | 厳格 MCR は純得点 0、固定 16 局、連荘なし | T/TFMJ の一室は既定 8 副、純得点 0 |
| 和了条件 | 役が必要。最低翻はドラを除く役だけで判定 | 花牌分を除いて 8 fan 以上 | 平胡 0 fan、合計 3 fan / 8 単位上限、ツモは各家 +1 |
| チー | 可能 | 可能 | 不可 |
| 複数ロン | `HEAD_BUMP` / `MULTI_RON` | 一人だけ | 複数可、和了者を除いて続行 |
| 特殊な開始処理 | なし | 二個のサイコロを二回、開門、跳牌、補花は選択制 | 直接定欠、換三張なし |
| 規範 | 雀魂公式四人段位戦 | EMA/WMO Green Book | T/TFMJ 01—2024 |

詳しいルール：

- [雀魂風リーチ麻雀](./docs/ja-JP/rules/riichi.md)
- [国標麻雀](./docs/ja-JP/rules/gb.md)
- [四川麻雀](./docs/ja-JP/rules/sichuan.md)

各ルールページには、規範となる公式文書または団体標準と、学習用の YouTube / Bilibili 動画を分けて掲載しています。動画は一般的な実卓や別の地域・大会 profile を扱う場合があり、裁定元ではありません。

- リーチの `MAJSOUL` は雀魂公式四人段位戦を基準にします。`EARLY_KAN_DORA` は槓ドラ表示時期だけを変える互換設定で、完全な WRC 規則ではありません。旧保存値 `TOURNAMENT` は同じ互換動作としてだけ読み込みます。
- `GB` は EMA/WMO Green Book を規範とします。vendored `GB-Mahjong` と規則書が食い違う場合、実装側を修正します。
- `SICHUAN` は T/TFMJ 01—2024 を規範とし、換三張、末四強制和、固定花猪罰など他 profile の規則を既定動作へ混ぜません。

## 基本操作

| 目的 | コマンドまたは操作 |
| --- | --- |
| ヘルプ | `/mahjong help` |
| 卓へ参加 | 席ラベルをクリック、または `/mahjong join <tableId>` |
| 準備状態を切り替える | 自分の席ラベルをクリック、または `/mahjong start` |
| 卓を離れる | `/mahjong leave` |
| 観戦する | `/mahjong spectate <tableId>` |
| モードを選ぶ | `/mahjong mode <MAJSOUL_TONPUU|MAJSOUL_HANCHAN|GB|SICHUAN>` |
| ルールを確認・変更する | `/mahjong rule [key] [value]` |
| ツモ / ロン | `/mahjong tsumo` / `/mahjong ron` |
| チー / ポン / 明槓 | `/mahjong chii <tileA> <tileB>` / `/mahjong pon` / `/mahjong minkan` |
| 暗槓・加槓 | `/mahjong kan <tile>` |
| 反応を見送る | `/mahjong skip` |
| 直近の結果を開く | `/mahjong settlement` |
| 段位を確認する | `/mahjong rank` |
| モード別ランキング | `/mahjong leaderboard [RIICHI|GB|SICHUAN]`（対局からの自動更新は `RIICHI` のみ） |

コマンド名、牌 ID、モード名、設定キーは翻訳せず、表示された文字列をそのまま入力してください。

## データ保存と InvSync

既定設定ではローカル H2 が有効です。MahjongPaper の SQL は、常に次のデータを管理します。

- 永続卓
- `round_history`
- `rank_history`
- 非権威のランキング投影

InvSync 2.x を利用できる場合、InvSync はオンラインプレイヤーの段位 profile を保存します。InvSync が存在しない、無効、API 非互換、または実行中に失敗した場合、既定のフォールバック設定では、SQL backend が有効かつ正常な場合に限って MahjongPaper 自身のデータベースへ切り替わります。SQL も利用できない場合、段位 backend は `UNAVAILABLE` になります。

現在、対局結果から段位と個人統計を自動更新するのは、4 人の人間プレイヤーによる `RIICHI` だけです。`GB` と `SICHUAN` の leaderboard selector は既存または移行済みデータを読み取れますが、そのモードの対局から自動更新されません。

InvSync の公開 addon API は `onSave` 時にデータを書き込むため、段位更新後から次回保存までにプロセスが異常終了すると、その更新が失われる可能性があります。InvSync の auto-save と world-save を有効にしてください。InvSync backend では `/mahjong rank` は、既に同期済みのオンラインプレイヤーに限られます。

## 設定と再読み込み

生成される設定の基礎テンプレートは [config.template.yml](./src/main/config-template/config.template.yml) です。

- 通常の設定変更：`/mahjong reload` で設定と CraftEngine bridge を再読み込み
- JAR、CraftEngine、サーバー本体、リソース生成方式の変更：完全再起動を推奨
- InvSync backend またはフォールバック方針の変更：完全再起動が必要

## ビルド

ビルド環境にも Java 21 以上が必要です。

```powershell
.\gradlew.bat build
```

通常のサーバー運用では、各プラットフォームの GB-Mahjong native library を含む公式配布 JAR の利用を推奨します。

## クレジットとライセンス

第三者コード、画像、音声、名称、商標は、それぞれのライセンスまたは利用条件に従います。特に再配布時は、次の文書を必ず確認してください。

- [LICENSE](./LICENSE)
- [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)
- [resourcepack/ATTRIBUTION.md](./resourcepack/ATTRIBUTION.md)
- [あみたろの声素材 使用報告案](./docs/amitaro-usage-report.ja.md)

必須の音声クレジット：

> Voice: Amitaro's Voice Material Studio (<https://amitaro.net/>)<br>
> 音声素材：あみたろの声素材工房 (<https://amitaro.net/>)

MahjongPaper のオリジナルソースコードと独自作成素材は、個別に別条件が示されていない限り MIT License です。一方、実行可能な配布物は GPL の Sparrow YAML とリンクし、GPL の Sparrow Reflection を内包するため、組み合わせた実行可能ソフトウェアは GPL-3.0-only として提供されます。バイナリを再配布する場合は、対応するソースとビルド手順、および第三者通知を保持してください。
