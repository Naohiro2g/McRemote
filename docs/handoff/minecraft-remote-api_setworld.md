# 引き継ぎ: minecraft-remote-api に setWorld / setBuildOrigin を追加

> ⚠️ **SUPERSEDED（歴史資料）— 2026-06-28 時点で現行 b1 wire 契約とは一致しない。**
> この文書は **protocol 20.0.0 / api 2000.0.0** 期に、行ベース `cmd(args)\n` プロトコル前提で
> 書かれた。以下は**すべて b1（protocol 21.0.0 / `2100.0.0b1`）で改訂済み**:
> - wire は **JSON-RPC 2.0**（旧 `cmd(args)\n` テキストは廃止）。
> - wire method は **`build.setWorld` / `build.setOrigin`** の dot 名前空間（API 名 `setWorld`/`setBuildOrigin` は別層で維持）。
> - **`setPlayer` は b1 でクリーン除去**（互換 alias なし）。本文の「移行期に温存」は無効。
> - エラーは **JSON-RPC error object**（`data.reason`/`data.ref`）。本文の `sys.exit`/テキスト ack は無効。
> - `postToChat` は現行 `Minecraft` クラスに無い（`chat.post`）。
> - hello は **object params + flat JSON-RPC result**（`protocol=21.0.0`・`y_sea`・`catalogHash:null` 等）。
>
> b1 の実際の着地状況は `NOTES_ja.md`（2026-06-28 エントリ）と `smoke.log`、正本は knowledge
> repo `10-protocol/wire-format-design_ja.md` を参照。本文は **setWorld/setBuildOrigin への分離設計の
> 歴史的経緯**としてのみ読むこと。現行 b1 handoff として信用しない。

---

対象リポ: `minecraft-remote-api`（Python クライアント）
作成元: McRemote（プラグイン）側の作業。protocol 20.0.0 / api 2000.0.0。
このファイル1枚で完結できるよう、背景・パッチ・テスト手順を入れてある。

---

## 背景（なぜ）
- プラグイン(McRemote)に **プレイヤー非依存の build-state コマンド**を追加した:
  `setWorld(dimension)` と `setBuildOrigin(x,y,z)`。identity（誰か）と build state（どこに建てるか）を分離する設計。
- すでに **Paper 1.21.11 実機で smoke test PASS 済み**（プレイヤー/認証なしで setWorld→setBuildOrigin→setBlock→getBlock が成立）。
- プロトコルは行ベース `cmd(args)\n`。ワイヤは `setWorld(overworld)` / `setBuildOrigin(200,0,200)`。
- **座標意味論は不変**: 絶対 y = origin_y + dy。**暗黙の Y オフセットは無い**（Y_SEA は座標式に足さない＝情報用定数のまま）。したがって既存の setBlock/getBlock は無改変。
- **後方互換**: プラグインは移行期 `setPlayer` を温存。Python も `setPlayer` を残し、メソッドを**追加**するだけ。

## このパッチでやること
1. `mc_remote/minecraft.py` の `Minecraft` クラスに `setWorld` / `setBuildOrigin` を追加（`setPlayer` と同じ ack 読み流儀）。
2. `examples/` と `tests/` の param に `DIMENSION = "overworld"` を追加し、`hello.py` / `axis_flat.py` の `setPlayer(...)` を `setWorld`+`setBuildOrigin` に置換。

---

## パッチ1: API メソッド追加（mc_remote/minecraft.py）

```diff
--- a/mc_remote/minecraft.py
+++ b/mc_remote/minecraft.py
@@ -313,6 +313,24 @@
             return result
         # return self.conn.sendReceive(b"setPlayer", *args)
 
+    def setWorld(self, dimension):
+        """Set the build world/dimension (overworld, nether, end, or an exact
+        world name). Build state is independent of setPlayer."""
+        result = self.conn.sendReceive(b"setWorld", dimension)
+        if "Error" in result:
+            sys.exit(result)
+        print(result)
+        return result
+
+    def setBuildOrigin(self, *args):
+        """Set the build origin (x, y, z). Default is (200, 0, 200).
+        Coordinates are absolute; no implicit Y offset is applied."""
+        result = self.conn.sendReceive(b"setBuildOrigin", intFloor(args))
+        if "Error" in result:
+            sys.exit(result)
+        print(result)
+        return result
+
     def close(self):
         """Close the connection to the Minecraft server"""
         self.conn.close()
```

## パッチ2: examples / param 差し替え（4ファイル）

