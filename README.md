# McRemote

[マイクラリモコン](https://mc-remote.com/)（Minecraft Remote / mc-remote）のサーバー側プラグインです。
Paper サーバーで動き、Scratch や Python などのクライアントから届いた命令を、マインクラフトの世界へ反映します。

🏠 **公式サイト**: [mc-remote.com](https://mc-remote.com/)

> [!NOTE]
> **🌐 言語方針について / Language Policy**\
> 本リポジトリは、一次情報（SSOT）の鮮度と正確性を保つため、日本語を正本として記述しています。多言語参加やIssue/PRの利用方針については [主要言語についての方針転換 / Language Policy](https://github.com/Naohiro2g/mc-remote-knowledge/blob/main/LANGUAGE_POLICY.md) をご覧ください。\
> *This repository is maintained in Japanese as its primary Single Source of Truth (SSOT). Multi-language contributions are welcome. Please see our [Language Policy](https://github.com/Naohiro2g/mc-remote-knowledge/blob/main/LANGUAGE_POLICY.md).*

---

## マイクラリモコンとは

マイクラリモコンは、コーディングでマイクラの世界を動かしながら「学び方を学ぶ」ためのオープンソースのツール群です。
Scratch や Python などで書いたプログラムから、マインクラフトのサーバーへブロックを置いたり、プレイヤーを動かしたりできます。
マイクラのアプリは Java 版でも統合版でも接続できます。

- サーバーのプラグイン（McRemote、このリポジトリ）
- 各言語のクライアント（Scratch、Python、Java など）
- 通信の中身を観察する WireScope
- サーバーの構築・運用パッケージ（mc-remote-stack）

はじめかた、考え方、ロードマップは公式ホームページへ：<https://mc-remote.com/>

### McRemote の役割

McRemote は Paper サーバーで動くプラグインで、各言語のクライアントからの接続を受ける側です。
クライアントのプログラムは、ゲームとは別の接続（既定の port `25575`）で McRemote に命令を送ります。
McRemote はその命令をサーバーの世界へ反映し、結果を返します。

まずは、公式ホームページで紹介している箱庭サーバー（マインクラフト - Scratch）で試してから、自分のサーバーへのプラグイン導入を検討できます。<https://mc-remote.com/#quickstart>

---

## 自分のサーバーに入れる（最短手順）

前提: Paper `1.21.11` のサーバーと Java 21。

### Step 1: プラグインを置いて起動する

[リリース一覧](https://github.com/Naohiro2g/McRemote/releases)から最新の JAR（`mc-remote-<Minecraft の版>-<版>.jar`）を取得し、
サーバーの `plugins/` に置いてサーバーを起動します。
認証は最初から有効で、認証情報の保存領域も起動時に自動で作られます。

### Step 2: クライアントから接続する

クライアントの接続先を自分のサーバー（port `25575`）にして、プログラムを実行します。
クライアントが表示する `/mcremote pair NNN-NNN` をゲーム内のチャットで実行すると、接続が認証されます。

Python なら、[minecraft-remote-api の最短クイックスタート](https://github.com/Naohiro2g/minecraft-remote-api#3分で動かす最短クイックスタート)の
`address` を自分のサーバーに変えて試せます。

---

## 設定

設定ファイルは `plugins/McRemote/config.yml` です。初回起動時に既定値で作られます。変更したらサーバーを再起動します。

- port `25575` は、信頼できる client からだけ到達できるよう制限します。
- LuckPerms を使う場合は、接続する player に `mcr.online` または `mcr.offline` と
  `mcr.build.range` meta を付与します。
- `auth.enforcement` の既定は `true` です。`false` は認証障害の診断や移行試験のために、運用者が明示する
  一時的な bypass です。token なしで接続できてしまうので、loopback または隔離した検証環境だけで使用します。
- 認証情報の保存先が起動時に欠けている場合、プラグインは新しい保存領域を自動で作り、サーバーログに通知します。
  以前の token は使えなくなるため、client で再ペアリングしてください。保存済みデータの破損や ID 不一致では
  認証を停止し、原因をログに出します。

### 設定項目

接続と権限:

| 項目 | 既定値 | 意味 |
| --- | --- | --- |
| `api_port` | `25575` | client が接続する TCP port。ゲームの port とは別です |
| `luckperm_permissions.online` | `mcr.online` | player がゲームに入っている（online）間、client からの建築などを許す LuckPerms の permission node |
| `luckperm_permissions.offline` | `mcr.offline` | player がゲームに入っていない（offline）間も、client からの建築などを許す permission node |
| `luckperm_permissions.build.range` | `mcr.build.range` | player ごとの build range を読む LuckPerms の meta key |
| `default_build_range` | `1000` | LuckPerms が無いときなどに使う build range（ブロック）。建築原点から X 方向と Z 方向それぞれにこの距離まで建築できます |
| `supported_mc_versions` | `["1.21.11"]` | hello で client に伝える対応 Minecraft の版。空にすると、動いているサーバーの版を伝えます |

認証（`auth`）:

| 項目 | 既定値 | 意味 |
| --- | --- | --- |
| `auth.enforcement` | `true` | 接続に token を求めるか。`false` は一時的な bypass に限ります（上記） |
| `auth.pair_code_ttl_seconds` | `120` | ペアリングコードの有効期間（秒） |
| `auth.session_token_ttl_seconds` | `7200` | ペアリングで発行する session token の有効期間（秒） |
| `auth.max_sessions_per_uuid` | `16` | 同じ player が同時に持てる接続の数 |
| `auth.credential_store_path` | `credential-store/snapshot.json` | 認証情報の保存先。相対パスは `plugins/McRemote` から |
| `auth.revocation_authority_path` | `credential-revocations` | 失効記録の保存先。上と入れ子にしないでください |
| `auth.max_long_lived_credentials_per_uuid` | `16` | player ごとの長期 credential の上限。同時接続数とは別です |

実行時の上限: プラグインが一度に抱える量の上限です。protocol の定数ではありません。サーバーの負荷を見て調整します。

認証前にも接続・受信・pairing の上限を適用します。接続数や頻度、入力サイズ、待ち時間、
保留 pair 数が上限を超えた場合は TCP 接続を閉じます。新しい認証 error や token を返さず、
自動再試行もしません。接続数と頻度は Bridge 配下も含めたサーバー全体の値です。
以下は有限な暫定値で、授業相当の負荷での本較正は別途必要です。

| 項目 | 既定値 | 意味 |
| --- | --- | --- |
| `connection.max_connections` | `128` | hello 完了後も含む全接続数 |
| `connection.max_pre_hello_connections` | `32` | hello 完了前の接続数 |
| `connection.accepts_per_second` | `32` | 1 秒の受付窓内の接続試行数。超過は session 作成前に拒否 |
| `connection.max_frame_bytes` | `65536` | 改行を除く入力 1 行の UTF-8 byte 数。hello 後も適用 |
| `connection.command_queue_bytes` | `1048576` | 接続ごとの入力 queue の推定 String 保存量。各行を `40 + 2 × UTF-16 長` byte と計算 |
| `connection.pre_hello_idle_seconds` | `30` | hello 前の入力待ち時間（秒） |
| `connection.hello_timeout_seconds` | `180` | 接続から hello 成功までの絶対期限（秒）。pairing の継続でも延長しない |
| `auth.pair_begins_per_second` | `8` | 1 秒の窓内に受け付ける pairBegin 数 |
| `auth.pair_polls_per_second` | `128` | 1 秒の窓内に受け付ける pairPoll 数 |
| `auth.max_pending_pairs` | `128` | 承認待ち・token 受け取り待ちの pair の総数 |

各値は正の整数で指定します。既定の hello 期限は pair code の 120 秒を確保しています。
`auth.pair_code_ttl_seconds` を延ばす場合は、hello 期限も余裕を持って延ばしてください。
入力 queue は件数か保存量の上限に達すると受信側を待機させ、TCP の backpressure を掛けます。
hello 前はこの待機にも絶対期限を適用します。

| 項目 | 既定値 | 意味 |
| --- | --- | --- |
| `connection.command_queue_capacity` | `1024` | 接続ごとに、受け付けて未実行の命令を溜められる数 |
| `connection.response_queue_capacity` | `64` | 接続ごとに、未送信の応答を溜められる数。あふれるとその接続を切ります（黙って捨てません） |
| `events.ring_capacity` | `256` | 接続ごとに溜めておくイベントの数 |
| `events.ring_bytes` | `262144` | 溜めておくイベントの合計バイト数の上限 |
| `events.poll_default` | `64` | `events.poll` が件数を指定しないときに返す件数 |
| `events.poll_limit` | `64` | `events.poll` が一度に返す件数の上限。client の指定がこれより大きければ、この値に縮めます |
| `entities.handle_capacity` | `256` | 接続ごとに発行できる entity handle の数 |
| `entities.nearby_max_radius` | `64` | `world.getNearbyEntities` で指定できる半径の上限（ブロック）。下げられますが、64 より上には上げられません |
| `entities.nearby_max_entities` | `64` | `world.getNearbyEntities` で一度に返せる entity の数の上限。下げられますが、64 より上には上げられません |
| `particles.max_count` | `1000` | 1 回の `spawnParticle` で出せる数の上限 |
| `work.per_request` | `4096` | 1 つの命令の作業量の上限 |
| `work.per_session_tick` | `4096` | 1 tick あたり、接続ごとの作業量の上限 |
| `work.per_player_tick` | `8192` | 1 tick あたり、player ごとの作業量の上限 |
| `work.global_per_tick` | `32768` | 1 tick あたり、サーバー全体の作業量の上限 |
| `sound.per_connection_per_tick` | `16` | 1 tick に、接続ごとに鳴らせる音の数（`world.playSound`、`world.playBlockSound`）。超えると `backpressure` |
| `sound.global_per_tick` | `64` | 1 tick に、サーバー全体で鳴らせる音の数 |
| `lightning.connection_cooldown_ticks` | `20` | 同じ接続から落雷を続けて呼べる間隔（tick） |
| `lightning.player_cooldown_ticks` | `20` | 同じ player が落雷を続けて呼べる間隔（tick） |
| `lightning.global_per_tick` | `2` | サーバー全体で 1 tick に起こせる落雷の数 |
| `lightning.rolling_window_ticks` | `20` | 下の上限を数える期間（tick） |
| `lightning.global_per_window` | `8` | 上の期間にサーバー全体で起こせる落雷の数 |

以前の `config.yml` にある `b5:`／`b7:` の項目は、起動時に同じ意味の新しい項目へ値を移して削除します。
変えていた値は引き継がれ、移した項目と削除した項目はサーバーログに出ます。新しい項目がすでにある場合は新しい項目が優先され、旧項目は削除されます。

## 主な capability

- stream-local な dimension／origin と、構造化された block set/get
- paired player の position／pose／direction
- bounded event poll と opaque entity handle
- particle（色・ブロックの指定、全員／本人への表示）、entity spawn、height query
- 周囲の entity の検索、position／pose／direction の取得・変更、削除
- 落雷、位置やブロックからのサウンド再生
- sign の get／replace／1行 update
- catalog、pairing、session／long-lived credential
- notification と `connection.flush` barrier

使える命令は[公開版の API 一覧](https://mc-remote.com/api/)で確認できます。
params、result、error の詳しい契約は
[protocol SSOT](https://github.com/Naohiro2g/mc-remote-knowledge/tree/main/10-protocol) を参照してください。

## 更新と前の版への戻し

更新前にサーバーを停止し、使用中の JAR と `plugins/McRemote/config.yml` を控え、world をバックアップします。
新しい JAR へ置き換えるときは、`plugins/` に McRemote の JAR が1つだけある状態にして起動してください。
クライアントも対象リリースに対応する版へ揃えます。認証情報の保存領域は保持します。

beta の問題は通常、次の beta で修正します。前の版へ戻す必要がある場合は、同じ Minecraft の版の中で、
控えておいた JAR とその版の設定を使い、クライアントの版も合わせてください。設定には上記の起動時移行があるので、
以前の JAR だけに戻しても、設定値が引き継がれるとは限りません。world の変更は JAR の交換では元に戻りません。
版を戻した後は、起動・認証・代表操作を確認します。release ごとの検証状況は
[release gate の記録](https://github.com/Naohiro2g/mc-remote-knowledge/blob/main/00-hub/release-gate-notes_ja.md)を参照してください。

## 制約と安全

- beta 間では protocol minor と利用可能 method が変わることがあります。client と plugin の
  protocol version を合わせてください。
- world mutation は取り消し transaction ではありません。重要な world はバックアップし、
  build origin と range を確認してから接続してください。
- notification は個別 result を返しません。順序の確定が必要な場合は `connection.flush` を使います。
- token、credential store、private host、FTP 設定を repository に commit しないでください。
- `reloadPlugin`、`live`、deploy task はローカル server や外部環境を変更します。通常の build／test
  とは分けて実行してください。

## 開発

配布版・Minecraft／Paper API の版・toolchain は [`gradle.properties`](gradle.properties) が所有します。
hello で使う protocol 版は [`ProtocolInfo.PROTOCOL`](src/main/java/club/code2create/mcremote/ProtocolInfo.java) が所有します。
設計と wire contract の正本は
[mc-remote-knowledge](https://github.com/Naohiro2g/mc-remote-knowledge) にあります。

```sh
./gradlew build
```

別の Paper API に対する一時的な compatibility compile は、対象の検証指示に従い、
`-PmcJavaVersion`、`-PpaperApiVersion`、`-PpluginApiVersion` で値を指定して
`compileJava` を実行します。compile の成功だけでは、その Minecraft 版での動作確認や
対応版の追加を意味しません。

local server task は環境固有の server directory を使用します。

```sh
./gradlew runServer
./gradlew stopServer
./gradlew restartServer
```

### smoke test

`scripts/smoke_test.py` は Python 標準ライブラリだけで接続し、hello、build context、
block set/get、catalog error を一往復確認します。`auth.enforcement: false` にした隔離した開発設定で、
サーバー起動後に実行します。

```sh
python3 scripts/smoke_test.py \
  --host 127.0.0.1 --port 25575 \
  --dimension overworld --ox 200 --oy 0 --oz 200
```

成功時は最後に `PASS` が表示されます。この smoke は実 world を変更し、既定では
`minecraft:overworld` の絶対座標 `(200,0,200)` から上へ4ブロックを置きます。使い捨ての
開発 world で実行するか、確認後にその4ブロックを削除してください。

pairing と player position／pose の代表往復は、サーバー内で pair code を承認できる状態で
次を使えます。

```sh
python3 scripts/player_test.py --host 127.0.0.1 --port 25575
```

検証スクリプトはこの checkout 内で使います。protocol の既定値は
`scripts/protocol_version.py` が `ProtocolInfo.PROTOCOL` から読み取るので、スクリプトごとの更新は不要です。
別の版を検証するときは `--protocol` で指定できます。`live_auto.py`、`live_human.py`、
`sign_live_auto.py` の `--expect-mc` は、検証指示に書かれた Minecraft 版を明示してください。
接続先の `hello.mc_version` が違う場合は、試験本体を実行せず失敗します。

## ライセンス

issue と contribution は [GitHub repository](https://github.com/Naohiro2g/McRemote) で受け付けます。
ライセンスは [LICENSE](LICENSE) を参照してください。本 project は
[wensheng/JuicyRaspberryPie](https://github.com/wensheng/JuicyRaspberryPie) を起点にしています。
