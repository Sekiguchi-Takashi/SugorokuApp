package com.appathy.sugoroku

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * ゲームデータ（ステージ・イベント）をJSONから読み込む基盤。
 *
 * v4.0a: 洞窟のみJSON化。本線ステージは MainActivity 内の定義のまま。
 * v4.0b で本線3ステージも stages.json へ移す予定。
 *
 * 方針:
 * - パースは Android 標準の org.json のみ（外部依存ゼロの規約を維持）
 * - 画像は文字列名で持ち、resources.getIdentifier() で解決する
 * - 読み込み優先順は filesDir → assets（v4.1のイベントエディタで filesDir に書き出す想定）
 * - どこで失敗しても例外を投げずフォールバックする。データが壊れてもアプリは起動する
 */
object GameData {

    private const val TAG = "GameData"

    /** 画像が見つからないときの最終フォールバック */
    private const val FALLBACK_BG = "bg_forest"

    /** JSONから読み込んだ1ワールド分の盤面 */
    data class BoardData(
        val name: String,
        val cellCount: Int,
        val bgRes: Int,
        val events: Map<Int, MainActivity.GameEvent>,
        /** 洞窟用: 分岐マスから何マス先の本線に復帰するか */
        val returnSkip: Int = 20
    )

    /** JSONから読み込んだ本線1ステージ分 */
    data class StageData(
        val name: String,
        val bgRes: Int,
        val branchCell: Int,
        val events: Map<Int, MainActivity.GameEvent>
    )

    /** 本線ステージ群の読み込み結果 */
    data class StagesResult(
        val stages: List<StageData>,
        val mainCellCount: Int,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = warnings.isEmpty()
    }

    /** 読み込み結果。エラーがあっても data は必ず返る（イベント0件になることはある） */
    data class LoadResult(
        val data: BoardData,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = warnings.isEmpty()
    }

    // ---------------- 画像解決 ----------------

    /**
     * drawable名 → リソースID。見つからなければ fallback、それも無ければ 0 を返す。
     * 結果をキャッシュして getIdentifier の呼び出し回数を抑える。
     */
    private val resCache = HashMap<String, Int>()

    fun drawableId(context: Context, name: String?): Int {
        if (name.isNullOrBlank()) return drawableId(context, FALLBACK_BG)
        resCache[name]?.let { return it }
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        val resolved = if (id != 0) id else {
            Log.w(TAG, "drawable not found: $name → fallback")
            if (name == FALLBACK_BG) 0
            else context.resources.getIdentifier(FALLBACK_BG, "drawable", context.packageName)
        }
        resCache[name] = resolved
        return resolved
    }

    // ---------------- 読み込み ----------------

