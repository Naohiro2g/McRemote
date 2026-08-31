# b7 live-auto runner（protocol 23.1.0）

これはMcRemote b7 candidateを実Paperへ配置した**後**に、gate coordinatorが指定した隔離環境で実行する
非production runnerとprobeです。このbranch自身はshared serverへの配置、起動、live実行を行いません。

## 固定するcandidate

- source: `9db95e8af0bcc9feaf66c1bbbffc05b9fb8304e0`
- product JAR: `mc-remote-1.21.11-2301.0.0b7.jar`
- product JAR bytes: `222313`
- product JAR SHA-256: `f47e08f1c6c2d0754b2d9f59a3e5a80fdf7307c5d011582c455fb10895f7b3ef`
- owner fixture SHA-256: `faad66c93d2c8ee8eb541f6b7297163cb681054b3de05ba3d130ac4288c1046a`

probeはproduct JARと別のGradle projectです。root `src/main`、product `plugin.yml`、handler registryへ
probe classやcommandを追加しません。

## probe build

Paper 1.21.11 API用:

```bash
./gradlew -p live/b7/probe clean jar
sha256sum live/b7/probe/build/libs/mc-remote-b7-live-probe-23.1.0-b7.jar
wc -c live/b7/probe/build/libs/mc-remote-b7-live-probe-23.1.0-b7.jar
```

Paper 26.2 API compile pulse:

```bash
./gradlew -p live/b7/probe clean compileJava \
  -PprobeJavaVersion=25 \
  -PpaperApiVersion=26.2.build.121-stable
```

確定したfilename、size、SHA-256は同directoryの`JAR_IDENTITY.txt`を参照してください。probeは
`plugins/McRemoteB7LiveProbe/control.properties`を各tickで読み、同directoryの
`observation.properties`へeventと後続tickのsanitized観測だけを書きます。credential、player UUID、接続先は
control／observation／plugin logへ書きません。

## 必要なenvironment inputs

- exact Paper version/build、Paper JAR SHA-256、Java vendor/version
- candidate product JARの上記identityとの一致
- test用logical deployment、physical host/runner、host-native/containerの別
- TCP host/port（CLI入力。sourceへ固定しない）
- token file、または`--interactive-pair`。tokenは標準出力へ出さない
- tokenに束縛されたonline playerがtest dimensionにちょうど1人で、direction確認中は静止していること
- `mcr.online`、`mcr.lightning`、test regionを含むbuild range
- defaultまたは明示したlightning rate/work policy（work request上限は256以上）
- loadedなtest dimensionと、dimension-change確認用のloadedな別dimension
- integer build origin、空の専用test region、別dimension側の安全なteleport座標
- server filesystem上のprobe data directoryとrollback manifest path
- 実行直前のdisposable world snapshot identity

## 実行例

tokenを引数へ直接書かず、ownerだけが読める一時fileへ置きます。runnerはside-effecting requestを自動retryしません。

```bash
python3 scripts/b7_live_auto.py \
  --host "$MCR_TEST_HOST" --port "$MCR_TEST_PORT" \
  --dimension minecraft:overworld \
  --alternate-dimension minecraft:the_nether \
  --origin 200 64 200 \
  --alternate-destination 0 80 0 \
  --probe-dir /exact/server/plugins/McRemoteB7LiveProbe \
  --state-file /private/test-state/b7-rollback.json \
  --token-file /private/runtime/mcremote.token
```

段階実行は`--phase setup`、`--phase run`、`--phase cleanup`です。`all`はその順に実行します。
各non-cancel/cancel lightning requestの間は20 server tickを越える待機を一度だけ行い、拒否時にも再送しません。

## setup、cleanup、rollback

1. coordinatorが指定したdisposable world snapshotを作成し、そのidentityを記録する。
2. `setup`が変更対象blockの`BlockValue`を`--state-file`へatomic保存する。
3. runnerは専用region内だけにentity、netherrack、lightning rod、copperを配置する。
4. `cleanup`はmanifestのblockを復元し、明示したregion box内のnon-player entityをprobeで削除する。
5. lightningの副作用が明示blockやregion外へ及ぶ可能性、chunk保存、後続tick収束をcleanupで完全に戻せるとは
   主張しない。正式rollbackはserver停止後に手順1のworld snapshotを復元し、candidate/probe JARと設定を
   pre-test identityへ戻して再起動する。
6. probeの`control.properties`、`observation.properties`とprivate token fileはevidenceのsanitization／移管後に削除する。

失敗時はまず`--phase cleanup`を一度実行し、続いてworld snapshot rollbackを行います。cleanup自体をretry可能な
product operationとはみなしません。

## PASSと観測

runnerがPASS条件にするもの:

- direction四methodの成功、DirectionValueの有限3要素・小数点以下最大6桁・norm tolerance
- set resultと直後getの一致、player/entity位置とdimensionの不変
- 外部remove／dimension移動後の初回reasonと、同handleの直後`entity_not_found`
- strike requestの`result:null`、event exact target、対象event 1件、cause `CUSTOM`
- non-cancelとcancelの独立runおよびfinal cancellation state
- later-tick snapshotの取得
- 既存`world.spawnParticle` wireでaccepted count `1`

damage、transformation、fire、rod、copperはbaseline／tick0／laterの値を`OBSERVED`として出力します。
発生しないこと自体をFAILにせず、個数、順序、確定tick、最終収束をassertしません。

## 対応case

| case | runner／probe対応 |
| --- | --- |
| `D-LIVE-01` | `player.getDirection`／`player.setDirection`／post-read |
| `D-LIVE-02` | `entity.getDirection`／`entity.setDirection`／post-read |
| `D-LIVE-03` | DirectionValueの有限3要素、小数点以下最大6桁、norm tolerance |
| `D-LIVE-04` | set前後のplayer/entity位置とdimension不変 |
| `H-LIVE-01` | probeによる外部remove後、`entity_unavailable`→即時`entity_not_found` |
| `H-LIVE-02` | probeによる別dimension移動後、`entity_dimension_changed`→即時`entity_not_found` |
| `L-LIVE-01` | non-cancel full strikeのexact target、対象event 1件、`CUSTOM`、`result:null` |
| `L-LIVE-02` | cancel runを別arm/run IDで実行しfinal cancelledを確認 |
| `L-LIVE-03` | damage／transformation／fireのbaseline、tick0、later観測。変化を観測した場合は旧handle失効も確認 |
| `L-LIVE-04` | lightning rod／copperのbaseline、tick0、later観測 |
| `L-LIVE-05` | configurable `later_ticks`後のsnapshot完了 |
| `P-LIVE-01` | 既存9-param `world.spawnParticle`、既定receiver経路、accepted count `1` |
| `R-LIVE-01` | side-effecting lightning callを各run exactly once送信しauto retryしない |
| `R-LIVE-02` | setup manifest、region cleanup、snapshot rollback停止線 |

## non-claim

- 個々のdamage、entity transformation、fire、rod、copper効果の発生保証、件数、順序、収束
- handler return／flushがlater tick、chunk保存、client描画のbarrierであること
- visual/audio、可視・可聴距離、client設定差
- particleのclient描画（live-human対象）
- event観測件数を、Paper内部実装全体の副作用exact countと同一視すること
- cleanupによるworld effectの完全rollback
- Paper 1.21.11での結果を26.2へ、または逆方向へ代用すること
