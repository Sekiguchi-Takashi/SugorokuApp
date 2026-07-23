# SugorokuApp（どうぶつすごろく）HANDOFF

## 概要
どうぶつの森風の世界観のすごろくアプリ。プレイヤーはキャラ（しばいぬ・うさぎ・いのしし・トラ）を選び、ルーレット(1〜6)で30マスの盤面を進んでゴールを目指す。

- 現在バージョン: **v1.1（フェーズ1完了＋マルチキャラCPU対戦）**
- パッケージ: `com.appathy.sugoroku` / minSdk 26 / targetSdk 34

## ロードマップ
| フェーズ | 内容 | 状態 |
|---|---|---|
| 1 | ルーレット＋すごろく移動＋ゴール判定 | ✅ v1.0 |
| 2 | 停止マスでイベント発生（どうぶつの森風：どんぐり集め等） | 未着手 |
| 3 | マルチプレイ | 一部完了（v1.1: 最大4キャラ・人間1＋CPU。複数人間のパス&プレイは未着手） |

## ビルド規約（変更禁止）
- AGP **8.5.2** / Kotlin **1.9.24** / Gradle **8.9**（CIの setup-gradle で固定、wrapperなし）
- 外部依存ゼロ・XMLレイアウト不使用（プログラマティックUIのみ。AndroidManifest.xmlは除く）
- `debug.keystore` はリポジトリにコミット済み（storepass/keypass: `android`、alias: `androiddebugkey`）
- 納品は差分ZIP、ファイル名はバージョン番号付きで毎回変える

## ⚠️ 重要な落とし穴
- **`git init` は必ず `~/SugorokuApp` 内で実行すること。** ホームディレクトリで実行するとトークン等がステージされ Push Protection (GH013) で弾かれる事故が過去に発生済み。

## 構成
```
SugorokuApp/
├── .github/workflows/build.yml   # gradle assembleDebug → APK artifact
├── build.gradle.kts / settings.gradle.kts
├── debug.keystore
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/appathy/sugoroku/MainActivity.kt  # 全ロジック1ファイル
        └── res/drawable/
            ├── chara_shiba.png / chara_usagi.png / chara_inoshishi.png / chara_tora.png (512px)
            └── ic_launcher.png (192px, しばいぬ流用)
```

## コード設計（MainActivity.kt）
- 画面遷移は `setContentView` 切替: `showTitle()` → `showCharaSelect()` → `showCountSelect()`（人数1〜4） → `showGame()`
- `Player(chara, isHuman, position)` のリストで管理。`turn` で手番を回す。人間は先頭固定、CPUは残りキャラから順に割当
- CPU手番: `startTurn()` が1秒後に `rouletteView.autoSpin()` を呼ぶ（タップロック中でも回る）
- ルーレット結果: 停止後 `showResult()` が中央に正立・特大の数字をポップ表示（0.35秒拡大アニメ→0.7秒表示→移動開始）。盤の回転と独立して描画するため常に読みやすい向き
- 複数駒が同一マスのときは横にオフセット描画。手番の駒は大きく最前面
- `Board` object: `COLS=5, ROWS=6, CELL_COUNT=30, GOAL_INDEX=29`。盤面は下段左スタートの蛇行（サーペンタイン）配置
- `BoardView`: Canvas描画（マス円・経路線・駒ビットマップ）。`position` 更新→`invalidate()`
- `RouletteView`: 6色セグメント。結果を先に抽選し、その角度に減速停止（DecelerateInterpolator 2.6秒）。`locked` はタップのみ禁止（`autoSpin()` は通る）
- 移動: `movePiece()` が Handler 300ms間隔で1マスずつ進める。ゴール超過は現状「ゴールで停止（clamp）」仕様。誰かがゴールした時点で勝敗確定

## フェーズ2の実装フック（ここから着手する）
1. `Board.CellType` にイベント種別を追加（例: `ITEM`(どんぐり+), `DROP`(どんぐり-), `LOSE_TURN`, `MOVE`(±nマス), `EVENT`(選択肢)）
2. `Board.CELL_TYPES` 配列に配置パターンを定義（S/Gは NORMAL 固定）
3. `onLanded(index)` 内で `CELL_TYPES[index]` により分岐 → ダイアログ表示＆所持どんぐり更新
4. `BoardView.onDraw` のマス色分け（現状 S緑/G橙/他クリーム）をイベント種別ごとの色に拡張
5. 検討事項: ゴールぴったり停止ルールの要否、どんぐり数による結果発表

## フェーズ3の残タスク
- 複数の人間プレイヤー（同一端末パス&プレイ）: 人数選択画面で「にんげん/CPU」の割当UIを追加し、`Player.isHuman` を切り替えるだけで手番ロジックはそのまま使える
