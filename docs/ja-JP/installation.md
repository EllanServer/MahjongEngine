# インストールと初期設定

言語： [English](../installation.md) · [简体中文](../installation.zh-CN.md) · **日本語**

[日本語ドキュメント一覧へ戻る](./README.md) | [プロジェクト概要](../../README.ja-JP.md)

このページでは、MahjongPaper を Paper/Folia サーバーへ導入し、最初のゲームルームと卓を作成するまでを説明します。

対局規則は別ページです：[リーチ麻雀](./rules/riichi.md) · [国標麻雀](./rules/gb.md) · [四川麻雀](./rules/sichuan.md)

## 動作要件

| 項目 | 要件 |
| --- | --- |
| Java | Java 21 以上 |
| サーバー | Paper または Folia。配布物の開発基準は Paper 1.20.1、`api-version: 1.20` |
| 必須プラグイン | CraftEngine 26.7 以上 |
| 任意プラグイン | InvSync 2.x |
| クライアント | バニラ互換。専用 Mod は不要 |

CraftEngine は、麻雀牌アイテム、卓と席の furniture hitbox、操作イベント、culling、リソース配信を担当します。CraftEngine が存在しない、26.7 より古い、または必要 API と互換性がない場合、MahjongPaper は正常に起動しません。

InvSync は必須ではありません。InvSync を導入しない場合も、既定の H2 データベースで段位 profile を保存できます。現在、対局結果から段位と個人統計を自動更新するのは、4 人の人間プレイヤーによるリーチ対局だけです。

## 配布 JAR を選ぶ

通常のサーバー運用では、リリースで提供される universal plugin JAR を使用してください。現在の universal JAR には、GB-Mahjong 用の Linux x86_64、Windows x86_64、macOS x86_64 向け native library がまとめられています。

- `sources` と付いた JAR：ソース閲覧用。サーバーへ導入しません。
- `dev` と付いた JAR：開発用。通常運用には使用しません。
- platform JAR：特定 OS 用。配布元が明示した用途以外では universal JAR を優先します。

Windows 用 native library には、必要に応じて `libwinpthread-1.dll` も JAR 内に含まれます。公式配布物を利用するサーバー管理者が CMake や MinGW を別途用意する必要はありません。

## 必須・任意プラグインを配置する

サーバーを停止した状態で、次のファイルを `plugins` ディレクトリへ配置します。

```text
plugins/
├── CraftEngine.jar       # 必須、26.7 以上
├── MahjongPaper.jar      # 必須
└── InvSync.jar           # 任意
```

AntiGriefLib、sparrow-heart、sparrow-reflection、sparrow-yaml など、MahjongPaper が内部または Paper のライブラリローダー経由で利用するライブラリを、サーバー管理者が個別のサーバープラグインとして追加する必要はありません。

## 初回起動

1. Java 21 以上でサーバーを完全起動します。
2. CraftEngine が先に有効化され、その後 MahjongPaper が有効化されたことをコンソールで確認します。
3. `plugins/MahjongPaper/config.yml` が生成されたことを確認します。
4. `plugins/CraftEngine/resources/mahjongpaper` が生成されたことを確認します。
5. CraftEngine 側でリソース pack を生成し、クライアントへ正しく配信します。
6. 管理者として参加し、ゲームルームを作成します。

生成設定の原型は [config.template.yml](../../src/main/config-template/config.template.yml) です。依存関係の宣言は [plugin.yml](../../src/main/resources/plugin.yml) と [paper-plugin.yml](../../src/main/resources/paper-plugin.yml) で確認できます。

## 日本語表示

ゲーム内メッセージは、プレイヤーの Minecraft クライアント locale を自動的に使用します。クライアントが日本語なら、通常は `ja-JP` bundle が選択されます。サーバー共通の言語を設定する項目はありません。

次の内容は翻訳せず、そのまま入力してください。

- `/mahjong` 以下のコマンド
- `MAJSOUL_HANCHAN`、`GB`、`SICHUAN` などのモード ID
- `m1`、`p9`、`red_dragon` などの牌 ID
- `database.connection.type` などの設定キー

