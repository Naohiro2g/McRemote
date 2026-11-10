# Protocol Inventory E1：McRemote

## 基準と読み方

- knowledge contract：`00-hub/polyglot-e1-instructions_ja.md`、実読commit
  `fc3eeffef28acfa54d1c112751fe95fd4e587fdb`。
- 基準：公開tag `v2321.1.0`、source `5b78ded96d1ad6d45160b67c5f8a13e14adb3fc8`、protocol `23.2.1`。
- 作業開始時のmainは`713fb7dff4d170ddc788f581fb9e6d8caf68cf0d`。基準との差分は
  `.github/workflows/ci.yml`と`release.yml`のrunnerラベルだけで、製品・test・fixtureは同じ。
- 「あり」はserverに処理の実装があること。「部分」は当該能力の一部だけ、「なし」は実装が無いこと。
  全reason・全分岐・実機での全経路の合格という意味ではない。この棚卸しでは実機試験を追加していない。
- 意味の正本はknowledgeのwire-format-design。以下は公開sourceを照合した実装の列であり、新しい契約を決めない。

根拠の製品classは[`src/main/java/club/code2create/mcremote/`](../src/main/java/club/code2create/mcremote/)、
test classは[`src/test/java/club/code2create/mcremote/`](../src/test/java/club/code2create/mcremote/)の同名`.java`。
method登録は`RemoteCommandRegistrar`と、そこから呼ぶ各classの`register`にある。
`hello`と`auth.*`は`RemoteSession`・`AuthCommands`で登録表より先に処理する。

## method（32）

reason欄は代表的な拒否を記す。パラメータの不正、権限、実行量などの共通条件は後述。
test名は根拠の範囲を示し、そのmethodの全wire対話を検証するという意味ではない。