```diff
--- a/examples/param_mc_remote.py
+++ b/examples/param_mc_remote.py
@@ -8,6 +8,7 @@
 
 PLAYER_NAME = "PLAYER_NAME"  # set your player name in Minecraft
 PLAYER_ORIGIN = Vec3(2000, 0, 2000)  # PO.x, PO.y, PO.z
+DIMENSION = "overworld"  # build dimension: overworld / nether / end
 print(f"param_mc_remote loaded for {PLAYER_NAME} at {PLAYER_ORIGIN.x}, {PLAYER_ORIGIN.y}, {PLAYER_ORIGIN.z}")
 
 # minecraft remote connection to the host at address:port
--- a/tests/param_mc_remote.py
+++ b/tests/param_mc_remote.py
@@ -7,6 +7,7 @@
 
 PLAYER_NAME = "PLAYER_NAME"  # set your player name in Minecraft
 PLAYER_ORIGIN = Vec3(2000, 0, 2000)  # PO.x, PO.y, PO.z
+DIMENSION = "overworld"  # build dimension: overworld / nether / end
 print(
     f"param_MCJE loaded for {PLAYER_NAME} at {PLAYER_ORIGIN.x}, {PLAYER_ORIGIN.y}, {PLAYER_ORIGIN.z}"
 )
--- a/examples/hello.py
+++ b/examples/hello.py
@@ -5,7 +5,8 @@
 
 # Connect to minecraft and open a session as player with origin location
 mc = Minecraft.create(address=param.ADRS_MCR, port=param.PORT_MCR)
-mc.setPlayer(param.PLAYER_NAME, PO.x, PO.y, PO.z)
+mc.setWorld(param.DIMENSION)
+mc.setBuildOrigin(PO.x, PO.y, PO.z)
 
 mc.postToChat("Hello, Minecraft Server!!")
 mc.setBlock(5, 68, 5, block.GOLD_BLOCK)
--- a/examples/axis_flat.py
+++ b/examples/axis_flat.py
@@ -67,7 +67,7 @@
     mc.postToChat(f"Construction fields at x={x}, z={z}")
     for _x in range(-400 + x, 400 + x, 200):
         for _z in range(-400 + z, 400 + z, 200):
-            mc.setPlayer(param.PLAYER_NAME, _x, 0, _z)
+            mc.setBuildOrigin(_x, 0, _z)
             reset_minecraft_world(mc)
             draw_XYZ_axis(mc, wait=0)
 
@@ -75,7 +75,8 @@
 if __name__ == "__main__":
     # Connect to minecraft and open a session as player with origin location
     mc = Minecraft.create(address=param.ADRS_MCR, port=param.PORT_MCR)
-    mc.setPlayer(param.PLAYER_NAME, po.x, po.y, po.z)
+    mc.setWorld(param.DIMENSION)
+    mc.setBuildOrigin(po.x, po.y, po.z)
 
     mc.postToChat("axis_flat.py")
 
```

## 適用手順
上記2つを `p1.patch` / `p2.patch` に保存し、リポ root で（**①→②の順**。②は①に依存）:

```sh
git apply --check p1.patch && git apply p1.patch
git apply --check p2.patch && git apply p2.patch
```

---

## テスト手順

### A. 静的チェック（サーバ不要）
```sh
python -m py_compile mc_remote/minecraft.py examples/hello.py examples/axis_flat.py \
    examples/param_mc_remote.py tests/param_mc_remote.py
python -c "from mc_remote.minecraft import Minecraft; \
print('setWorld', hasattr(Minecraft,'setWorld')); \
print('setBuildOrigin', hasattr(Minecraft,'setBuildOrigin'))"
# 期待: どちらも True
```

### B. 実機チェック（McRemote プラグイン稼働サーバが必要）
前提: McRemote(>=2000.0.0, setWorld/setBuildOrigin 入り)が **Paper 1.21.11** で稼働、port 25575。
```python
from mc_remote.minecraft import Minecraft
mc = Minecraft.create(address="localhost", port=25575)
print(mc.setWorld("overworld"))        # -> 'World set to "..." (origin: ...)'
print(mc.setBuildOrigin(200, 0, 0))    # -> 'Build origin set to: 200, 0, 0 in world "..."'
mc.setBlock(0, 0, 0, "DIAMOND_BLOCK")  # setBlock は無応答（成功時）
print(mc.getBlock(0, 0, 0))            # 期待: DIAMOND_BLOCK
mc.close()
```
または `examples/hello.py` / `axis_flat.py` を実行し、Minecraft クライアントで目視。

**重要（既知の落とし穴）**: 初回チャンクロード（特に版アップグレード直後）で main スレッドが数秒占有され、getBlock の応答が遅れる。**receive 系の timeout は 15–20s** を見込むこと（プラグイン側 smoke test で 5s timeout に当たった実績あり）。

### C. 期待結果
- setWorld → `World set to ...` / setBuildOrigin → `Build origin set to ...` の ack 1行。
- getBlock のラウンドトリップが設定した material と一致。

---

## 注意・メモ
- **日本語ワールド名バグ**: 旧 setPlayer は `Bukkit.getWorld("world")` 決め打ちで、オーバーワールド名が "world" 以外（日本語名など）だと null→建築不可だった。新 `setWorld("overworld")` は **Environment で名前非依存に解決**するので、ワールド名に関係なく通る。
- 新経路は**認証なし**（誰でも建築可）。認証必須化は R2。
- `setPlayer` はまだ消さない（移行期）。`PLAYER_NAME` は param に残置（未使用・将来撤去）。
- `Y_SEA=62` は param の情報用定数のまま（将来 hello でサーバ供給へ）。

## このパッチに含めない追従（別ステップ）
- バージョン整合（api 2000.0.0）＋ PyPI リリースをプラグイン push と足並み合わせ。
- `setWorld` の dimension 正準形を protocol スポーク(10-protocol)に ratify。
- Y_SEA 等ワールド定数の hello 返却（DECISIONS 2026-06-15-04 系）。
