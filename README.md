# McRemote

McRemote は、Minecraft の外部クライアントから受け取ったコマンドを Paper サーバー上の
world へ反映するサーバー側プラグインです。Python、Scratch などから、ブロック建築、
player／entity 操作、event 観察を行うための共通 protocol endpoint を提供します。

## 現在の提供状態

現在の公開版は beta です。

| 項目 | 公開済みの値 |
| --- | --- |
| Minecraft / Paper | `1.21.11` |
| McRemote artifact | `2300.0.0b6` |
| wire protocol | `23.0.0` |
| Java | `21` |

[b6 prerelease と JAR を取得する](https://github.com/Naohiro2g/McRemote/releases/tag/v1.21.11-2300.0.0b6)

公開 JAR の SHA-256 は
`0ec8d4c0b105f3034361b260fc39fcb78013e932e684d34d5ca95c9a6c6a87a6` です。
設計と wire contract の正本は
[mc-remote-knowledge](https://github.com/Naohiro2g/mc-remote-knowledge) にあります。

### b7 candidate inventory（未公開）

現在の開発branchは artifact `2301.0.0b7`／protocol `23.1.0` candidateです。公開版の表と
download linkはb7 artifactがreleaseされるまでb6を指し続けます。

- `player.getDirection`／`player.setDirection`
- `entity.getDirection`／`entity.setDirection`
- damage-capableな`world.strikeLightning`（専用`mcr.lightning` permission、plugin default `op`）
- wireを変えない`world.spawnParticle`のPaper `ParticleBuilder` Stage 1移行

旧候補`world.strikeLightningEffect`は公開せず、full lightningはdamage、fire、entity変化などの
world副作用を起こし得ます。exact contractはknowledge commit
`f132a8e6c9f27e62c2762b6d07d2023988c55c97`の`10-protocol/wire-format-design_ja.md` §5.8.2です。
shared fixture、Paper live確認、公開artifact、releaseはcandidate sourceとは別gateであり、未完了です。

## 導入

1. Paper 1.21.11 サーバーを用意します。
2. 公開 JAR をサーバーの `plugins/` へ置き、サーバーを起動します。
3. 生成された `plugins/McRemote/config.yml` で TCP port、認証、build range を確認します。
4. LuckPerms を使う場合は、接続する player に `mcr.online` または `mcr.offline` と
   `mcr.build.range` meta を付与します。
5. TCP port の既定値 `25575` は信頼できる client からだけ到達できるよう制限します。

公開ネットワークで使う場合は `auth.enforcement: true` を設定してください。token なしの
開発用接続は、loopback または隔離した検証環境だけで使用します。認証時は client が表示する
pair code を Minecraft 内の `/mcremote pair NNN-NNN` で承認します。

## 最初の成功

この repository の `scripts/smoke_test.py` は Python 標準ライブラリだけで接続し、hello、
build context、block set/get、catalog error を一往復確認します。token なし hello を許す
隔離した開発設定で、サーバー起動後に実行します。

```sh
python3 scripts/smoke_test.py \
  --host 127.0.0.1 --port 25575 \
  --protocol 23.0.0 \
  --dimension overworld --ox 200 --oy 0 --oz 200
```

成功時は最後に `PASS` が表示されます。この smoke は実 world を変更し、既定では
`minecraft:overworld` の絶対座標 `(200,0,200)` から上へ4ブロックを置きます。使い捨ての
開発 world で実行するか、確認後にその4ブロックを削除してください。

pairing と player position／pose の代表往復は、サーバー内で pair code を承認できる状態で
次を使えます。

```sh
python3 scripts/player_test.py --host 127.0.0.1 --port 25575 --protocol 23.0.0
```

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

version と toolchain は `gradle.properties` が所有します。

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

issue と contribution は [GitHub repository](https://github.com/Naohiro2g/McRemote) で受け付けます。
ライセンスは [LICENSE](LICENSE) を参照してください。本 project は
[wensheng/JuicyRaspberryPie](https://github.com/wensheng/JuicyRaspberryPie) を起点にしています。
