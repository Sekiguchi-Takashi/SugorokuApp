# SugorokuApp B面（にんげんすごろく）BSIDE_HANDOFF

A面（どうぶつすごろく＝`app/`）と同一リポジトリ内で共存する **B面モジュール `bside/`** の仕様。
TubeHQApp（app/ + shot/）、NovelC/NovelD と同じ複数モジュール方式。

## モジュール構成
| モジュール | 中身 | applicationId | ラベル |
|---|---|---|---|
| `app/` | A面：どうぶつすごろく（既存・このチャットでは触らない） | `com.appathy.sugoroku` | どうぶつすごろく |
| `bside/` | B面：にんげんすごろく（本モジュール） | `com.appathy.sugoroku.human` | にんげんすごろく |

applicationId が違うので **2本とも同時にインストール可能**。

## ⚠️ チャット分担のルール（重要）
- **このチャットが触るのは `bside/` 配下と、B面専用ファイルのみ。** `app/` 配下は一切変更しない
- A面チャットが `app/` を更新するため、push前に必ず `git pull --rebase`（`deploy_bside.sh` が実行する）
- 共通ファイルの扱い：
  - `settings.gradle.kts` … B面追加時に `include(":bside")` を1行追記するのみ（冪等・スクリプトが判定）
  - `.github/workflows/build.yml`（A面）は**編集しない**。B面は別ファイル `.github/workflows/build_bside.yml` を新規追加してA面のCIと干渉させない
  - `build.gradle.kts`（ルート）/ `gradle.properties` / `debug.keystore` は既存のものをそのまま利用
- 署名は `bside/build.gradle.kts` がルート → `app/` → `bside/` の順に `debug.keystore` を探して自動採用。見つからない場合はGradleデフォルトのdebug署名になる

## 取り込み手順
```
cd ~
cp /sdcard/Download/SugorokuApp_Bside_v1.0.zip .
unzip -o SugorokuApp_Bside_v1.0.zip
bash ~/SugorokuApp/deploy_bside.sh "v1.0 bside ninngen sugoroku"
```
`deploy_bside.sh` は app/ と .git の存在を確認 → settings.gradle.kts を冪等パッチ → add/commit → pull --rebase → push。
CIは A面ワークフローと B面ワークフロー（artifact名 `SugorokuApp-bside-apk` / ファイル `bside-debug.apk`）の2本が走る。

## B面ゲーム仕様（v1.0）
学園すごろく。しょうがっこう(0-14) → ちゅうがっこう(15-29) → こうこう(30-44) の45マス。

- ステータス4種：べんきょう / うんどう / にんき / おこづかい（初期 5 / 5 / 5 / 1000）
- キャラ12人（`assets/charas_human.json` の `sets.human`）、2〜4人プレイ（人の数を選び残りはCPU）
- マス種別：START / NORMAL / GOOD / BAD / WARP(move±) / REST(rest回休み) / CHOICE(2択) / CHALLENGE(ステータス判定) / GOAL
- CHALLENGE は5箇所：がくげいかい(にんき6) / たいかい(うんどう12) / こうこうじゅけん(べんきょう18) / こくはく(にんき22) / さいごのしけん(べんきょう34)
- 全員ゴール後、得点 =(べ+う+に)×3 + おこづかい/200 で順位、最大ステータスでエンディング4種を分岐
- 画面遷移：`showTitle()` → `showCountSelect()` → `showHumanSelect()` → `showCharaSelect(index)` → `startGame()` → `showResult()`
- 描画：`BoardView`（空グラデ＋丘＋校舎のパララックス、ステージで配色変更、うねる一本道、ミニマップ、カメラlerp 0.18）／`RouletteView`（6分割・結果を先に抽選して角度決定・2.1秒Decelerate）
- 全てCanvas描画。A面のような**写真イベント素材は未使用**

## ファイル
```
SugorokuApp/
├── .github/workflows/build_bside.yml   # B面専用CI（A面のbuild.ymlとは別ファイル）
├── deploy_bside.sh                     # 冪等パッチ＋rebase付きpush
├── tools/validate_bside.py             # JSON整合・マス連番・WARP範囲・波括弧・テンプレート罠チェック
└── bside/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/charas_human.json / events_human.json
        ├── kotlin/com/appathy/sugoroku/human/MainActivity.kt   # 全ロジック1ファイル
        └── res/drawable/chara_kid01..12.png（512px RGBA）+ ic_launcher.png
```

## 落とし穴
- Kotlin文字列テンプレート罠：`$変数` の直後に日本語が続くとビルド失敗。本ソースは **`+` 連結のみ** で書いてあるので追記時も維持
- 納品前に `python3 tools/validate_bside.py` を通す
- ルートの `build.gradle.kts` に AGP 8.5.2 / Kotlin 1.9.24 が `apply false` で宣言済みである前提（A面と同じ）。宣言が無い場合はビルドが通らないので確認する

## バージョン履歴
| Ver | 内容 |
|---|---|
| v1.0 | B面モジュール新設。45マス3ステージ、12キャラ、2〜4人、選択肢・チャレンジ・ワープ・休み、結果とエンディング4種 |

## 次にやる候補
1. A面の写真イベント素材・図鑑・イベントエディタの仕組みをB面へ移植（A面ソース参照が必要）
2. `sets` スキーマをA面と共通化し、`SUGOROKU_DATA.md` を契約ファイルとしてリポジトリ直下に配置
3. スキル／アイテム、パス&プレイの手番受け渡し画面
