# おすすめプラグイン

[READMEに戻る](../README.md)

マイクラリモコンと一緒に使うと便利な、Paperサーバー向けのプラグインを紹介します。自分のサーバーでやりたいことに合わせて選んでください。ダウンロードするときは、使っているPaperのMCバージョンに対応するプラグイン版を選びます。

| プラグイン | 何に使うか |
| --- | --- |
| [LuckPerms](#luckperms) | プレイヤーやグループごとに、使える機能や建築範囲を決める |
| [ViaVersion / ViaBackwards](#viaversion--viabackwards) | Java版のマイクラアプリとサーバーのバージョンの違いを吸収する |
| [Geyser / Floodgate](#geyser--floodgate) | 統合版のマイクラアプリからJava版のサーバーへ参加できるようにする |
| [DirectionHUD](#directionhud) | 保存した目的地への案内やプレイヤー追跡などを使う |
| [WorldEdit](#worldedit) | 整地や建築物のコピーなど、世界をまとめて編集する |
| [DiscordSRV](#discordsrv) | ゲーム内のチャットとDiscordをつなぐ |
| [BlueMap](#bluemap) | ブラウザで世界を3D地図として見られるようにする |

## LuckPerms

LuckPermsは、プレイヤーごとに「何ができるか」を決める権限管理プラグインです。「ビジター」「メンバー」「先生」などのグループに権限をまとめておけるので、クラスや大人数で使う場合に便利です。[Modrinth](https://modrinth.com/plugin/luckperms)・[公式ホームページ](https://luckperms.net/)

McRemoteでは、接続して操作する権限に加えて、建築できる範囲と1操作の対象ブロック数を設定できます。範囲やブロック数などの値を持たせる設定を、LuckPermsでは「meta」と呼びます。

**LuckPermsを入れたら、McRemote用の権限とmetaも設定してください。** LuckPermsがあると、configの`default_build_range`と`default_build_blocks`から、プレイヤーの実効metaへ切り替わります。metaが無いと範囲とブロック数が0になり、建築できなくなります。

管理者として、例えばゲーム内のチャットで次を実行します。

```text
/lp group default permission set mcr.online true
/lp group default meta set mcr.build.range 100
/lp group default meta set mcr.build.blocks 32768
```

| 設定 | 意味 |
| --- | --- |
| `mcr.online` | プレイヤーがゲームに入っている間、McRemoteクライアントから操作できる |
| `mcr.build.range` | 建築原点からX・Zの各方向へ建築できる距離 |
| `mcr.build.blocks` | `setBlock` / `setBlocks`の1操作で指定できる対象ブロック数 |

上の値は設定例です。グループ名と値は用途に合わせて変え、プレイヤーがそのグループを継承していることを確認してください。特定のプレイヤーだけを設定する場合は、`group default`を`user <プレイヤー名>`へ置き換えます。

ゲームから退出した後にも操作させる用途では、必要なグループに次も設定します。

```text
/lp group default permission set mcr.offline true
```

**設定後はMcRemoteクライアントを切断・再接続してください。** 権限とmetaの変更は、新しい接続に反映されます。`mcr.build.blocks`を大きくしても、サーバー側の1要求の作業量上限は増えません。サーバー側の値は[READMEの設定項目](../README.md#設定項目)を参照してください。

コマンドの詳細は、LuckPerms公式の[meta設定](https://luckperms.net/wiki/Meta-Commands)と[permission設定](https://luckperms.net/wiki/Permission-Commands)にあります。

## ViaVersion / ViaBackwards

ViaVersion、ViaBackwardsは、サーバー利用者がマイクラアプリのバージョンについて気にせずに済むようにするために使います。「ほぼ、どんなバージョンのマイクラアプリ（Java版）でもオッケー」を目指す組み合わせです。[公式ホームページ](https://viaversion.com/)

例えば、サーバーのMCバージョンが以前より上がって26.3になっていても、利用者は以前と同じ1.21.11の構成でつながる。あるいは、公式ランチャーで「最新のリリース」を使って起動した場合も、大体つながる。そういう使い方を目指します。

- **ViaVersion**は、サーバーより新しいバージョンのアプリから接続できるようにします。
- **ViaBackwards**は、サーバーより古いバージョンのアプリから接続できるようにします。ViaVersionと一緒に入れます。

Paper向けの配布ページは、[ViaVersion](https://hangar.papermc.io/ViaVersion/ViaVersion)と[ViaBackwards](https://hangar.papermc.io/ViaVersion/ViaBackwards)です。対応範囲は導入する版によります。MCの更新直後などは対応が追いついていないこともあるので、[公式の導入案内](https://github.com/ViaVersion/ViaVersion/wiki/Installation)で確認してください。

さらに[Geyser / Floodgate](#geyser--floodgate)を組み合わせると統合版もつながるようになり、「マイクラアプリなら、大体つながる」に近づきます。

## Geyser / Floodgate

Geyser、Floodgateは、統合版のマイクラアプリからJava版のサーバーへ参加できるようにするために使います。スマホやタブレットの統合版からも、Java版で遊んでいる人と同じ世界へ入れます。[公式ホームページ](https://geysermc.org/)

- **Geyser**は、統合版のアプリとJava版のサーバーの間で通信を変換します。
- **Floodgate**は、統合版のアカウントで参加できるようにします。Geyserと一緒に使うと、Java版の購入アカウントを別途用意せずに参加できます。[Floodgateの公式案内](https://geysermc.org/wiki/floodgate/)

ViaVersion / ViaBackwardsと組み合わせると、Java版のバージョンの違いも、Java版と統合版の違いも吸収でき、「マイクラアプリなら、大体つながる」を目指せます。

配布ページは、[Geyser](https://geysermc.org/download/?project=geyser)と[Floodgate](https://geysermc.org/download/?project=floodgate)です。Paperサーバーへ入れる場合は、Spigot / Paper向けのものを選びます。統合版の接続にはUDPのポートを使うので、接続先やポートの設定も含めて[公式の導入案内](https://geysermc.org/wiki/geyser/setup/)を参照してください。

## DirectionHUD

McRemoteには[座標・時刻HUD](../README.md#座標時刻hud)があり、絶対／相対座標、world内の時刻、照準先ブロックをゲーム画面で確認できます。

DirectionHUDは、座標や向きの表示に加え、保存した目的地への案内やプレイヤー追跡などを使うプラグインです。[Modrinth](https://modrinth.com/plugin/directionhud)・[公式ガイド](https://directionhud.docs.oth3r.one/)

座標を保存して目的地にしたり、ほかのプレイヤーの位置を追ったりする機能もあります。みんなで同じ場所へ集まるときや、建築場所へ戻るときにも便利です。表示項目は`/hud`、目的地は`/destination`から設定できます。

## WorldEdit

WorldEditは、範囲を選んでブロックをまとめて置き換えたり、建築物をコピー・貼り付けしたりする編集プラグインです。授業前に建築用の平面を用意する、見本の建築を複製する、といった準備に便利です。[Modrinth](https://modrinth.com/plugin/worldedit)・[公式ホームページ](https://enginehub.org/worldedit)

ゲーム内のコマンドで手早く世界を整えられます。使い方は[公式のクイックスタート](https://worldedit.enginehub.org/en/latest/quickstart/)を参照してください。

## DiscordSRV

DiscordSRVは、ゲーム内のチャットとDiscordのテキストチャンネルをつなぐプラグインです。ゲームに入っている人と、Discordにいる人が会話できるようになります。ゲームへ入れないときにも、仲間の様子を聞いたり連絡したりできます。[Modrinth](https://modrinth.com/plugin/discordsrv)・[公式ホームページ](https://www.discordsrv.com/)

使うにはDiscordのbotを用意し、連携するチャンネルを設定します。手順は[公式の導入案内](https://docs.discordsrv.com/installation/)を参照してください。

## BlueMap

BlueMapは、マイクラの世界をブラウザで見られる3D地図にするプラグインです。上から全体を眺めたり、建築物に近づいて見たりできるので、みんなの作品や建築場所を共有するのに便利です。[Modrinth](https://modrinth.com/plugin/bluemap)・[公式ホームページ](https://bluemap.bluecolored.de/)

マイクラアプリを起動せずにブラウザから見られるため、作品の紹介にも使えます。[デモ地図](https://bluecolored.de/bluemap/)で見え方を試し、[公式の導入案内](https://bluemap.bluecolored.de/wiki/getting-started/Installation.html)を参考に設定してください。
