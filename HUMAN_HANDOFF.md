# SugorokuApp `human/` モジュール（にんげんすごろく）HANDOFF

同一リポジトリ内の **3モジュール構成**。このチャットが触るのは `human/` のみ。

| モジュール | 中身 | applicationId | オーナー |
|---|---|---|---|
| `app/` | A面：どうぶつすごろく | `com.appathy.sugoroku` | A面チャット |
| `bside/` | A面チャットが作ったB面（パッケージ `com.appathy.sugorokub`、A面コードのコピー派生） | A面チャット管理 | A面チャット |
| `human/` | 本モジュール：**すごろく人生ゲーム**（人間版／新規実装） | `com.appathy.sugoroku.human` | **このチャット** |

## ⚠️ 事故と再発防止（2026-08-12）
- このチャットが `bside/` に納品した v1.1〜v1.3 が、A面チャットの `bside/` を上書きし
  `bside/build.gradle.kts` の namespace が変わったことで `com.appathy.sugorokub` 側の `R` が解決不能になりビルド失敗した
- 復旧：`deploy_human.sh` が `bside/` 配下の**このチャットが書いた分だけ**を基準コミットへ戻し（無かったファイルは削除）、
  本モジュールを `human/` へ移設。以後 `bside/` には**一切触らない**
- 共有ファイルへの変更は `settings.gradle.kts` に `include(":human")` を1行足すのみ。`:bside` の行は残す
- CIは `.github/workflows/build_human.yml`（`gradle :human:assembleDebug` / artifact `SugorokuApp-human-apk` / `human-debug.apk`）。
  A面の `build.yml`、A面チャットの B面用ワークフローは触らない

## 取り込み手順
```
cd ~
cp /sdcard/Download/SugorokuApp_Human_v1.4.zip .
unzip -o SugorokuApp_Human_v1.4.zip
bash ~/SugorokuApp/deploy_human.sh "v1.4 human module" a1be7f0
```
第2引数は「A面チャットがB面モジュールを追加したコミット」。省略時は `a1be7f0`。

## ゲーム仕様（v1.4 = v1.3の内容をそのまま移設）
学園すごろく。しょうがっこう(0-14) → ちゅうがっこう(15-29) → こうこう(30-44) の45マス。

- ステータス4種：べんきょう / うんどう / にんき / おこづかい（初期 5 / 5 / 5 / 1000）
- キャラ2セット：`sets.human`＝プレイヤー6人（あかり・みなみ・さくら・はやと・けんと・たける）、
  `sets.partner`＝恋人候補6人（ひまり・しおり・ゆい・そうた・りく・あおい）
- マス種別：START / NORMAL / GOOD / BAD / WARP / REST / CHOICE / CRUSH / CHALLENGE / GOAL
- 恋人：30マス目CRUSHで候補3人から選択 → 34マス目こくはく（にんき22以上）で成立。得点+15
- 2〜4人プレイ（人の数を選び残りはCPU）、全員ゴールで順位＋エンディング4種
- 盤面は擬似3D（奥行き・楕円タイル・接地影・奥から手前への描画順・カメラlerp）
- 画面遷移：`showTitle()` → `showCountSelect()` → `showHumanSelect()` → `showCharaSelect()` → `playIntro()` → `startGame()` → `showResult()`

## アイコン・アプリ名
- アプリ名は `human/src/main/AndroidManifest.xml` の `android:label`（現在「すごろく人生ゲーム」）とタイトル画面の文言の2箇所
- アイコン `human/src/main/res/drawable/ic_launcher.png`（432px）は街角背景 3377.png の (560,330)-(1020,768) を切り出したもの。
  他の候補：桜の木の寄り(40,230)-(500,690) / コンビニ角(900,60)-(1408,568) / 信号寄り(1000,150)-(1360,510)
- ※「人生ゲーム」はタカラトミーの登録商標。配布範囲が身内・テスターを超える場合は名称の再検討が必要

## 素材の規約
- 立ち絵：`chara_<stageKey><NN>.png`。stageKey = `baby / kinder / elem / jhs / high / univ / work / senior`
  - 向き違いは `_s`（側面）/ `_b`（背面）を同名に付けて置くと盤面の駒が自動で使う。無ければ正面へフォールバック
  - `charas_human.json` の `images` に1行足すだけで反映。未定義ステージは近いステージの絵で代用
- 紹介ムービー：`human/src/main/res/raw/intro_<NN>.mp4`（NN=プレイヤー番号01〜06）。無いキャラはスキップ
  - H.264 baseline / yuv420p / AAC / 1280×720 / 10秒前後。`androidResources { noCompress += "mp4" }` 指定済み
  - マゼンタ背景は ffmpeg の `colorkey=0xE30BE3:0.32:0.10,despill` で差し替えてから収録

## 落とし穴
- Kotlin文字列テンプレート罠：`$変数` の直後に日本語でビルド失敗。本ソースは `+` 連結のみ
- 納品前に `python3 tools/validate_human.py`
- ルートの `build.gradle.kts` に AGP 8.5.2 / Kotlin 1.9.24 が `apply false` で宣言されている前提

## バージョン履歴
| Ver | 内容 |
|---|---|
| v1.0 | 初版。45マス3ステージ、12キャラ、選択肢・チャレンジ・ワープ・休み、結果とエンディング4種 |
| v1.1 | 中学生画像12枚追加、ライフステージ別の駒画像切替、盤面の擬似3D化 |
| v1.2 | プレイヤー6人＋恋人候補6人に分離、CRUSHマスと恋人成立 |
| v1.3 | キャラ決定時の紹介ムービー（さくら） |
| v1.4 | `bside/` から `human/` へモジュール移設（A面チャットのB面と衝突したため）。機能変更なし |
| v1.5 | アプリ名を **人生ゲーム** に変更（`android:label` とタイトル画面）。アイコンを街角背景（横断歩道＋桜）の切り出しに差し替え（432px） |
| v1.6 | アプリ名を **すごろく人生ゲーム** に変更（タイトル文字は9文字なので30spへ縮小） |

## 次にやる候補
1. `bside/`（A面チャット版の人間編）と本モジュールのどちらを本線にするか決める。並走させるならアプリ名で区別する
2. 高校生の立ち絵 `chara_high01..06`
3. 頭＋体の2パーツ合成（枚数削減の本命）
4. ライフステージ7〜8段階への盤面拡張、恋人→結婚ルート