| method | McRemote | 実装・根拠 / test | 拒否・境界 |
|---|---|---|---|
| `hello` | あり | `RemoteSession.handleHello/buildHelloResult`、`ProtocolInfo` / `ProtocolInfoTest`、`RemoteSessionPermissionsTest`、`DimensionKeyContractTest` | `protocol_required`、`protocol_mismatch`、`auth_required`、token系、`permission_denied`、`too_many_sessions`、`credential_store_unavailable`、`invalid_params`、`unknown_dimension`。拒否応答をflushして閉じる。 |
| `connection.flush` | あり | `ConnectionCommands.handleFlush`、`RemoteSession.tick`のFIFO処理 / `BlockEditFifoTest` | exact `[]`、不正は`invalid_params`。origin・建築権限・work不要。先行commandの終端までで、描画・永続保存は保証しない。 |
| `build.setDimension` | あり | `BuildStateCommands.handleSetDimension`、`DimensionResolver` / `DimensionKeyContractTest` | `invalid_params`、未ロード/未知dimensionは`unknown_dimension`。origin座標を維持する。 |
| `build.setOrigin` | あり | `BuildStateCommands.handleSetOrigin` / `DimensionKeyContractTest` | 整数3軸。`invalid_params`、`origin_not_set`。dimensionは維持する。 |
| `world.setBlock` | あり | `BlockEditCommands.handleSetBlock`、`BlockCodec` / `BlockEditCommandsTest`、`BlockCodecTest` | 建築共通拒否、BlockValueの拒否。成功`null`、notificationも受ける。 |
| `world.setBlocks` | あり | `BlockEditCommands.handleSetBlocks`、`BlockEditCursor/Volume` / `BlockEditCommandsTest`、`BlockEditFifoTest` | 範囲・量を検証してtick分割。後続FIFOは完了まで追い越さない。途中切断はrollbackしない。 |
| `world.getBlock` | あり | `BlockQueryCommands.handleGetBlock`、`BlockCodec.encode` / `BlockCodecTest`（値のencode） | `invalid_params`、`permission_denied`、`build_denied`。相対位置からBlockValueを返す。handlerの全経路のunitは主張しない。 |
| `world.getBlocks` | あり | `BlockQueryCommands.handleGetBlocks`、`BlockQueryRegion` / `BlockQueryRegionTest` | 各軸10 block以内、超過は`work_limit_exceeded`。`invalid_params`、`permission_denied`、`build_denied`。 |
| `world.getHeight` | あり | `WorldB5Commands.handleGetHeight` / `MiscCommandsHeightTest`、`WorldB5ChunkAdmissionTest` | `height_not_found`、`invalid_params`、建築/実行量の共通拒否。 |
| `world.getSign` | あり | `SignCommands.handleGetSign` / `SignCommandsTest`、`SignFixtureContractTest` | `not_a_sign`、`invalid_params`。両面・各4行の構造化値を返す。 |
| `world.setSign` | あり | `SignCommands.handleSetSign` / `SignCommandsTest`、`SignFixtureContractTest` | `not_a_sign`、`sign_waxed`、`sign_update_failed`、`invalid_property_value`、建築共通拒否。 |
| `world.updateSignLine` | あり | `SignCommands.handleUpdateSignLine` / `SignCommandsTest`、`SignFixtureContractTest` | 面・行を指定し更新。看板setterの拒否に加え入力検証。 |
| `player.getPos` | あり | `PlayerCommands.handleGetPosStructured` / `PlayerCommandsPoseTest` | player共通拒否、`invalid_params`。基準originに対する位置。 |
| `player.setPos` | あり | `PlayerCommands.handleSetPosStructured` / `PlayerCommandsPoseTest` | player共通拒否、`unknown_dimension`、`origin_not_set`、`teleport_failed`、`invalid_params`。 |
| `player.getPose` | あり | `PlayerCommands.handleGetPoseStructured` / `PlayerCommandsPoseTest` | player共通拒否、`invalid_params`。dimension・位置・yaw/pitchを返す。 |
| `player.setPose` | あり | `PlayerCommands.handleSetPoseStructured` / `PlayerCommandsPoseTest` | player共通拒否、`unknown_dimension`、`origin_not_set`、`teleport_failed`、`invalid_params`。 |
| `player.getDirection` | あり | `DirectionCommands` / `DirectionCommandsTest`、`DirectionLightningFixtureContractTest` | player共通拒否、`invalid_params`、内部失敗は`internal_error`。 |
| `player.setDirection` | あり | `DirectionCommands`、`DirectionValue` / `DirectionCommandsTest`、`DirectionValueTest`、`DirectionLightningFixtureContractTest` | `zero_direction`、`invalid_params`、player共通拒否。非ゼロ方向を正準化。 |
| `world.spawnEntity` | あり | `WorldB5Commands.handleSpawnEntity`、`EntityHandleRegistry` / `EntityParticleFixtureContractTest`、`WorldB5ChunkAdmissionTest` | `unknown_entity`、`entity_not_spawnable`、`entity_capacity_exhausted`、`entity_spawn_failed`、建築/実行量の拒否。 |
| `world.getNearbyEntities` | あり | `EntityLifecycleCommands.handleGetNearbyEntities` / `EntityLifecycleCommandsTest`、`EntityParticleFixtureContractTest` | playerを除きhandleを発行。`entity_capacity_exhausted`、`work_limit_exceeded`、建築共通拒否。 |
| `entity.getPose` | あり | `EntityLifecycleCommands`、`EntityHandleRegistry` / `EntityLifecycleCommandsTest`、`EntityParticleFixtureContractTest` | handle共通拒否、`permission_denied`、`invalid_params`。 |
| `entity.setPose` | あり | `EntityLifecycleCommands` / `EntityLifecycleCommandsTest`、`EntityParticleFixtureContractTest` | handle共通拒否、`unknown_dimension`、`origin_not_set`、`build_denied`、`teleport_failed`、`internal_error`。 |
| `entity.getDirection` | あり | `DirectionCommands`、`EntityHandleRegistry` / `DirectionCommandsTest`、`DirectionLightningFixtureContractTest` | handle共通拒否、`permission_denied`、`invalid_params`、`internal_error`。 |
| `entity.setDirection` | あり | `DirectionCommands` / `DirectionCommandsTest`、`DirectionLightningFixtureContractTest` | handle共通拒否、`zero_direction`、`invalid_params`、`permission_denied`。 |
| `entity.remove` | あり | `EntityLifecycleCommands` / `EntityLifecycleCommandsTest`、`EntityParticleFixtureContractTest` | handle共通拒否、`permission_denied`、`invalid_params`、`internal_error`。 |
| `world.spawnParticle` | あり | `WorldB5Commands.handleSpawnParticle` / `ParticleBuilderStage1Test`、`ParticleStage2Test`、`EntityParticleFixtureContractTest`、`DirectionLightningFixtureContractTest` | typed data、receiver、force。`unknown_particle`、`particle_data_required`、`particle_data_unsupported`、receiverの`auth_required/player_offline`、建築/実行量の拒否。 |
| `world.playSound` | あり | `SoundCommands` / `SoundCommandsTest`、`EntityParticleFixtureContractTest` | `unknown_sound`、`invalid_params`、`auth_required`、`player_offline`、`permission_denied`、`build_denied`、`work_limit_exceeded`。 |
| `world.playBlockSound` | あり | `SoundCommands` / `SoundCommandsTest`、`EntityParticleFixtureContractTest` | 指定blockの音。`no_block`とsound共通拒否。 |
| `world.strikeLightning` | あり | `LightningCommands` / `LightningCommandsTest`、`DirectionLightningFixtureContractTest` | `auth_required`、`permission_denied`、`build_denied`、`invalid_params`、実行量/頻度の拒否。 |
| `chat.post` | あり | `MiscCommands.handleChatPost` / `MiscCommandsChatPostTest`、`ChatEventCompatibilityFixtureContractTest` | request成功は`null`、notificationは応答なし。引数の不正は`invalid_params`。 |
| `events.poll` | あり | `EventCommands.handlePoll`、`EventRing` / `EventCommandsTest`、`EventRingTest`、`EventsFixtureContractTest`、`ChatEventCompatibilityFixtureContractTest` | cursorと任意`max_events`。`invalid_params`。filtered poll、clearは未登録。 |
| `catalog.get` | あり | `CatalogCommands.handleGet`、`CatalogService` / `ResourceIdsTest`（ID正準化） | `invalid_params`、`auth_required`。enforcement OFFのtoken無しhelloもcatalog取得権限にはしない。catalog全registryの実機網羅は未主張。 |

