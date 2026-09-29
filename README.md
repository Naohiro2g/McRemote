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

- `plugins/McRemote/config.yml` で、TCP port（`api_port`）、認証、build range を確認します。
- port `25575` は、信頼できる client からだけ到達できるよう制限します。
- LuckPerms を使う場合は、接続する player に `mcr.online` または `mcr.offline` と
  `mcr.build.range` meta を付与します。
- `auth.enforcement` の既定は `true` です。`false` は認証障害の診断や移行試験のために、運用者が明示する
  一時的な bypass です。token なしで接続できてしまうので、loopback または隔離した検証環境だけで使用します。
- 認証情報の保存先が起動時に欠けている場合、プラグインは新しい保存領域を自動で作り、サーバーログに通知します。
  以前の token は使えなくなるため、client で再ペアリングしてください。保存済みデータの破損や ID 不一致では
  認証を停止し、原因をログに出します。

## 主な capability

- stream-local な dimension／origin と、構造化された block set/get
- paired player の position／pose
- bounded event poll と opaque entity handle
- particle／entity spawn、height query
- sign の get／replace／1行 update
- catalog、pairing、session／long-lived credential
- notification と `connection.flush` barrier

method の exact params、result、error、成熟状態は README ではなく
[protocol SSOT](https://github.com/Naohiro2g/mc-remote-knowledge/tree/main/10-protocol) を参照してください。

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

version と toolchain は `gradle.properties` が所有します。設計と wire contract の正本は
[mc-remote-knowledge](https://github.com/Naohiro2g/mc-remote-knowledge) にあります。

```sh
./gradlew build
```

Paper 26.2 API に対する一時的な compatibility compile は Java 25 で実行できます。

```sh
./gradlew \
  -PmcJavaVersion=25 \
  -PpaperApiVersion=26.2.build.121-stable \
  -PpluginApiVersion=26.2 \
  compileJava
```

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
  --protocol 23.1.0 \
  --dimension overworld --ox 200 --oy 0 --oz 200
```

成功時は最後に `PASS` が表示されます。この smoke は実 world を変更し、既定では
`minecraft:overworld` の絶対座標 `(200,0,200)` から上へ4ブロックを置きます。使い捨ての
開発 world で実行するか、確認後にその4ブロックを削除してください。

pairing と player position／pose の代表往復は、サーバー内で pair code を承認できる状態で
次を使えます。

```sh
python3 scripts/player_test.py --host 127.0.0.1 --port 25575 --protocol 23.1.0
```

## ライセンス

issue と contribution は [GitHub repository](https://github.com/Naohiro2g/McRemote) で受け付けます。
ライセンスは [LICENSE](LICENSE) を参照してください。本 project は
[wensheng/JuicyRaspberryPie](https://github.com/wensheng/JuicyRaspberryPie) を起点にしています。
