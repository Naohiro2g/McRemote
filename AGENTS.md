# AGENTS.md - McRemote

McRemote は、マイクラリモコンのサーバー側プラグインです。Paper サーバー上で動き、各クライアント
から受け取ったリモコン建築コマンドを Minecraft 世界へ反映します。

## McRemote SSOT

McRemote 固有の設計判断の正本は、GitHub 上の `Naohiro2g/mc-remote-knowledge` です。

McRemote 固有の architecture / protocol / release flow / deployment / contributor workflow /
learning design、または McRemote 固有の判断理由に依存する挙動を変更する前に、その SSOT
リポジトリの関連文書を読んでください。

`Naohiro2g/mc-remote-knowledge` にアクセスできない場合は、作業を止めてその旨を明示してください。
このリポジトリ単体、assistant memory、過去会話、ローカル推論から欠けた McRemote 文脈を補完してはいけません。
SSOT にアクセスできるまで、McRemote 固有文脈に依存する設計判断や実装を進めないでください。

このファイルは McRemote SSOT を複製しません。複製はドリフトを生みます。

## このリポ固有の指示

- 変更はユーザー依頼の範囲に限定する。
- protocol 契約は SSOT に従う。wire format に触れる場合は、編集前に `10-protocol` の SSOT を読む。
- 秘密値や環境固有の deploy 値を commit しない。認証情報はローカル限定 config に置く。
- build / test は既存 Gradle workflow を使う。
- 複数リポまたは複数スポークにまたがる判断が出たら、このリポを正本にせず、knowledge repo への候補行を出す。