### 共通の拒否と観測の限界

- 登録済みhandlerがoriginを要するとき、無ければ`origin_not_set`。未知methodは`method_not_found`。
  `CommandDispatcher`の想定外例外はlogと切断で処理し、常にJSON-RPC errorを返すわけではない。
- 建築は`ConstructionPermissions`の接続時snapshotで判定し、`permission_denied`、範囲・block数等の
  `build_denied`、`WorkAdmission`等の`work_limit_exceeded` / `backpressure`がある。
  個々のhandlerで適用条件が違うので、全methodに同じguardがあるとはしない。
- player操作はUUID束縛・online・online権限が要り、`auth_required` / `player_offline` / `permission_denied`。
- handle操作は`entity_not_found` / `entity_unavailable` / `entity_dimension_changed`を区別する。
  接続を跨ぐhandleの持越しはしない。`EntityHandleRegistryTest`、各handler testが根拠。
- BlockValueは`BlockCodec.ValidationException`により`invalid_params`、`unknown_block`、`unknown_property`、
  `invalid_property_value`を返す。Paper registry、world、物理、描画をunitのproxyだけでは保証しない。

## auth（5）

| method | McRemote | 実装・根拠 / testの範囲 | 拒否・境界 |
|---|---|---|---|
| `auth.pairBegin` | あり | `AuthCommands.handlePreHello/handlePairBegin`、`PairingManager.begin` / `PairingManagerTest` | pre-hello。`token_type`はsession/long_lived、旧playerは`invalid_params`。deviceも検証。容量・頻度超過は応答せずtransport終了。`client`情報の保存/表示は実装していない。 |
| `auth.pairPoll` | あり | `AuthCommands.handlePairPoll`、`PairingManager.poll` / `PairingManagerTest` | pendingは成功result、承認後ok+token。`pair_expired`、`pair_not_found`、`credential_limit_reached`、`credential_store_unavailable`。 |
| `auth.listCredentials` | あり | `AuthCommands.handleListCredentials`、`CredentialService.list` / `CredentialServiceTest`（service側） | hello後、束縛UUID自身のlong-lived一覧だけ。raw token/hashを返さない。`invalid_params`、`credential_store_unavailable`。 |
| `auth.revoke` | あり | `AuthCommands.handleRevoke/revokeAndClose`、`CredentialService.revoke` / `CredentialServiceTest`、`SqliteCredentialLifecycleTest`（永続化） | 他人/不存在は同じ`credential_not_found`。`invalid_params`、`credential_store_unavailable`。失効後、そのcredentialの全sessionを応答flush後に閉じる。 |
| `auth.logout` | あり | `AuthCommands.handleLogout`、`McRemote.closeSessionsForCredential` / 上記service/lifecycle test | long-livedのみ。session tokenからは`invalid_params`。現在credentialのrevokeと全session終了。 |

