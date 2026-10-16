# McRemote

[マイクラリモコン](https://mc-remote.com/)（Minecraft Remote / mc-remote）のサーバー側プラグインです。Paper サーバーで動き、Scratch や Python などのクライアントから届いた命令を、マインクラフトの世界へ反映します。

🏠 **公式サイト**: [mc-remote.com](https://mc-remote.com/)

> [!NOTE]
> **🌐 言語方針について / Language Policy**\
> 本リポジトリは、一次情報（SSOT）の鮮度と正確性を保つため、日本語を正本として記述しています。多言語参加やIssue/PRの利用方針については [主要言語についての方針転換 / Language Policy](https://github.com/Naohiro2g/mc-remote-knowledge/blob/main/LANGUAGE_POLICY.md) をご覧ください。\
> *This repository is maintained in Japanese as its primary Single Source of Truth (SSOT). Multi-language contributions are welcome. Please see our [Language Policy](https://github.com/Naohiro2g/mc-remote-knowledge/blob/main/LANGUAGE_POLICY.md).*

---

## マイクラリモコンとは

マイクラリモコンは、コーディングでマイクラの世界を動かしながら「学び方を学ぶ」ためのオープンソースのツール群です。Scratch や Python などで書いたコードから、マインクラフトのサーバーへブロックを置いたり、プレイヤーを動かしたりできます。

ヘルパープラグインとしてGeyser/Floodgateを使用することで、Java 版でも統合版でも接続できます。また、ViaVersion / ViaBackwards を使うと、Java版のバージョンを気にせず接続できるようになります。

- サーバーのプラグイン（McRemote、このリポジトリ）
- 各言語のクライアント（Scratch、Python、Java など）
- 通信の中身を観察する WireScope
- サーバーの構築・運用パッケージ（mc-remote-stack）

はじめかた、考え方、ロードマップなどは、公式ホームページへ：<https://mc-remote.com/>

### McRemote の役割

McRemote は マイクラサーバー（Paper）で動くプラグインで、各言語のクライアントからの接続を受けるマイクラリモコンサーバーとして働きます。マイクラリモコンクライアントのコードは、マイクラ自体とは別の接続（ TCP ポート、規定番号は `25575`）で McRemote に命令を送ります。McRemote はその命令をマイクラサーバーの世界へ反映し、結果を返します。

マイクラサーバー（Paper）　----------------　マイクラアプリ（Java版、統合版）

マイクラリモコンサーバー（McRemote）　--------　マイクラリモコンクライアント（Scratch、Python など）

まずは、公式ホームページで紹介している箱庭サーバーセット（マインクラフト - Scratch）で気軽に試してから、自分のサーバーへのプラグイン導入を検討できます。<https://mc-remote.com/#quickstart>

---

## 自分のサーバーで動かしてみる（Pythonによる最短手順）

[minecraft-remote-api の最短クイックスタート](https://github.com/Naohiro2g/minecraft-remote-api#3分で動かす最短クイックスタート)の `address` を、箱庭サーバーから自分のサーバーに変えて試す流れです。

前提: プラグインMcRemoteのリリースに記載されている対応MCバージョンのPaperが、LAN内のPCやレンタルサーバーなど動いている。

### Step 1: プラグインを置いて起動する

[リリース一覧](https://github.com/Naohiro2g/McRemote/releases)から使いたいリリースのJARファイルをダウンロードし、サーバーの `plugins/` に置いてサーバーを起動します。

### Step 2: マイクラリモコンのPythonクライアントから接続する

クライアントコードで接続先を自分のサーバー（port `25575`）にして、コードを実行します。

### Step 3: ペアリングする

デフォルトでは、認証が有効になっています。クライアントが接続すると、McRemote はペアリングコードを生成し、クライアントに返します。クライアントが表示する `/mcremote pair NNN-NNN` をゲーム内のチャットコマンドとして実行すると、接続が認証されます。

2分以内に入力しないと、ペアリングコードは無効になるので、再度、新しいコードを取得してください。ペアリングが成功した場合、認証の有効期限は2時間です。期限が切れた場合は、再度ペアリングが必要です。

---

## おすすめプラグイン

[おすすめプラグインのページ](docs/recommended-plugins_ja.md)で、LuckPerms、ViaVersion / ViaBackwards、Geyser / Floodgate、DirectionHUD、WorldEdit、DiscordSRV、BlueMapを紹介しています。

## サーバーのJavaを選ぶ

Paper 1.21.11はJava 21、Paper 26.3はJava 25で起動します。[mise](https://mise.jdx.dev/lang/java.html)で、サーバーのフォルダーごとにJavaの版を固定してください。miseの導入・シェル設定後、そのフォルダーで次を実行すると`mise.toml`に設定が保存されます。

```sh
mise use --path mise.toml --pin java@25  # Paper 26.3。Paper 1.21.11のフォルダーでは java@21
mise exec -- java -version
mise exec -- java -jar paper.jar nogui
```

`--pin`でインストールした具体的な版を記録します。以後は、そのフォルダーで`mise exec --`を使うと、記録したJavaで起動できます。

## 設定

設定ファイルは `plugins/McRemote/config.yml` です。初回起動時に既定値で作られます。設定値を変更した場合は、サーバーを再起動して反映させてください。

- マイクラリモコンの通信のため、TCPポート（`api_port`、デフォルト値は`25575`）を使います。 **セキュリティに留意してポート開放してください。**
- プラグイン `LuckPerms` は、グループや大人数で使う場合には特におすすめですが、使う場合は、接続するプレイヤーに `mcr.online` または `mcr.offline` の権限、及び `mcr.build.range` と `mcr.build.blocks` についての meta を付与する必要があります。 **「なんか、よくわからないけど入れておこう」だと、マイクラリモコンが使えなくなってしまうので要注意。** 詳しくは、[LuckPermsの使い方](docs/recommended-plugins_ja.md#luckperms)へ。
- `auth.enforcement` の既定は `true` です。`false` は認証障害の診断や移行試験のための一時的なバイパスです。 **認証なしで接続できてしまう** ので、検証環境だけで使用します。
- 認証情報の保存先が起動時に欠けている場合、プラグインは新しい保存領域を自動で作り、サーバーログに通知します。この場合、以前の token は使えなくなり、再び、ペアリングが必要となります。保存済みデータの破損や ID 不一致などでは認証を停止し、原因をログに出します。

### 設定項目

接続と権限:

| 項目 | 既定値 | 意味 |
| --- | --- | --- |
| `api_port` | `25575` | client が接続する TCP port。ゲームの port とは別です |
| `luckperm_permissions.online` | `mcr.online` | プレイヤーがゲームに入っている（online）間に、client からの建築などを許す LuckPerms の permission node |
| `luckperm_permissions.offline` | `mcr.offline` | プレイヤーがゲームに入っていない（offline）ときに、client からの建築などを許す permission node |
| `luckperm_permissions.build.range` | `mcr.build.range` | プレイヤーごとの build range を読む LuckPerms の meta key |
| `luckperm_permissions.build.blocks` | `mcr.build.blocks` | プレイヤーごとの1操作あたりの対象block数を読む LuckPerms の meta key |
| `default_build_range` | `1000` | LuckPerms が無いときなどに使う build range（ブロック）。建築原点から X 方向と Z 方向それぞれにこの距離まで建築できます |
| `default_build_blocks` | `32768` | LuckPerms が無いときの1操作あたりの対象block数。既存configの明示値は `0` も含めて保持します |
| `supported_mc_versions` | 配布JARの宣言から生成 | helloはJAR同梱の対応版一覧を使います。この設定は広告を変更しません。既存値は保持し、宣言と違えば起動時にwarningを出します |

`mcr.build.blocks` は `world.setBlock`／`world.setBlocks` の1操作で指定する対象block数の上限です。`setBlocks` は両端を含む直方体の体積で数え、元から同じblockだった場所も含めます。
LuckPerms の継承・contextを含む実効metaを接続時に読み、変更は再接続で反映します。LuckPermsがある場合、meta未設定は `0`（block編集禁止）です。LuckPermsが無い場合はconfigの `default_build_blocks`（配布既定32768）を使います。metaの `0`、負値・不正な整数も編集を禁止します。

上限超過は既存の `build_denied` で拒否し、範囲内の一部だけを変更しません。

例えばグループごとに、次のように設定できます。

```text
/lp group visitor meta set mcr.build.blocks 256
/lp group member meta set mcr.build.blocks 32768
```

このmetaはwork予算を増やしません。既定の1要求のwork上限は32768、1接続の1tickのwork上限は4096です。 `setBlocks`は既定tick予算に収まる分ずつ施工し、同じ接続の後続操作を待たせます。id付き要求は全量の施工後に `result:null`を返し、FAST通知の後に送る`connection.flush`も完了まで待ちます。開始前に予算がまったく無ければid付きは`backpressure`、FASTは延期します。施工開始後の予算不足では進捗を保って待ちます。

途中失敗や切断では施工済み部分が残るため、操作全体の自動再送は避けてください。tokenに束縛されない明示的な認証bypassではこのプレイヤーmetaを適用せず、既存のwork予算を適用します。

認証（`auth`）:

| 項目 | 既定値 | 意味 |
| --- | --- | --- |
| `auth.enforcement` | `true` | 接続に token を求めるか。`false` は一時的な bypass に限ります（上記） |
| `auth.pair_code_ttl_seconds` | `120` | ペアリングコードの有効期間（秒） |
| `auth.session_token_ttl_seconds` | `7200` | ペアリングで発行する session token の有効期間（秒） |
| `auth.max_sessions_per_uuid` | `16` | 同じ プレイヤーが同時に持てる接続の数 |
| `auth.credential_store_path` | `credential-store/snapshot.json` | 認証情報の保存先。相対パスは `plugins/McRemote` から |
| `auth.revocation_authority_path` | `credential-revocations` | 失効記録の保存先。上と入れ子にしないでください |
| `auth.max_long_lived_credentials_per_uuid` | `16` | プレイヤーごとの長期 credential の上限。同時接続数とは別です |

認証情報は、すべてのOSでPaperが提供するSQLite JDBCを使って保存します。既存configの `auth.credential_store_path` に `.sqlite` を付けたDB（既定 `credential-store/snapshot.json.sqlite`）と、`auth.revocation_authority_path` に `-sqlite` を付けた別directory内の `authority.sqlite`（既定 `credential-revocations-sqlite/authority.sqlite`）を使います。旧JSONは読み取り・移行・削除・退避しません。旧ファイル方式からの更新時は、新しい認証domainが作られるため、ペアリングし直してください。既存の有効なSQLite DBは引き続き使います。

Minecraftサーバーのフォルダーは、OneDriveなどのクラウド同期の対象に置かないでください。

Java 25ではSQLite native libraryの読み込み時に警告が出ることがあります。読み込みは追加flagなしでも可能で、`java --enable-native-access=ALL-UNNAMED ... -jar paper.jar` とするとこの警告を抑えられます。

各DBとその `-wal` は一組の復旧単位です。稼働中のDBだけをコピーせず、整合したバックアップを取ってください。snapshotだけを復元する際にauthorityを書き戻さないことが、失効したtokenを再び有効にしないための条件です。明示resetでは旧SQLite stateを各DB内へ保持して、新しいdomainを作ります。

実行時の上限: プラグインが一度に抱える量の上限です。protocol の定数ではありません。サーバーの負荷を見て調整します。

認証前にも接続・受信・pairing の上限を適用します。接続数や頻度、入力サイズ、待ち時間、保留 pair 数が上限を超えた場合は TCP 接続を閉じます。新しい認証 error や token を返さず、
自動再試行もしません。接続数と頻度は Bridge 配下も含めたサーバー全体の値です。

接続・pairing の値は個別に設定せず、起動時の Paper の有効な `max-players` から内部テーブルを選びます。16 以下は16人向け、17–24は24人向け、25–32は32人向けです。33以上では32人向けの上限を使い、想定範囲外であることを起動ログへ通知します。設定人数は建築処理性能の保証ではありません。

以下は試用で較正する有限な暫定値です。

| 上限 | 16人向け | 24人向け | 32人向け |
| --- | ---: | ---: | ---: |
| 全接続数（hello後も含む） | 64 | 96 | 128 |
| hello前の同時接続数 | 32 | 48 | 64 |
| 1秒の受付窓内の接続試行数 | 80 | 112 | 144 |
| 1秒の窓内のpairBegin数 | 20 | 28 | 36 |
| 1秒の窓内のpairPoll数 | 64 | 96 | 128 |
| 保留pair数 | 32 | 48 | 64 |

人数に関係なく、入力1行は改行を除くUTF-8で64KiB、接続ごとの入力queueの保存量は1MiBです。保存量は各行を `40 + 2 × UTF-16長` byteで見積もります。これらはhello後も適用します。

hello前の入力待ちは30秒、hello成功までの絶対期限は180秒で、継続入力やpairingでも延長しません。`auth.pair_code_ttl_seconds` が120秒より長い場合は、絶対期限をその値＋60秒へ自動的に延ばします。hello成功後の入力待ちには、このtimeoutを適用しません。

以前の10個の個別設定キーは起動時に削除し、対象キーと内部テーブルへの切替をログへ通知します。Paperの `max-players` を変更した後は、サーバーを再起動してください。起動ログには選択テーブルと実効値を表示し、使用中は30秒間隔で変化のあった集計を表示します。

`rejected` は前回集計からの理由別件数、`*_peak` は起動後の最高値、`queue_waits` はqueue空き待ち回数です。通常の切断は上限拒否へ計上せず、IP・UUID・token・入力本文を集計へ含めません。停止時にも残りを出力します。入力 queue は件数か保存量の上限に達すると受信側を待機させ、TCP の backpressure を掛けます。hello 前はこの待機にも絶対期限を適用します。

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
| `work.per_request` | `32768` | 1 つの命令の作業量の上限。接続・プレイヤー・全体のtick予算も別に適用します |
| `work.per_session_tick` | `4096` | 1 tick あたり、接続ごとの作業量の上限 |
| `work.per_player_tick` | `8192` | 1 tick あたり、プレイヤーごとの作業量の上限 |
| `work.global_per_tick` | `32768` | 1 tick あたり、サーバー全体の作業量の上限 |
| `sound.per_connection_per_tick` | `16` | 1 tick に、接続ごとに鳴らせる音の数（`world.playSound`、`world.playBlockSound`）。超えると `backpressure` |
| `sound.global_per_tick` | `64` | 1 tick に、サーバー全体で鳴らせる音の数 |
| `lightning.connection_cooldown_ticks` | `20` | 同じ接続から落雷を続けて呼べる間隔（tick） |
| `lightning.player_cooldown_ticks` | `20` | 同じ プレイヤーが落雷を続けて呼べる間隔（tick） |
| `lightning.global_per_tick` | `2` | サーバー全体で 1 tick に起こせる落雷の数 |
| `lightning.rolling_window_ticks` | `20` | 下の上限を数える期間（tick） |
| `lightning.global_per_window` | `8` | 上の期間にサーバー全体で起こせる落雷の数 |

既存の `config.yml` に指定した `work.per_request` の値は維持します。既定値32768を使うには、この項目が未設定であるか、設定値を32768へ変更する必要があります。

以前の `config.yml` にある `b5:`／`b7:` の項目は、起動時に同じ意味の新しい項目へ値を移して削除します。変えていた値は引き継がれ、移した項目と削除した項目はサーバーログに出ます。新しい項目がすでにある場合は新しい項目が優先され、旧項目は削除されます。

## 主な API

- stream-local な dimension／origin と、構造化された block set/get
- paired プレイヤーの position／pose／direction
- bounded event poll と opaque entity handle
- particle（色・ブロックの指定、全員／本人への表示）、entity spawn、height query
- 周囲の entity の検索、position／pose／direction の取得・変更、削除
- 落雷、位置やブロックからのサウンド再生
- sign の get／replace／1行 update
- catalog、pairing、session／long-lived credential
- notification と `connection.flush` barrier

使える命令は[公開版の API 一覧](https://mc-remote.com/api/)で確認できます。

params、result、error の詳しい契約は [protocol SSOT](https://github.com/Naohiro2g/mc-remote-knowledge/tree/main/10-protocol) を参照してください。

## 更新と前の版への戻し

更新前にサーバーを停止し、使用中の JAR と `plugins/McRemote/config.yml` を控え、world をバックアップします。新しい JAR へ置き換えるときは、`plugins/` に McRemote の JAR が1つだけある状態にして起動してください。クライアントも対象リリースに対応する版へ揃えます。認証情報の保存領域は保持します。

beta の問題は通常、次の beta で修正します。前の版へ戻す必要がある場合は、同じ Minecraft の版の中で、控えておいた JAR とその版の設定を使い、クライアントの版も合わせてください。設定には上記の起動時移行があるので、以前の JAR だけに戻しても、設定値が引き継がれるとは限りません。world の変更は JAR の交換では元に戻りません。版を戻した後は、起動・認証・代表操作を確認します。release ごとの検証状況は [release gate の記録](https://github.com/Naohiro2g/mc-remote-knowledge/blob/main/00-hub/release-gate-notes_ja.md)を参照してください。

## 制約と安全

- beta 間では protocol minor と利用可能 method が変わることがあります。client と plugin の protocol version を合わせてください。
- world mutation は取り消し transaction ではありません。重要な world はバックアップし、 build origin と range を確認してから接続してください。
- notification は個別 result を返しません。順序の確定が必要な場合は `connection.flush` を使います。
- token、credential store、private host、FTP 設定を repository に commit しないでください。
- `reloadPlugin`、`live`、deploy task はローカル server や外部環境を変更します。通常の build／test とは分けて実行してください。

## 開発

配布版・Minecraft／Paper API の版・toolchain は [`gradle.properties`](gradle.properties) が所有します。

hello で使う protocol 版は [`ProtocolInfo.PROTOCOL`](src/main/java/club/code2create/mcremote/ProtocolInfo.java) が所有します。

設計と wire contract の正本は [mc-remote-knowledge](https://github.com/Naohiro2g/mc-remote-knowledge) にあります。

```sh
./gradlew build
```

別の Paper API に対する一時的な compatibility compile は、対象の検証指示に従い、 `-PmcJavaVersion`、`-PpaperApiVersion`、`-PpluginApiVersion` で値を指定して `compileJava` を実行します。compile の成功だけでは、その Minecraft 版での動作確認や対応版の追加を意味しません。

local server task は環境固有の server directory を使用します。

```sh
./gradlew runServer
./gradlew stopServer
./gradlew restartServer
```

### smoke test

`scripts/smoke_test.py` は Python 標準ライブラリだけで接続し、hello、build context、 block set/get、catalog error を一往復確認します。`auth.enforcement: false` にした隔離した開発設定で、サーバー起動後に実行します。

```sh
python3 scripts/smoke_test.py \
  --host 127.0.0.1 --port 25575 \
  --dimension overworld --ox 200 --oy 0 --oz 200
```

成功時は最後に `PASS` が表示されます。この smoke は実 world を変更し、既定では `minecraft:overworld` の絶対座標 `(200,0,200)` から上へ4ブロックを置きます。使い捨ての開発 world で実行するか、確認後にその4ブロックを削除してください。

pairing と プレイヤーposition／pose の代表往復は、サーバー内で pair code を承認できる状態で次を使えます。

```sh
python3 scripts/player_test.py --host 127.0.0.1 --port 25575
```

検証スクリプトはこの checkout 内で使います。protocol の既定値は `scripts/protocol_version.py` が `ProtocolInfo.PROTOCOL` から読み取るので、スクリプトごとの更新は不要です。
別の版を検証するときは `--protocol` で指定できます。`live_auto.py`、`live_human.py`、 `sign_live_auto.py` の `--expect-mc` は、検証指示に書かれた Minecraft 版を明示してください。
接続先の `hello.mc_version` が違う場合は、試験本体を実行せず失敗します。

## ライセンス

issue と contribution は [GitHub repository](https://github.com/Naohiro2g/McRemote) で受け付けます。ライセンスは [LICENSE](LICENSE) を参照してください。本 project は [wensheng/JuicyRaspberryPie](https://github.com/wensheng/JuicyRaspberryPie) を起点にしています。

## 同梱ライブラリ

認証情報の保存には、Paperが提供する [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) を使います。McRemoteのJARにはSQLite JDBCやnative libraryを同梱しません。
