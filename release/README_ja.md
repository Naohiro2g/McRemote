# McRemoteのrelease manifest

b10以降のMC版を含まないtagでは、公開manifestをv2で生成します。既存のMC版を含むtagはv1の生成を維持します。
正式な契約はknowledgeの[release gate notesのb10](https://github.com/Naohiro2g/mc-remote-knowledge/blob/6dbb9f1ee192c6c46d8dd58fdd91f8e6c3f46de5/00-hub/release-gate-notes_ja.md)（DECISIONS `2026-10-07-09`）です。

## 公開前にそろえるもの

- tagのCIが作ったcandidate JAR、`source-commit.txt`、`minecraft-targets.json`。公開workflowは再buildしません。
- 同じcandidateを宣言した全MC版で検証した、版ごとのrecord。Paper build、server JARのSHA-256、Java runtimeの実測した版の文字列を含めます。
- tooling担当から返された、固定commitのJSON Schemaと共有fixture。Schemaの正本は`minecraft-remote-tooling`です。

tag、Release、shared環境へのdeploy、人間参加試験は、coordinatorが許可した操作に従います。

## 検証recordをまとめる

`scripts/prepare_minecraft_verification.py`は、sanitizedの通常pulse、通常再起動後のpulse、enable／disableの観測を1件にまとめます。入力のsource commit、JAR、宣言、PaperとJavaのidentityが一致し、両pulseがPASSであることを確かめます。異なるcandidateへのPASSの付け替えは行いません。

```sh
python3 scripts/prepare_minecraft_verification.py \
  --pulse observations/pulse-1.21.11.json \
  --restart-pulse observations/restart-pulse-1.21.11.json \
  --lifecycle observations/lifecycle-1.21.11.json \
  --output-dir candidate
```

宣言した各版について実行します。出力名は`mc-remote-verification-<MC版>.json`です。元のpulseには、`source_commit`、`paper_build`、`paper_server_sha256`、`java_version`も、観測した値で記録してください。

recordはMcRemote側の素材形式`mc-remote.minecraft-verification` v1です。公開manifestのverificationと同じ6項目（MC版、Paper build、server SHA、Java版、JAR SHA、PASS）に、source commitと宣言のdigest、元の観測を持ちます。wire APIやtoolingのmanifest Schemaは変更しません。

coordinatorの許可した公開準備で、このrecordを対象Releaseのassetとして先に添付します。公開workflowは同じReleaseから版ごとのrecordを読み、candidateと照合します。recordはmanifestの`artifacts`に載せず、`verifications[].record`からbasenameとSHA-256で参照します。

## 固定したSchemaと共有fixture

`release-manifest-lock.json`はtoolingの発行commit
[`fb6880b192a0063241f95c44a5fa6b836f5e7394`](https://github.com/Naohiro2g/minecraft-remote-tooling/commit/fb6880b192a0063241f95c44a5fa6b836f5e7394)
を固定しています。Bridge／WireScopeのlockとは別です。

| lockの項目 | 同梱file | 用途 |
| --- | --- | --- |
| `schema` | `contracts/release-manifest-v2.schema.json` | Draft 2020-12のv2 Schema |
| `fixtures` | `contracts/fixtures/release-manifest-v2.json` | 共有fixture 66件（受入8件、拒否58件） |
| `legacy_v1_schema` | `contracts/fixtures/release-manifest-v1.schema.json` | v1回帰試験 |
| `legacy_v1_license` | `contracts/fixtures/STACK-LICENSE` | v1 Schemaの元のMIT License |

各項目はupstreamの`path`、lockからの相対`local_path`、`bytes`、`sha256`を持ちます。validatorは全項目のbytesとdigestを確認してからSchemaを使います。実行時にremoteのmainやSchemaの外部参照を取得しません。更新時はownerが発行した同じcommitの素材をbytesのまま取り込み、共有fixtureの受入・拒否、段階、理由まで照合します。

```sh
python3 -m unittest discover -s scripts -p 'test_*release*.py'
```

この固定commitの共有fixtureは66件とも一致しています。ただしtoolingの参照検査とfixtureの宣言fileは配列形式を想定し、実際のMcRemoteの`mc-remote.minecraft-targets` v1オブジェクトを拒否します。McRemoteは既存のオブジェクト形式とJARに同梱したbytesを維持しています。tooling／Stack側との宣言形式の照合が済むまで、このfixture一致だけでb10の公開可とは判断しません。

## manifestを生成する

以下はb10の例です。tag、JAR名、対象版は、そのcandidateに合わせます。

```sh
python3 -m pip install -r scripts/requirements-release.txt
python3 scripts/create_release_manifest.py \
  --schema-version 2 \
  --release-tag v2320.0.0b10 \
  --source-commit "$(cat candidate/source-commit.txt)" \
  --jar candidate/mc-remote-2320.0.0b10.jar \
  --declaration candidate/minecraft-targets.json \
  --verification-record candidate/mc-remote-verification-1.21.11.json \
  --verification-record candidate/mc-remote-verification-26.2.json \
  --output candidate/manifest.json
```

宣言とPASSの集合、版ごとに1件であること、全verificationのJAR SHA、recordのdigestと実測identity、宣言fileとsource commitの内容、JAR同梱宣言の生のbytesを照合します。公開するJARの`bytes`も載せます。対応版の順序は宣言にそろえます。

Schema搬送前のlocal確認には`--preflight`を追加できます。この場合もcandidateとの照合は行いますが、固定した共有Schemaへの適合は主張できません。公開workflowはこのflagを使わず、lockが無ければ停止します。

`.github/workflows/release.yml`は、すべての照合を終えてから、同じ宣言から作ったtitleと本文の対応版欄を反映し、JAR・宣言・manifestを添付します。本文の対応版欄以外は保持します。`dry_run=true`でも同じ検証を行い、Releaseの変更とassetの添付を省きます。