auth handlerの全送受信経路を上記service単体testで確認したという主張はしない。
`CredentialDiagnosticsTest`、`SqliteCredentialBackendTest`も永続backendの根拠で、pairingの人間操作の代替ではない。

## methodでない能力（既存14行）

| 能力 | McRemote | 根拠と境界 |
|---|---|---|
| TCPのframing／JSON-RPC | あり | `ServerListenerThread`、`BoundedLineReader`、`CommandParser`、`RemoteSession` / `BoundedLineReaderTest`、`CommandParserTest`、`PreAuthTransportTest`。UTF-8行、整数id、JSON-RPC 2.0。batchは扱わない。 |
| WebSocket／browser | 部分 | native WS listenerは無し。wire §2のBridgeがWS messageをTCP行に変え、McRemoteはそのTCPを受ける。browserのrouting/OriginをMcRemoteで処理しない。今回Bridge経路の実機確認はしていない。 |
| protocol negotiation | あり | `ProtocolInfo.isCompatible` / `ProtocolInfoTest`。major一致、server.minor >= client.minor、patch不問。helloの拒否後は切断。 |
| authentication／pairing | あり | `AuthCommands`、`PairingManager`、`PairCommand`、`TokenStore` / `PairingManagerTest`、`RemoteSessionPermissionsTest`。pre-helloのpairとtoken付きhelloを分離。 |
| session credentialのUX | あり | server側のコード承認・期限付きsession token・long-livedの一覧/失効。`PairCommand`、`CredentialService`、`SqliteCredentialStore` / `CredentialServiceTest`、`SqliteCredentialLifecycleTest`。接続先別のclient側保存は担当外。 |
| request／notification／flushと順序 | あり | `RemoteSession.respondResult/respondError`、`ConnectionCommandQueue`、`BlockEditCommands`、`ConnectionCommands` / `BlockEditFifoTest`、`ConnectionCommandQueueTest`。notificationにはerrorも応答しない。DEBUG/TRACE delayはclientのpolicyでserver mode APIは無い。 |
| events cursor／loss | あり | `EventRing`、`EventCommands` / `EventRingTest`、`EventsFixtureContractTest`、`ChatEventCompatibilityFixtureContractTest`。有限ring、through/latest cursor、overflow/capacity loss。pushは無い。 |
| error reason | あり | `RemoteSession.respondError`がcode/message/data.reasonと追加dataを包む。`CommandDispatcher`、各handler / 各command test。全異常がerror応答になるとはしない（F1照合参照）。 |
| build context | あり | `BuildStateCommands`、`DimensionResolver` / `DimensionKeyContractTest`。hello/setterのdimension+origin、接続単位。旧`build.setWorld`は未登録。 |
| BlockValue／BlockSpec | あり | `BlockCodec`、`BlockEditCommands`、`BlockQueryCommands` / `BlockCodecTest`、`BlockEditCommandsTest`。wireのblock_id/stateを受け正準化。Client LibraryのBlockSpec classそのものは持たない。 |
| catalog／constants | あり | `CatalogService`、`CatalogCommands`、`ResourceIds` / `ResourceIdsTest`。registryからcatalog/hashを生成して返す。`.py/.pyi`等の生成はclient側。 |
| helloの`world_constants` | 部分 | `RemoteSession.buildHelloResult`は`y_sea`だけ（origin worldのseaLevel-1、取得不可ならnull）。ほかの世界定数は未実装で2400へ送られている。 |
| epoch／reconnect | あり | `RemoteSession`が接続ごとにepoch/handle/ringを生成、closeで破棄。`EntityHandleRegistryTest`、`EntityHandleFixtureContractTest`。新TCPは再hello。clientの自動再認証・再送はserverの機能ではない。 |
| WireScopeのobserver | なし | McRemoteはobserverの接続/ID/投影を管理しない。通常wireの応答・eventsを提供し、観察はclient/tooling側。HUDにもWireScope IDの取得経路は無い（platform §11）。 |

