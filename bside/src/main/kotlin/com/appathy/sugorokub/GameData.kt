package com.appathy.sugorokub

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
        /** ステータス3種の表示名。モードごとに変わる */
        val statNames: List<String>,
        /** 使用するキャラクターセットのキー */
        val charaSet: String,
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
        "job" -> MainActivity.EventKind.JOB
        "shop" -> MainActivity.EventKind.SHOP
        "exam" -> MainActivity.EventKind.EXAM
        "love" -> MainActivity.EventKind.LOVE
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
            MainActivity.EventKind.JOB -> shared?.optJSONObject("job")
            MainActivity.EventKind.SHOP -> shared?.optJSONObject("shop")
            MainActivity.EventKind.EXAM -> shared?.optJSONObject("exam")
            MainActivity.EventKind.LOVE -> shared?.optJSONObject("love")
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
            dMove = num("move", 0),
            condStat = num("condStat", 0),
            condMin = num("condMin", 0),
            condBonus1 = num("condBonus1", 0),
            condBonus2 = num("condBonus2", 0),
            condBonus3 = num("condBonus3", 0),
            condMessage = str("condMessage", ""),
            // ちょうせん（受験・こくはく）のパラメータ
            challengeStat = num("challengeStat", if (kind == MainActivity.EventKind.LOVE) 3 else 1),
            baseRate = num("baseRate", if (kind == MainActivity.EventKind.LOVE) 20 else 30),
            passGain = num("passGain", 20),
            failLoss = num("failLoss", 3),
            failMove = num("failMove", -2),
            passMessage = str("passMessage", ""),
            failMessage = str("failMessage", "")
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
    private val DEFAULT_STATS = listOf("満腹", "充実", "友情")

    /**
     * ステージ群を読み込む。
     * @param fileName モードごとのファイル（stages.json / school_stages.json）
     */
    fun loadStages(context: Context, fileName: String = "stages.json"): StagesResult {
        val warnings = ArrayList<String>()
        val raw = readJson(context, fileName)
            ?: return StagesResult(emptyList(), MainActivity.Board.MAIN_COUNT, DEFAULT_STATS,
                "animal", arrayListOf("$fileName を読み込めませんでした"))

        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return StagesResult(emptyList(), MainActivity.Board.MAIN_COUNT, DEFAULT_STATS,
                "animal", arrayListOf("$fileName のJSON構文が不正です: ${e.message}"))
        }

        // ステータス名（省略時はつうじょう版の名前）
        val statNames = root.optJSONArray("statNames")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it, DEFAULT_STATS[it]) }
        }?.takeIf { it.size == 3 } ?: DEFAULT_STATS

        if (root.optInt("schemaVersion", 0) != 1) {
            warnings.add("$fileName: 未対応のschemaVersion")
        }
        val cellCount = root.optInt("mainCellCount", MainActivity.Board.MAIN_COUNT).let {
            if (it < 5) {
                warnings.add("mainCellCountが小さすぎます($it)")
                MainActivity.Board.MAIN_COUNT
            } else it
        }
        val shared = root.optJSONObject("shared")
        if (shared == null) warnings.add("$fileName: shared がありません")

        val list = ArrayList<StageData>()
        val arr = root.optJSONArray("stages")
        if (arr == null) {
            warnings.add("$fileName: stages 配列がありません")
        } else {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "ステージ${i + 1}")
                // -1 は「分岐なし」を意味するので警告しない
                val branch = o.optInt("branchCell", -1)
                if (branch != -1 && branch !in 1..(cellCount - 2)) {
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
        if (list.isEmpty()) warnings.add("$fileName: 有効なステージが1つもありません")
        return StagesResult(list, cellCount, statNames,
            root.optString("charaSet", "animal"), warnings)
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

    // ---------------- キャラクター（v5.2）----------------

    /**
     * assets/charas.json からキャラクターのセットを読み込む。
     * partner/child は結婚・出産のあるモードでのみ使う。
     * 学校モードのように1枚しか無いキャラは、partner/child が本人画像にフォールバックする。
     */
    fun loadCharas(context: Context, setKey: String): List<MainActivity.Chara> {
        val raw = readJson(context, "charas.json") ?: return emptyList()
        return try {
            val sets = JSONObject(raw).optJSONObject("sets") ?: return emptyList()
            val set = sets.optJSONObject(setKey) ?: return emptyList()
            val arr = set.optJSONArray("charas") ?: return emptyList()
            val out = ArrayList<MainActivity.Chara>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val img = o.optString("img", "")
                if (img.isBlank()) continue
                val self = drawableId(context, img)
                // partner/child が無いモードでは本人画像で代用する（表示が欠けないように）
                val partner = o.optString("partner", "").ifBlank { img }
                val child = o.optString("child", "").ifBlank { img }
                out.add(
                    MainActivity.Chara(
                        name = o.optString("name", "キャラ${i + 1}"),
                        resId = self,
                        partnerRes = drawableId(context, partner),
                        childRes = drawableId(context, child)
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "charas.json の解析に失敗", e)
            emptyList()
        }
    }

    // ---------------- 職業・スキル（v4.2）----------------

    data class SkillDef(
        val id: String, val name: String, val icon: String,
        val cost: Int, val desc: String,
        /** 常時のルーレット補正 */
        val dice: Int = 0
    )

    data class JobDef(
        val id: String, val name: String, val icon: String,
        val salary: Int, val requires: List<String>,
        val dManpuku: Int, val dJuujitsu: Int, val dYuujou: Int,
        val desc: String
    )

    data class JobsData(
        val startMoney: Int,
        val skills: List<SkillDef>,
        val jobs: List<JobDef>,
        val warnings: List<String>
    )

    /**
     * assets/jobs.json を読む。読めなければ最低限の「むしょく」だけを持つデータを返し、
     * 職業システムが無効な状態でもゲームが成立するようにする。
     */
    fun loadJobs(context: Context): JobsData {
        val warnings = ArrayList<String>()
        val fallback = JobsData(
            100,
            emptyList(),
            listOf(JobDef("none", "むしょく", "🌱", 30, emptyList(), 0, 0, 0, "")),
            warnings
        )
        val raw = readJson(context, "jobs.json") ?: run {
            warnings.add("jobs.json を読み込めませんでした")
            return fallback
        }
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            warnings.add("jobs.json のJSON構文が不正です")
            return fallback
        }
        if (root.optInt("schemaVersion", 0) != 1) warnings.add("jobs.json: 未対応のschemaVersion")

        val skills = ArrayList<SkillDef>()
        root.optJSONArray("skills")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) {
                    warnings.add("skills[$i]: id が空")
                    continue
                }
                skills.add(
                    SkillDef(
                        id = id,
                        name = o.optString("name", id),
                        icon = o.optString("icon", "⭐"),
                        cost = o.optInt("cost", 100).coerceAtLeast(0),
                        desc = o.optString("desc", ""),
                        dice = o.optInt("dice", 0).coerceIn(0, 3)
                    )
                )
            }
        } ?: warnings.add("jobs.json: skills がありません")

        val skillIds = skills.map { it.id }.toSet()
        val jobs = ArrayList<JobDef>()
        root.optJSONArray("jobs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) {
                    warnings.add("jobs[$i]: id が空")
                    continue
                }
                val req = ArrayList<String>()
                o.optJSONArray("requires")?.let { r ->
                    for (k in 0 until r.length()) {
                        val sid = r.optString(k, "")
                        if (sid.isNotBlank()) {
                            // 存在しないスキルを要求する職業は永久に就けないので弾く
                            if (sid in skillIds) req.add(sid)
                            else warnings.add("$id: 未知のスキル $sid を要求")
                        }
                    }
                }
                jobs.add(
                    JobDef(
                        id = id,
                        name = o.optString("name", id),
                        icon = o.optString("icon", "💼"),
                        salary = o.optInt("salary", 0).coerceAtLeast(0),
                        requires = req,
                        dManpuku = o.optInt("manpuku", 0),
                        dJuujitsu = o.optInt("juujitsu", 0),
                        dYuujou = o.optInt("yuujou", 0),
                        desc = o.optString("desc", "")
                    )
                )
            }
        } ?: warnings.add("jobs.json: jobs がありません")

        if (jobs.isEmpty()) {
            warnings.add("jobs.json: 有効な職業が1つもありません")
            return fallback
        }
        return JobsData(
            startMoney = root.optInt("startMoney", 100).coerceAtLeast(0),
            skills = skills,
            jobs = jobs,
            warnings = warnings
        )
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