設定ファイル内の説明コメントは、現時点では英語・簡体字中国語・繁体字中国語のみです。

## CraftEngine bundle とリソース

既定では起動時に次の場所へ bundle が出力されます。

```text
plugins/CraftEngine/resources/mahjongpaper
```

主な内容：

```text
pack.yml
configuration/items/mahjong_tiles.yml
resourcepack/assets/mahjongcraft/...
```

牌や卓が正しく見えない場合は、次の順に確認してください。

1. CraftEngine 26.7 以上が有効か。
2. `mahjongpaper` bundle が生成されたか。
3. CraftEngine が resource pack を正常にビルドしたか。
4. クライアントが resource pack を受信・適用したか。
5. `/mahjong render` で卓を再描画できるか。
6. `/mahjong inspect` で anchor と向きを確認できるか。

## データベースと永続化

既定設定ではデータベースが有効で、接続方式はローカル H2 です。

```yaml
database:
  enabled: true
  connection:
    type: h2
```

H2 は小規模サーバーや単一サーバー運用に適しています。共有 DB、外部バックアップ、長期運用が必要なら、`database.connection.type` を MariaDB または MySQL に変更し、接続先と認証情報を設定します。

SQL が有効で正常な場合に MahjongPaper が管理するデータ：

- 永続卓
- `round_history`
- `rank_history`
- `player_rank_mode` のローカル段位、または InvSync 使用時の非権威投影
- モード別ランキング投影

InvSync を利用しても、永続卓や履歴、ランキング投影を SQL から移動してはいけません。

### 再起動後に復元されるもの

- 卓 ID と位置
- 卓主
- ゲームモード
- 卓のルール設定

### 再起動後に復元されないもの

- 進行中の手牌
- 牌山と王牌
- 現在点数を含む進行中の試合状態
- 鳴き・ロンの反応待ち
- 行動タイマー

再起動後、プレイヤーは再度着席・準備して新しい試合を開始します。

## InvSync を使用する場合

InvSync 2.x が有効で API 互換なら、InvSync はオンラインプレイヤーの段位 profile を主データとして保持します。現在の自動更新対象は、4 人の人間プレイヤーによる `RIICHI` の完了対局だけです。`GB` と `SICHUAN` の selector は既存または移行済みデータを読めますが、そのモードの対局から自動更新されません。

```yaml
ranking:
  enabled: true
  playerStorage:
    invSync:
      enabled: true
      fallbackToDatabase: true
```

`fallbackToDatabase: true` の既定方針では、SQL backend が有効かつ正常な場合に限り、次の場合に切り替わります。

- InvSync がインストールされていない
- InvSync または連携設定が無効
- 起動時の API probe に失敗した
- addon callback 中に API 非互換や実行時エラーが発生した

SQL が無効または初期化に失敗した場合、段位 backend は `UNAVAILABLE` です。既定の `database.failOnError: false` はデータベース失敗後もプラグインを起動しますが、その状態では永続化されません。牌卓、履歴、段位の保存を必須にする本番サーバーでは `database.failOnError: true` を推奨します。

InvSync を意図的に使用しない場合は、`ranking.playerStorage.invSync.enabled: false` にします。`fallbackToDatabase: false` の状態で InvSync を利用できないと、段位 backend は `UNAVAILABLE` になります。通常の本番環境では推奨しません。

### 保存タイミングの注意

公開 addon API では、MahjongPaper のデータを InvSync の `onSave` callback 中に書き込みます。段位を更新した後、次の InvSync 保存より前にサーバープロセスが異常終了すると、その更新がまだ永続化されていない可能性があります。

- InvSync の auto-save を有効にする
- InvSync の world-save を有効にする
- 自動保存間隔を適切に短くする
- SQL のランキング投影は監査に利用しても、InvSync へ自動で逆流させない

InvSync の公開 offline API を直接利用するには、その API JAR の型を継承する必要があります。このリポジトリは提供されていない Maven 座標や API class を捏造・同梱しません。そのため InvSync backend の `/mahjong rank` は、既に同期済みのオンラインプレイヤーに限られます。