### 表に足す能力

| 能力 | McRemote | 根拠と境界 |
|---|---|---|
| 接続前の有限資源とadmission | あり | `PreAuthAdmission/Policy`、`BoundedLineReader`、`ConnectionFrameQueue` / `PreAuthAdmissionTest`、`PreAuthTransportTest`、`ConnectionFrameQueueTest`。接続数・期限・frame/queue量等。上限拒否はtransport終了を含む。 |
| tick実行量と公平性 | あり | `WorkAdmission`、`BlockEditCursor`、`McRemote`のtick処理 / `WorkAdmissionTest`、`BlockEditFifoTest`、`SessionTickRotationTest`。全methodの重さを一律には計上しない。 |
| durableなcredential失効 | あり | `CredentialService`、`SqliteCredentialStore/RevocationAuthority` / `SqliteCredentialBackendTest`、`SqliteCredentialLifecycleTest`。store障害時fail-close、session tokenも期限まで再起動を跨ぐ。 |
| resource IDの入力/出力正準化 | あり | `ResourceIds`、`BlockCodec`、`DimensionResolver` / `ResourceIdsTest`、`BlockCodecTest`、`DimensionKeyContractTest`。namespace省略入力と正準出力。 |
| ゲーム内の座標・時刻HUD | あり（protocol外） | `CoordinateHud`、`HudSampler`ほか / `CoordinateHudTest`、`HudSelectionTest`。protocolにHUD APIを追加しておらず、Client Libraryへの投影対象とは分ける。 |

## 共有fixtureの使い方

取得元は前後ともtooling `21a2a9a0aed8b4d9c715da46616aad86f1174b4c`の
`packages/protocol/test/fixtures/`。McRemoteでは先に固定し直してあり、今回変更しない。
公開tagもmainも同じ取得元で、[取得元・bytes/SHA-256の一覧](../src/test/resources/fixtures/README_ja.md)に記録済み。
copy 7本と同梱LICENSEのbyte列を指定commitと再照合して全件一致した。LICENSEは1,070 bytes、
SHA-256 `aa329a0bafc839b95124306141db13a381de45818bf629e983c44ea55ab5a7fb`。

toolingの10本を、保持していないものも含めて以下に分類する。

| fixture | copy | testへの接続・使い方 |
|---|---|---|
| `chat-event-compat-v23.2.1.json` | あり | `ChatEventCompatibilityFixtureContractTest`。chatPostedと未知eventの保持、cursor/loss等、serverに該当するcaseを使う。 |
| `direction-lightning-v23.1.json` | あり | `DirectionLightningFixtureContractTest`。方向、雷、粒子、handle/認可等のserverに該当するcase。 |
| `entity-particle-v23.2.1.json` | あり | `EntityParticleFixtureContractTest`。nearby、entity lifecycle、typed particle、sound等のserverに該当するcase。 |
| `events-v23.json` | あり | `EventsFixtureContractTest`（DTOとproducerの範囲）、`EntityHandleFixtureContractTest`（handle例）。13 case全部を対話として再生するものではない。 |
| `sign-v23.json` | あり | `SignFixtureContractTest`。看板のparse/encode、色・修飾、両面4行等。実際の表示は保証しない。 |
| `chat-event-compat-v23.2.json` | あり | 履歴として保持。testに接続していない。現行testは23.2.1版を読む。 |
| `entity-particle-v23.2.json` | あり | 履歴として保持。testに接続していない。現行testは23.2.1版を読む。 |
| `block-value-v22.json` | なし | 未接続。BlockCodecの独自unit testはあるが、この共有fileを読むこととは区別する。 |
| `dimensions-v22.json` | なし | 未接続。独自`dimension-key-v23.json`は別物。 |
| `spawn-v22.json` | なし | 未接続。旧handle形式の履歴で、現行handleを保証するfixtureにはしない。 |

共有fixtureに接続するのは上記5 file・6 test class。Gradleの通常`test`（JUnit Platform）に入っており、
CIの`build-only`の`./gradlew build`で実行する。credential matrixの限定testだけでfixtureのPASSとはしない。
既存testの観測範囲を引き継ぎ、toolingの全caseやScratch/client-only caseまでMcRemoteでPASSしたとはしない。
caseの追加、test runnerの共通化、未接続fixtureの接続は今回行わない。

