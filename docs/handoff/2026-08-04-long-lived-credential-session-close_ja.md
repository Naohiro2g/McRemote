# long-lived credential session close（2026-08-04）

## 結論

McRemote の long-lived credential plugin slice は、既決 `2026-08-02-01` に対する実装、
deterministic test、実 server の live-human test、knowledge への正式 evidence 着地まで完了した。

ただし `2026-08-02-03` の public gate は閉じたままである。次に行うのは機能追加ではなく、
**clean commit から作った plugin artifact と Stack の profile / restore 機構を組み合わせた統合検証**である。
Python の既定 credential を `session` から `long_lived` へ変更してはならない。

long-lived credential は b3 release scope ではない。b3 の catalog / Scratch compatibility を
この作業で膨らませない（knowledge `10-protocol/versioning-design_ja.md` §10.11.2）。

## 固定された実装境界

- repository: `Naohiro2g/McRemote`
- branch: `feature/long-lived-credential`
- branch source HEAD at close: `ecf967f6163bc8182ff399f685a6c9dc3c6d204d`
- implementation: `7b1a98934d69451cd53559bc595464ab1e21d68e`
- restart harness: `8dc575ef7dc2bf4bb0c8e5611c085286e06c6c7a`
- multi-session revoke harness: `ce63dda8eabc948debe5359ed05dc0df4f8b4b61`
- storage / revoke-persistence harness: `ecf967f6163bc8182ff399f685a6c9dc3c6d204d`
- close 時に参照した knowledge `main`: `16e888376114b73609c75b02ca028fc414545a04`

実装済みの主な面は次のとおり。

- `mcrl_` long-lived credential の hash-only 永続化
- `CredentialStore` snapshot と create-only `RevocationAuthority` の分離
- authority durable commit を revoke の線形化点とする処理
- domain manifest、欠落・破損・domain mismatch の fail closed
- 明示 `/mcremote credential status|bootstrap|reset`
- `auth.listCredentials` / `auth.revoke` / `auth.logout`
- revoke 済み credential の全 session close と再接続拒否
- device、UTC時刻、`current`、上限16件、degraded snapshot reconcile
- `mcrp_` 非移行と `mcrl_` / `mcrs_` の token type 分離

設定 surface は `src/main/resources/config.yml` の次のキーである。

- `auth.credential_store_path`
- `auth.revocation_authority_path`
- `auth.max_long_lived_credentials_per_uuid`

保存 path は絶対 path を受け入れ、相対 path は `plugins/McRemote` 配下へ解決する。snapshot と
authority は独立 path とし、一方を他方の配下へ置かない。物理 volume と restore write set の保証は
deployment / Stack の責務であり、plugin 単独の保証ではない。

## 検証と正式 evidence

2026-08-04 のクローズ時に次を再実行した。

```text
./gradlew test --tests club.code2create.mcremote.CredentialServiceTest
BUILD SUCCESSFUL
tests=13, failures=0, errors=0, skipped=0
```

live-human evidence は knowledge へ正式着地済みである。

- record: `14-evidence/records/2026-08-02-long-lived-credential-lifecycle-live-human_ja.md`
- artifacts: `14-evidence/artifacts/2026-08-02-long-lived-credential-lifecycle-live-human/`
- knowledge landing commit: `16e888376114b73609c75b02ca028fc414545a04`
- subject JAR SHA-256: `1ea1baa3545988d083d185eded38d39754b8dc7fe7a910ac0f42726a8d952869`

正式 record が支えるのは、active/revoked 状態の通常再起動耐性、hash-only 保存、通常 log への
raw bearer 非包含、list metadata、logout/revoke、非 current 個別 revoke、同一 credential の
2 session close、再接続時 `token_revoked` である。

この JAR は digest で固定されているが、build 時に catalog hardening の未コミット Java 差分を
含んでいたため、単一 commit からの whole-JAR 再現性は主張しない。knowledge はこの claim boundary で
受理済みである。**gate を開く統合試験では clean commit から新しい artifact を作る。**