    /**
     * JSONを読む。filesDir に同名ファイルがあればそちらを優先する。
     * 読めなければ null。
     */
    private fun readJson(context: Context, fileName: String): String? {
        val override = File(context.filesDir, fileName)
        if (override.exists()) {
            try {
                return override.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "filesDir読み込み失敗、assetsにフォールバック: $fileName", e)
            }
        }
        return try {
            context.assets.open(fileName).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "assets読み込み失敗: $fileName", e)
            null
        }
    }

    /** kind文字列 → EventKind。未知の値は NORMAL 扱い */
    private fun parseKind(s: String?): MainActivity.EventKind = when (s?.lowercase()) {
        "wedding" -> MainActivity.EventKind.WEDDING
        "birth" -> MainActivity.EventKind.BIRTH
        else -> MainActivity.EventKind.NORMAL
    }

    /**
     * 1件のイベントJSONを GameEvent にする。
     * shared（結婚/出産の共通定義）があれば、未指定の項目をそこから継承する。
     */
    private fun parseEvent(
        context: Context,
        o: JSONObject,
        shared: JSONObject?
    ): MainActivity.GameEvent {
        val kind = parseKind(o.optString("kind", "normal"))
        // 共通定義（wedding / birth）を土台にして、個別指定があれば上書きする
        val base: JSONObject? = when (kind) {
            MainActivity.EventKind.WEDDING -> shared?.optJSONObject("wedding")
            MainActivity.EventKind.BIRTH -> shared?.optJSONObject("birth")
            else -> null
        }
        fun str(key: String, def: String): String =
            if (o.has(key)) o.optString(key, def) else base?.optString(key, def) ?: def
        fun num(key: String, def: Int): Int =
            if (o.has(key)) o.optInt(key, def) else base?.optInt(key, def) ?: def

        return MainActivity.GameEvent(
            bgRes = drawableId(context, str("bg", FALLBACK_BG)),
            message = str("message", ""),
            dManpuku = num("manpuku", 0),
            dJuujitsu = num("juujitsu", 0),
            dYuujou = num("yuujou", 0),
            groupSize = num("group", 1).coerceIn(1, 4),
            kind = kind,
            dMove = num("move", 0)
        )
    }

    /**
     * イベント配列を読む。範囲外・重複は警告を積んでスキップする。
     * @param label 警告メッセージに出す識別子
     */
    private fun parseEvents(
        context: Context,
        arr: org.json.JSONArray?,
        cellCount: Int,
        shared: JSONObject?,
        label: String,
        warnings: MutableList<String>
    ): Map<Int, MainActivity.GameEvent> {
        val events = LinkedHashMap<Int, MainActivity.GameEvent>()
        if (arr == null) {
            warnings.add("$label: events 配列がありません")
            return events
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                warnings.add("$label: events[$i] がオブジェクトではありません")
                continue
            }
            val cell = o.optInt("cell", -1)
            if (cell < 1 || cell > cellCount - 2) {
                warnings.add("$label: cell=$cell が範囲外(1〜${cellCount - 2})")
                continue
            }
            if (events.containsKey(cell)) warnings.add("$label: cell=$cell が重複（後勝ち）")
            val ev = parseEvent(context, o, shared)
            if (ev.message.isBlank()) warnings.add("$label: cell=$cell のmessageが空です")
            events[cell] = ev
        }
        return events
    }

    /**
     * 本線ステージ群（stages.json）を読み込む。
     * 1ステージも読めなければ stages が空のまま返るので、呼び出し側でフォールバックすること。
     */
    fun loadStages(context: Context): StagesResult {
        val warnings = ArrayList<String>()
        val raw = readJson(context, "stages.json")
            ?: return StagesResult(emptyList(), MainActivity.Board.MAIN_COUNT,
                arrayListOf("stages.json を読み込めませんでした"))

        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return StagesResult(emptyList(), MainActivity.Board.MAIN_COUNT,
                arrayListOf("stages.json のJSON構文が不正です: ${e.message}"))
        }

        if (root.optInt("schemaVersion", 0) != 1) {
            warnings.add("stages.json: 未対応のschemaVersion")
        }
        val cellCount = root.optInt("mainCellCount", MainActivity.Board.MAIN_COUNT).let {
            if (it < 5) {
                warnings.add("mainCellCountが小さすぎます($it)")
                MainActivity.Board.MAIN_COUNT
            } else it
        }
        val shared = root.optJSONObject("shared")
        if (shared == null) warnings.add("stages.json: shared がありません（結婚/出産が空になります）")

        val list = ArrayList<StageData>()
        val arr = root.optJSONArray("stages")
        if (arr == null) {
            warnings.add("stages.json: stages 配列がありません")
        } else {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "ステージ${i + 1}")
                val branch = o.optInt("branchCell", -1)
                if (branch !in 1..(cellCount - 2)) {
                    warnings.add("$name: branchCell=$branch が範囲外")
                }
                val events = parseEvents(
                    context, o.optJSONArray("events"), cellCount, shared, name, warnings
                )
                if (branch in events.keys) {
                    warnings.add("$name: branchCell がイベントマスと重複しています")
                }
                list.add(
                    StageData(
                        name = name,
                        bgRes = drawableId(context, o.optString("bg", FALLBACK_BG)),
                        branchCell = branch,
                        events = events
                    )
                )
            }
        }
        if (list.isEmpty()) warnings.add("stages.json: 有効なステージが1つもありません")
        return StagesResult(list, cellCount, warnings)
    }

    /**
     * 1つの盤面JSONを読み込む。
     * 壊れた項目は警告を積んでスキップし、読める分だけ返す。
     */
    fun loadBoard(
        context: Context,
        fileName: String,
        defaultName: String,
        defaultCellCount: Int
    ): LoadResult {
        val warnings = ArrayList<String>()
        val raw = readJson(context, fileName)
        if (raw == null) {
            warnings.add("$fileName を読み込めませんでした")
            return LoadResult(
                BoardData(defaultName, defaultCellCount, drawableId(context, FALLBACK_BG), emptyMap()),
                warnings
            )
        }

        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            warnings.add("$fileName のJSON構文が不正です: ${e.message}")
            return LoadResult(
                BoardData(defaultName, defaultCellCount, drawableId(context, FALLBACK_BG), emptyMap()),
                warnings
            )
        }

        val version = root.optInt("schemaVersion", 0)
        if (version != 1) warnings.add("未対応のschemaVersion: $version")

        val name = root.optString("name", defaultName)
        val cellCount = root.optInt("cellCount", defaultCellCount).let {
            if (it < 5) {
                warnings.add("cellCountが小さすぎます($it) → $defaultCellCount を使用")
                defaultCellCount
            } else it
        }
        val boardBg = drawableId(context, root.optString("bg", FALLBACK_BG))
        val returnSkip = root.optInt("returnSkip", 20).coerceIn(1, MainActivity.Board.MAIN_COUNT - 1)

        val events = parseEvents(
            context, root.optJSONArray("events"), cellCount, null, name, warnings
        )

        return LoadResult(BoardData(name, cellCount, boardBg, events, returnSkip), warnings)
    }

    // ---------------- 保存・復元（v4.1 イベントエディタ用）----------------

    /**
     * 生のJSON文字列を取得する（filesDir優先）。エディタが読み書きの起点に使う。
     */
    fun rawJson(context: Context, fileName: String): String? = readJson(context, fileName)

    /** assets の初期データをそのまま返す（「はじめに戻す」用） */
    fun rawAssetJson(context: Context, fileName: String): String? = try {
        context.assets.open(fileName).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "assets読み込み失敗: $fileName", e)
        null
    }

    /**
     * filesDir にJSONを保存する。
     * 破損防止のため一時ファイルへ書いてから rename する。
     * @return 成功したら true
     */
    fun saveJson(context: Context, fileName: String, content: String): Boolean {
        return try {
            // 保存前に構文チェック。壊れたJSONを書き込むと次回起動で読めなくなる
            JSONObject(content)
            val tmp = File(context.filesDir, "$fileName.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            val dst = File(context.filesDir, fileName)
            if (dst.exists()) dst.delete()
            val ok = tmp.renameTo(dst)
            if (!ok) Log.e(TAG, "rename失敗: $fileName")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "保存失敗: $fileName", e)
            false
        }
    }

    /** filesDir の編集済みデータを消して assets の初期状態に戻す */
    fun resetToAssets(context: Context, fileName: String): Boolean {
        val f = File(context.filesDir, fileName)
        return if (f.exists()) f.delete() else true
    }

    /** ユーザーが編集したデータが存在するか */
    fun hasUserData(context: Context, fileName: String): Boolean =
        File(context.filesDir, fileName).exists()

    /** 洞窟データを読み込む */
    fun loadCave(context: Context): LoadResult =
        loadBoard(context, "cave.json", "どうくつ", MainActivity.Board.CAVE_COUNT)
}
