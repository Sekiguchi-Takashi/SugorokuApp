# SugorokuApp（どうぶつすごろく）HANDOFF

## 概要
どうぶつの森風の世界観のすごろくアプリ。プレイヤーはキャラ（しばいぬ・うさぎ・いのしし・トラ）を選び、ルーレット(1〜6)で30マスの盤面を進んでゴールを目指す。

- 現在バージョン: **v1.0（フェーズ1完了）**
- パッケージ: `com.appathy.sugoroku` / minSdk 26 / targetSdk 34

## ロードマップ
| フェーズ | 内容 | 状態 |
|---|---|---|
| 1 | ルーレット＋すごろく移動＋ゴール判定 | ✅ v1.0 |
| 2 | 停止マスでイベント発生（どうぶつの森風：どんぐり集め等） | 未着手 |
| 3 | 同一端末パス&プレイで2〜4人対戦（通信なし・依存ゼロ維持） | 未着手 |

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
- 画面遷移は `setContentView` 切替: `showTitle()` → `showCharaSelect()` → `showGame()`
- `Board` object: `COLS=5, ROWS=6, CELL_COUNT=30, GOAL_INDEX=29`。盤面は下段左スタートの蛇行（サーペンタイン）配置
- `BoardView`: Canvas描画（マス円・経路線・駒ビットマップ）。`position` 更新→`invalidate()`
- `RouletteView`: 6色セグメント。結果を先に抽選し、その角度に減速停止（DecelerateInterpolator 2.6秒）。`locked` で移動中の再スピン防止
- 移動: `movePiece()` が Handler 300ms間隔で1マスずつ進める。ゴール超過は現状「ゴールで停止（clamp）」仕様

## フェーズ2の実装フック（ここから着手する）
1. `Board.CellType` にイベント種別を追加（例: `ITEM`(どんぐり+), `DROP`(どんぐり-), `LOSE_TURN`, `MOVE`(±nマス), `EVENT`(選択肢)）
2. `Board.CELL_TYPES` 配列に配置パターンを定義（S/Gは NORMAL 固定）
3. `onLanded(index)` 内で `CELL_TYPES[index]` により分岐 → ダイアログ表示＆所持どんぐり更新
4. `BoardView.onDraw` のマス色分け（現状 S緑/G橙/他クリーム）をイベント種別ごとの色に拡張
5. 検討事項: ゴールぴったり停止ルールの要否、どんぐり数による結果発表

## フェーズ3の方針メモ
- プレイヤー配列 `List<Player(chara, position, acorns)>` に一般化し、手番インデックスで回す
- 同一端末パス&プレイ（「〇〇のばん」表示）。CPU対戦は自動スピンで実現可能