## 現在の worktree と保全事項

クローズ時点で次の5ファイルに未コミット差分がある。

- `scripts/smoke_test.py`
- `src/main/java/club/code2create/mcremote/BlockEditCommands.java`
- `src/main/java/club/code2create/mcremote/BlockQueryCommands.java`
- `src/main/java/club/code2create/mcremote/BlockRef.java`
- `src/main/java/club/code2create/mcremote/CatalogService.java`

内容は catalog/state error hardening（canonical state、`data.allowed`、`invalid_params` の整合）で、
credential実装の未完了差分ではない。**破棄しない・credential commitへ混ぜない。** clean artifact を
作る前に、catalog側の正しいbranchへ分離して独立に検証・commitする。

`handoff-materials/2026-08-02-long-lived-credential-live-human/` は gitignore 配下の旧搬送素材で、
正式正本ではない。knowledge着地済みなので、ローカル固有の再確認が不要になった時点で削除できる。

本クローズでは server process の稼働継続を引継ぎ前提にしない。次回の live test は対象 server identity、
installed JAR digest、online player を改めて確認してから開始する。

## 次回の開始手順

1. 本文書、knowledge `2026-08-02-01` / `2026-08-02-03`、platform-design §9、正式 evidence recordを読む。
2. Stack側の実装branch / commit / profile revisionと、同側のhandoffを確認する。McRemote側からStackの
   完了状態を推測しない。
3. catalogの未コミット差分をcredential branchから分離し、credential＋Stack統合対象をclean commitにする。
4. clean sourceからJARをbuildし、source commit、JAR SHA-256、installed digestを固定する。
5. Stack profileでsnapshotとauthorityの独立path/mount、backup archive非包含、doctorのpath/domain healthを確認する。
6. 下記restore matrixをliveで通し、sanitized transcriptを保存する。
7. knowledgeへ統合evidenceを搬送し、`2026-08-02-03` のgate開放可否を判断する。

## Stack統合 restore matrix

最低限、同一 player に credential A / B を用意して次を確認する。

1. A / B がともに有効な時点のrestore対象snapshotを作る。
2. Aだけをrevokeし、authority tombstoneのdurable commitを確認する。
3. Stackの正規手順でworld / rollback対象snapshotをrevoke前へ戻す。authorityはwrite setへ含めない。
4. serverを通常再起動し、doctorでpath、domain、authority continuityを確認する。
5. Aのfresh helloが`token_revoked`になる。
6. Bのfresh helloが成功する。
7. Aが`auth.listCredentials`、active limit、`current`へ復帰しない。
8. authority tombstoneとcredential domainがrestore前後で同一である。
9. backup archiveにauthorityが含まれず、restoreがauthorityへ書き込んでいないことを機械確認する。

併せて、authority欠落・domain mismatch・読取不能が空storeや無認証へfallbackせずfail closedになること、
およびdoctorがoperatorへ復旧可能な診断を出すことを確認する。host全損でauthorityを回収できない場合は
暗黙再生成せず、明示resetで新domainを作って全credential失効・再pairとする。

## 別スコープとして残すもの

- `2026-08-02-03` gateが開くまで、Python既定は`session`のまま。
- 完了条件11「PoPなしのbearer credentialであること」の利用者向け文書化は未完。
- `2026-08-02-08` の `mcrs_` session token再起動継続は今回のlong-lived sliceに含めない。
- `protocol-21.0.0-b4` の `player.getPose` / `player.setPose`（`461ac3e`）は別branch・別scope。
- b3 catalog release readinessを、このcredential evidenceから主張しない。

## クローズ判定

McRemote単体のlong-lived credential実装セッションはここでcloseできる。次の作業単位は
「Stack実装との統合・clean artifact・restore live evidence」であり、plugin機能実装の継続ではない。
