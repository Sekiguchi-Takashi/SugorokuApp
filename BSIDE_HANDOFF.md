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
| v1.1 | 中学生画像12枚を追加（背景透過・512px）。ライフステージ別に駒画像が切り替わる仕組み（charas_human.json schemaVersion 2）。盤面を擬似3D化（奥行き・楕円タイル・接地影・奥から手前への描画順） |

## ライフステージと画像の規約（v1.1〜）
`stageKeys` = `baby / kinder / elem / jhs / high / univ / work / senior`（生後・幼稚園 → 老後）。

- ファイル名は **`chara_<stageKey><NN>.png`**（例 `chara_high01.png`）。NN=01〜12 はキャラ番号で固定
- 向き違いは同名に接尾辞を付ける：**`_s`=側面 / `_b`=背面**（例 `chara_jhs01_s.png`）。
  盤面の駒は `_s` を優先して探し、無ければ接尾辞なしへ自動フォールバックするので、**あるぶんだけ置けば動く**
- `charas_human.json` の各キャラ `images` に `"high": "chara_high01"` のように追記するだけで反映される
- 未定義のステージは近いステージの画像へフォールバック（現状 high/univ/work/senior は jhs 画像が出る）
- 盤面ステージ側は `events_human.json` の `stages[].key` で stageKey を指定する

### 画像枚数の設計（12キャラ×8ステージ×3向き=288枚を避けるための方針）
| 案 | 構成 | 枚数 | 備考 |
|---|---|---|---|
| A 全部一枚絵 | キャラ×ステージ×向き | 288 | 破綻。採用しない |
| B 頭＋体の2パーツ | 頭=キャラ×向き（12×3=36）＋体=ステージ×体型2×向き（8×2×3=48） | **84** | 推奨。首の接合位置を固定すれば合成できる |
| C 完全パーツ分割 | 顔・髪・服を別レイヤー＋色フィルタ | 約57 | 最小だが位置合わせが最難関 |
| D キャラを6人に絞る | 案Bの半分 | **42** | 最大4人プレイなので6人でも足りる |

推奨は **D+B**（キャラ6人・頭＋体の2パーツ）。年齢差は頭身比率（子どもは頭大きめ）で出し、老後の白髪は
実行時のカラーフィルタで作れるため画像を増やさずに済む。ただし合成はまだ未実装で、v1.1 は一枚絵方式。

## 次にやる候補
1. ライフステージ7〜8段階への盤面拡張（現状3ステージ45マス。素材が揃った段階で60〜70マスへ）
2. 頭＋体の2パーツ合成レンダラ（枚数削減の本命。首位置の基準を決めるのが先）
3. 背面画像を使う盤面（道を奥へ折り返すレイアウト。`_b` が揃ってから）
4. A面の写真イベント素材・図鑑・イベントエディタの仕組みをB面へ移植（A面ソース参照が必要）
5. `sets` スキーマをA面と共通化し、`SUGOROKU_DATA.md` を契約ファイルとしてリポジトリ直下に配置
