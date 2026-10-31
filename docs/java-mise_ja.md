# サーバーのJavaをmiseで管理する

Paperを動かすJavaは、**Eclipse Temurinの最新パッチ**を推奨します。サーバーのフォルダーごとにmiseで系列を指定すると、Paperに合ったJavaを選べます。

| Paper | Javaの系列 | フォルダーで実行するコマンド |
| --- | --- | --- |
| 1.21.11 | Temurin Java 21 | `mise use java@21` |
| 26.3 | Temurin Java 25 | `mise use java@25` |

## miseを準備する

[公式のインストール手順](https://mise.jdx.dev/installing-mise.html)でmiseの最新版を導入し、[使うシェルに合った設定](https://mise.jdx.dev/shell-setup.html)を行ってください。既に導入済みの場合も、mise自体を最新にします。更新方法は、miseを入れた方法に合わせてください。

Bashを使う場合は、`~/.bashrc`に次を追加します。既に設定されている行は重複させません。

```bash
eval "$(mise activate bash)"
eval "$(mise completion bash)"
```

新しいターミナルを開くか、`source ~/.bashrc`で反映します。ほかのシェルの設定は公式のシェル設定を参照してください。

## サーバーのフォルダーでJavaを選ぶ

PaperのJARがあるフォルダーで、必要な系列を**一つ**指定します。以下はPaper 26.3の例です。Paper 1.21.11では`java@21`に変えてください。

```sh
mise use java@25
mise exec -- java -version
mise exec -- java -jar paper.jar nogui
```

最新版miseでは、配布元を省略した`java@21`／`java@25`の既定はTemurinです。条件に合う導入済みの版があればそれを使い、なければその時点の最新の正式パッチを導入します。以前のOpenJDKなどが導入済みの場合は、下の「既存環境を確認・整理する」で実体を確かめてください。

`mise.toml`には次のように系列が保存されます。通常の運用では、パッチまで固定する`--pin`は付けません。

```toml
[tools]
java = "25"
```

以後はそのフォルダーで起動します。`mise exec --`は、そのフォルダーの設定を使ってコマンドを実行します。これはローカルの設定で、`-g`を付けたときの全体設定とは異なります。

`mise use java@21 java@25`と一度に書くと、両方がそのフォルダーに登録され、通常の`java`には先頭の21が使われます。二つのPaperを使う場合は、サーバーのフォルダーを分け、それぞれに必要な系列を指定してください。

## パッチを更新する

稼働中の環境は月1回ほど新しい版を確認し、次の保守で明示的に更新して再起動します。重大なセキュリティ修正は、保守の予定より早い更新を検討してください。

サーバーを停止してから、そのフォルダーで実行します。

```sh
mise upgrade java
mise exec -- java -version
mise exec -- java -jar paper.jar nogui
```

系列指定のまま最新パッチへ更新します。起動のたびの自動更新は設定しません。`mise use java@25`を再実行するだけでは、導入済みのパッチが選ばれることがあり、最新版への更新にはなりません。

更新後は、起動、クライアントからの接続、ブロックの設置・読み戻しを確認し、Javaの実際の版を記録してください。リリースの検証などで具体的な版を定めた期間は、その版を維持します。

## 既存環境を確認・整理する

導入済みのJavaと、その版を参照する設定を確認します。補完候補には未導入の版も含まれるため、導入済みの一覧にはこのコマンドを使います。

```sh
mise ls java
mise exec -- java -version
```

Temurinを使っていても、一覧の管理名は`21.0.13+9.0.LTS`など、配布元のない数字だけの表記になることがあります。`temurin-`の有無だけでは実体の配布元を判断できません。`java -version`の出力でTemurinであることを確かめてください。

特定の導入済み版を確認したい場合は、一覧の管理名をそのまま指定できます。例えば次のように実行します（版は自分の一覧にあるものへ置き換えます）。

```sh
mise exec java@21.0.13+9.0.LTS -- java -version
```

古いOpenJDKが引き続き選ばれる場合は、サーバーを停止した保守時に`mise install java@21`（25なら`java@25`）で最新パッチを導入し、`mise use java@21`で選び直して、配布元と版を確認します。Javaの配布元を独自に設定している場合は、[`java.shorthand_vendor`](https://mise.jdx.dev/lang/java.html#java-shorthand-vendor)がTemurinを指すことも確認してください。

以前に配布元を明示して導入した`temurin-21…`などは、数字だけの管理名と別に並ぶことがあります。他のサーバーの`mise.toml`やIDEの参照も移し終えてから、使わなくなった版だけを`mise uninstall java@<一覧の管理名>`で削除します。`mise uninstall`は設定ファイルを書き換えません。

## 公式リファレンス

- [Javaの配布元と系列指定](https://mise.jdx.dev/lang/java.html)
- [mise use](https://mise.jdx.dev/cli/use.html)、[mise install](https://mise.jdx.dev/cli/install.html)、[mise upgrade](https://mise.jdx.dev/cli/upgrade.html)
- [シェルの設定と補完](https://mise.jdx.dev/shell-setup.html#completions)