backend はプラグイン起動時に選択されます。InvSync の有効/無効や fallback 方針を変更した場合は、`/mahjong reload` ではなくサーバーを完全再起動してください。

詳細は [InvSync プレイヤー段位接続ガイド](../invsync-player-rank.zh-CN.md) を参照してください。

## 最初の卓を作る

既定ではゲームルーム外に新しい卓を作成できません。まず管理者権限 `mahjongpaper.admin` を確認します。

### 選択 wand を使う方法

```text
/mahjong room wand
```

1. 部屋の一方の角を左クリックします。
2. 反対側の角を右クリックします。
3. シアン色の particle 枠が、プレイヤーと卓の表示高さまで覆っているか確認します。
4. ゲームルームを保存します。

```text
/mahjong room create main-hall メインホール
/mahjong room info main-hall
```

5. そのルーム内に立ち、卓を作成します。

```text
/mahjong create
```

### 現在位置から簡易ルームを作る方法

選択範囲がない状態で実行すると、現在位置と `gameRooms.defaultRadius` / `gameRooms.defaultHeight` を使います。

```text
/mahjong room create quick-room
/mahjong create
```

### プレイヤーを参加させる

1. 東・南・西・北の席ラベルをクリックして着席します。
2. 足りない席は卓主または管理者が `/mahjong addbot` で補えます。
3. 各プレイヤーが自分の席ラベル、または `/mahjong start` で準備します。
4. 4 席が埋まり、全員が準備すると自動的に開始します。

卓を作成したプレイヤーは卓主になりますが、自動着席はしません。卓主は `/mahjong table` から、モード、ルール、Bot、再描画、開始、削除を管理できます。

## `reload` と完全再起動

| 変更内容 | 推奨操作 |
| --- | --- |
| 通常のルール・表示設定 | `/mahjong reload` |
| CraftEngine bridge の再構築 | `/mahjong reload`、解決しなければ完全再起動 |
| MahjongPaper JAR の交換 | 完全再起動 |
| CraftEngine 本体の交換 | 完全再起動 |
| Paper/Folia または Java の変更 | 完全再起動 |
| resource pack 生成方式の変更 | 完全再起動 |
| InvSync backend / fallback 方針 | 完全再起動 |

対局中の設定変更は、現在の一局を途中で別ルールへ変えるものではありません。モードや主要ルールは、卓が待機状態のときに変更してください。

## トラブルシューティング

### プラグインが起動しない

- Java が 21 以上か確認します。
- CraftEngine が存在し、26.7 以上か確認します。
- コンソールの API compatibility probe エラーを確認します。
- `sources` / `dev` JAR を誤って入れていないか確認します。

### 国標麻雀だけ利用できない

- 公式 universal JAR を使用しているか確認します。
- コンソールで GB-Mahjong native library のロード失敗を確認します。
- OS と CPU architecture が配布物の対象か確認します。

### `/mahjong create` が拒否される

- `mahjongpaper.admin` 権限を確認します。
- `gameRooms.restrictNewTables: true` なら、ゲームルーム内に立っているか確認します。
- `/mahjong room info <id>` で world と範囲を確認します。

### `/mahjong rank` が利用できない

- `ranking.enabled: true` を確認します。
- SQL backend なら、データベース接続を確認します。
- InvSync backend なら、そのプレイヤーがオンラインで同期済みか確認します。
- InvSync が利用不能で `fallbackToDatabase: false` なら、backend は `UNAVAILABLE` です。

### 牌や卓が見えない・クリックできない

- CraftEngine bundle と resource pack を確認します。
- `/mahjong render` を実行します。
- `/mahjong inspect` で表示 anchor と方向を確認します。
- 保護プラグインが家具操作を拒否していないか確認します。

### 俯瞰表示から戻れない

画面中央の **「席に戻る」** を選びます。操作できない場合だけ Shift を押して復帰してください。サーバーバージョンが必要な camera entity と非互換なら、俯瞰表示は利用不可として案内されます。
