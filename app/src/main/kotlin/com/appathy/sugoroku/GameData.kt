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

        val events = LinkedHashMap<Int, MainActivity.GameEvent>()
        val arr = root.optJSONArray("events")
        if (arr == null) {
            warnings.add("events 配列がありません")
        } else {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    warnings.add("events[$i] がオブジェクトではありません")
                    continue
                }
                val cell = o.optInt("cell", -1)
                // S(0) と G(cellCount-1) にはイベントを置けない
                if (cell < 1 || cell > cellCount - 2) {
                    warnings.add("events[$i] cell=$cell が範囲外(1〜${cellCount - 2})")
                    continue
                }
                if (events.containsKey(cell)) {
                    warnings.add("cell=$cell が重複しています（後勝ち）")
                }
                val msg = o.optString("message", "")
                if (msg.isBlank()) warnings.add("cell=$cell のmessageが空です")

                events[cell] = MainActivity.GameEvent(
                    bgRes = drawableId(context, o.optString("bg", FALLBACK_BG)),
                    message = msg,
                    dManpuku = o.optInt("manpuku", 0),
                    dJuujitsu = o.optInt("juujitsu", 0),
                    dYuujou = o.optInt("yuujou", 0),
                    groupSize = o.optInt("group", 1).coerceIn(1, 4),
                    kind = parseKind(o.optString("kind", "normal")),
                    dMove = o.optInt("move", 0)
                )
            }
        }

        return LoadResult(BoardData(name, cellCount, boardBg, events, returnSkip), warnings)
    }

    /** 洞窟データを読み込む */
    fun loadCave(context: Context): LoadResult =
        loadBoard(context, "cave.json", "どうくつ", MainActivity.Board.CAVE_COUNT)
}