独自fixtureの`b5-chunk-admission-v23.json`、`b5-plugin-runtime-policy-v23.json`、`dimension-key-v23.json`は
tooling copyではなく、それぞれ`WorldB5ChunkAdmissionTest`、`B5PluginRuntimePolicyFixtureTest`、
`DimensionKeyContractTest`で使う。MITの適用範囲は上記tooling copyで、独自fixtureのlicenseと混ぜない。

## F1の不足案とserverの照合

tooling `1286eb486bcaea93c4195c11e94304bdb51f53ba`の
[`packages/protocol/FIXTURE_INVENTORY_ja.md` §3](https://github.com/Naohiro2g/minecraft-remote-tooling/blob/1286eb486bcaea93c4195c11e94304bdb51f53ba/packages/protocol/FIXTURE_INVENTORY_ja.md#3-f1の不足と追加案)を読んだ。
不足する6領域の見立てはserverの実装と矛盾しない。ただし、新規caseで次を仮定しないよう返す。

| F1の領域 | serverの今の振る舞いと、追加案への注意 |
|---|---|
| framing | `BoundedLineReader`はLFだけでなくCRLF/CRも境界にし、分割/結合受信を読む。EOFに末尾byteがあればreader自体は末尾文字列を返すので、「LF無しの末尾は必ず捨てる」とは置けない。一方`InputThread`はEOFでtransportを閉じ、未処理queueを回収するため、末尾commandの実行・応答まで保証するとはしない。reader単体とsession終了のcaseを分ける。 |
| hello／negotiation | 正常/版不一致/認証拒否後closeの対話が要る。major一致・server minor充足・patch不問。`protocol_mismatch`はcode `-32600`、dataに`reason/server/client_requires`。`auth_required`後も同じTCPを使い続ける対話にはしない。BridgeのWS寿命は別。 |
| authentication／pairing | `pairBegin`→ゲーム内承認→`pairPoll(ok+token)`→hello。pendingはerrorではない。tokenの種別と期限、store障害、上限を区別する。明示的な「拒否するauth method/status」は無く、未承認ならpendingから期限切れになる。`client` metadataは現handlerが使わないので、保存や表示を期待値にしない。pairing_idはTCP接続ではなくmanagerに属し、再TCPでもpollできる。 |
| setBlock／getBlock | 座標はorigin相対、setterはBlockValue object、getterは正準BlockValue。認可・range・block数等を通る設定が要る。requestのset成功は`null`、notificationには応答しない。拒否を確認した後のworld値を比較し、単にgetterが通ることとsetterの副作用を区別する。 |
| errorの外枠 | `respondError`はcode/message/data.reasonを同じidに返す。ただし壊れたJSON/不正idはparse段階で捨て、hello前なら切断する。一般JSON-RPCのparse error応答を必ず期待するcaseにはしない。notificationはerror応答も無し。未知reasonを保持するclientのcaseは有用だが、McRemoteが任意の未知reasonを発生するとはしない。 |
| close | hello拒否やcredential失効は応答flush後close、I/O・資源制限は応答なしのcloseもある。切断前の建築が未実行か一部実行済みかを自動再送で埋め合わせない。再接続は新hello、origin・event ring・handle/epochを引き継がない。clientの保留request終了と自動再送方針はserverのfixtureで新規に決めない。 |

根拠：`BoundedLineReaderTest`、`CommandParserTest`、`PreAuthTransportTest`、`ProtocolInfoTest`、
`RemoteSessionPermissionsTest`、`PairingManagerTest`、`BlockEditCommandsTest`、`BlockEditFifoTest`と、各製品class。
F1の順序付き対話は現行共有fixtureには無い。今回は追加案への入力を返すだけで、新しいfixtureやwire契約を作っていない。

## 検証の範囲

今回の変更対象はこの文書だけ。fixtureの取得元固定とLICENSE同梱は既に済んでいるため差分なし。
CIの結果とPR/merge identityは返却票で提示する。通常buildのunit/deterministic testの成功は、
Paper両版でのlive-auto・人間の試験・Geyser/browser経路の追加確認を意味しない。
