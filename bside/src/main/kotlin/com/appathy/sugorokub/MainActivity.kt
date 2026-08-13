package com.appathy.sugorokub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.animation.TimeInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * どうぶつすごろく v3.0（ステージ制）
 * - 誰か1人がゴールしたら全員つぎのステージへ（ステータス・結婚・子どもは引き継ぎ）
 * - 各ステージに 結婚マス💒 と 出産マス👶 を2箇所ずつ配置（未達成のときだけ発生）
 * - 戻るマスを多めに配置
 * - 全3ステージ: もりのまち / たびのステージ / あそびのステージ
 */
class MainActivity : Activity() {

    // ================= データ定義 =================
    data class Chara(val name: String, val resId: Int, val partnerRes: Int, val childRes: Int)

    data class Player(
        val chara: Chara, val isHuman: Boolean, var position: Int = 0,
        var manpuku: Int = 0, var juujitsu: Int = 0, var yuujou: Int = 0,
        var boostNext: Boolean = false,  // 満腹スキル発動中（次のルーレット+3）
        var married: Boolean = false,    // 結婚後はイベントのステータス上昇が1.5倍
        var hasChild: Boolean = false,   // 子どもが生まれると2.0倍
        var inCave: Boolean = false,     // 洞窟ルートを進行中
        var caveReturn: Int = 0,         // 洞窟を抜けたときに復帰する本線のマス
        val humanNo: Int = 0,            // 人間プレイヤーの番号（1P/2P）。CPUは0
        var money: Int = 0,              // 所持金
        var jobId: String = "none",      // 就いている職業のid
        var passedExam: Boolean = false, // 受験に合格したか
        var examTries: Int = 0,          // 受験に挑戦した回数（上限あり）
        var hasLover: Boolean = false,   // こくはくに成功したか
        var skipNext: Boolean = false,   // つぎの じぶんの番を1回休む
        var stageWins: Int = 0,          // ステージ1着の かず（勝敗の どうてん判定に使う）
        val items: ArrayList<String> = ArrayList(),  // もちもの（items.json の id）
        var itemBoost: Int = 0,          // もちものによる 次のルーレット加算
        var restGuard: Boolean = false,  // ⏰ おやすみを1回ふせぐ
        // ここから下は「おもいでしょう」（称号）のための かぞえ
        var backSteps: Int = 0,          // もどったマスの合計
        var restCount: Int = 0,          // おやすみした かず
        var braveCount: Int = 0,         // 選択肢で もどるほうを えらんだ かず
        var itemUsed: Int = 0,           // もちものを つかった かず
        val skills: MutableSet<String> = HashSet()   // 取得済みスキルのid
    ) {
        val total: Int get() = manpuku + juujitsu + yuujou
    }

    enum class EventKind { NORMAL, WEDDING, BIRTH, JOB, SHOP, EXAM, LOVE }

    /**
     * 選択肢イベントの えらびかた1つ分。
     * えらぶと この効果だけが起きる。bgRes が 0 なら親イベントの写真を使う。
     */
    data class Choice(
        val label: String,
        val message: String,
        val bgRes: Int = 0,
        val dManpuku: Int = 0,
        val dJuujitsu: Int = 0,
        val dYuujou: Int = 0,
        val dMove: Int = 0,
        val skipTurn: Boolean = false,
        /** えらぶと もらえる もちもの（items.json の id）。空なら なし */
        val itemId: String = ""
    )

    data class GameEvent(
        val bgRes: Int,
        val message: String,
        val dManpuku: Int = 0,
        val dJuujitsu: Int = 0,
        val dYuujou: Int = 0,
        val groupSize: Int = 1,
        val kind: EventKind = EventKind.NORMAL,
        /** イベントの結果すすむ/もどるマス数。0なら移動なし（0のイベントが多数） */
        val dMove: Int = 0,
        /**
         * 条件ボーナス。指定ステータス(1〜3)が condMin 以上なら追加で加算する。
         * 例: 運動が25以上なら モテモテ+8
         */
        val condStat: Int = 0,
        val condMin: Int = 0,
        val condBonus1: Int = 0,
        val condBonus2: Int = 0,
        val condBonus3: Int = 0,
        val condMessage: String = "",
        /**
         * ちょうせん（受験・こくはく）の設定。
         * 指定ステータスが高いほど成功しやすく、成功で全ステータスが上がる。
         */
        val challengeStat: Int = 1,
        val baseRate: Int = 30,
        val passGain: Int = 20,
        val failLoss: Int = 3,
        val failMove: Int = -2,
        val passMessage: String = "",
        val failMessage: String = "",
        /** true なら このイベントのあと つぎの じぶんの番を1回休む */
        val skipTurn: Boolean = false,
        /** 空でなければ選択肢イベント。えらんだものの効果だけが起きる */
        val choices: List<Choice> = emptyList(),
        /** もらえる もちもの（items.json の id）。空なら なし */
        val itemId: String = ""
    )

    /** 1ステージ分の盤面定義 */
    data class Stage(
        val name: String,
        val bgRes: Int,          // 盤面の背景
        val events: Map<Int, GameEvent>,
        val branchCell: Int      // ここに止まると洞窟ルートへ分岐（+20マス先で復帰）
    )

    /** スキル定数 */
    object Skill {
        const val MANPUKU_COST = 10          // 満腹を消費して
        const val MANPUKU_BONUS = 3          //   ルーレット+3
        const val JUUJITSU_COST = 10         // 充実を消費して
        const val JUUJITSU_PUSH = 2          //   自分以外を2マス戻す
        const val YUUJOU_THRESHOLD = 30      // 友情がこの値以上なら
        const val YUUJOU_BONUS = 1           //   常にルーレット+1
        const val MARRIED_MULTIPLIER = 1.5f  // 結婚後のイベント上昇倍率（プラスのみ）
        const val CHILD_MULTIPLIER = 2.0f    // 子どもが生まれた後の倍率
        const val CLEAR_BONUS = 10           // ステージ1着ボーナス（充実・友情に加算）
        const val SKILL_SCORE = 10           // スキル1つあたりのスコア
        const val MARRIED_SCORE = 30         // けっこんのスコア
        const val CHILD_SCORE = 50           // こどものスコア
        const val EXAM_SCORE = 50            // 学校編: じゅけん ごうかくのスコア
        const val LOVER_SCORE = 30           // 学校編: こいびとが できたスコア
        const val EXAM_MAX_TRIES = 2         // 受験に挑戦できる回数
    }

    /**
     * あそびかたのモード。ステージ定義ファイルとステータス名がモードごとに変わる。
     * 小学校版は今後 中学校 → 高校 → 大学 とステージを足していく予定。
     */
    enum class GameMode(val title: String, val desc: String, val file: String) {
        // B面は学校編のみ。つうじょう版は A面（:app）にある
        SCHOOL("がっこうへん", "べんきょう・運動・モテモテで じゅけんに いどむ", "stages.json")
    }

    /**
     * 盤面のズーム。画面に何マス見えるかで表す。
     * BoardView はステージ切替のたびに作り直されるので、設定はここに保持する。
     */
    object Zoom {
        val steps = floatArrayOf(3f, 5f, 8f)
        var index = 0
        val cells: Float get() = steps[index]
        val label: String get() = "${steps[index].toInt()}マス"
        val isMax: Boolean get() = index == steps.size - 1
        fun next() { index = (index + 1) % steps.size }
        /** 縮小するほどマスが潰れるので、比率を少し大きめに補正する */
        val cellRatio: Float get() = when (index) {
            0 -> 0.27f
            1 -> 0.30f
            else -> 0.33f
        }
    }

    object Speed {
        var fast = false
        val spinMs get() = if (fast) 1100L else 2600L
        val stepMs get() = if (fast) 140L else 300L
        val resultMs get() = if (fast) 300L else 700L
        val cpuWaitMs get() = if (fast) 400L else 1000L
        val eventWaitMs get() = if (fast) 350L else 700L
    }

    /** モードごとのキャラクター一覧（assets/charas.json から読み込み） */
    private var charas: List<Chara> = emptyList()

    // ================= ゲームデータ（JSONから読み込み） =================
    /** 本線ステージ（assets/stages.json） */
    private var stages: List<Stage> = emptyList()

    /** 洞窟ルート（assets/cave.json）。読み込み失敗時は空マップで安全に動く */
    private var caveEvents: Map<Int, GameEvent> = emptyMap()
    private var caveCellCount: Int = Board.CAVE_COUNT

    /** 洞窟の盤面背景（cave.json の bg） */
    private var caveBgRes: Int = 0

    /** 選択中のモード */
    private var mode: GameMode = GameMode.SCHOOL

    /** ステータス3種の表示名（モードごとに変わる） */
    private var statNames: List<String> = listOf("満腹", "充実", "友情")

    /** 職業・スキル（assets/jobs.json） */
    private var jobsData: GameData.JobsData =
        GameData.JobsData(100, emptyList(), emptyList(), emptyList())

    /**
     * そのイベントが「損をする」ものか。
     * ステータスの合計がマイナス、または もどる ならbadとして紫で示す。
     */
    private fun isBad(ev: GameEvent): Boolean =
        ev.kind == EventKind.NORMAL && ev.choices.isEmpty() && !ev.skipTurn &&
            (ev.dManpuku + ev.dJuujitsu + ev.dYuujou < 0 || ev.dMove < 0)

    /** 1回休みマス（選択肢マスは えらんだ結果しだいなので ふくめない） */
    private fun isRest(ev: GameEvent): Boolean =
        ev.kind == EventKind.NORMAL && ev.choices.isEmpty() && ev.skipTurn

    /** 選択肢マス */
    private fun isChoice(ev: GameEvent): Boolean =
        ev.kind == EventKind.NORMAL && ev.choices.isNotEmpty()

    private fun jobOf(id: String): GameData.JobDef? = jobsData.jobs.firstOrNull { it.id == id }
    private fun skillOf(id: String): GameData.SkillDef? = jobsData.skills.firstOrNull { it.id == id }

    /**
     * 最終スコア。
     * ためこむだけで勝てないよう、しごととスキルへの投資も点になるようにしている。
     *   ステータス合計 ＋ おかね/10 ＋ きゅうりょう/10 ＋ スキル数×10 ＋ かぞく
     */
    private fun scoreOf(p: Player): Int {
        // 学校編は しごと・おかね・けっこん を使わないので、
        // ステータスと ちょうせん の成果だけで採点する
        if (mode == GameMode.SCHOOL) {
            var s = p.total
            if (p.passedExam) s += Skill.EXAM_SCORE
            if (p.hasLover) s += Skill.LOVER_SCORE
            s += p.items.size * itemsData.unusedScore
            return s
        }
        var s = p.total + p.money / 10 + p.items.size * itemsData.unusedScore
        s += (jobOf(p.jobId)?.salary ?: 0) / 10
        s += p.skills.size * Skill.SKILL_SCORE
        if (p.married) s += Skill.MARRIED_SCORE
        if (p.hasChild) s += Skill.CHILD_SCORE
        return s
    }

    /**
     * 「おもいでしょう」。てんすうには ひびかない、わらうための しょう。
     * 1位が いない（ぜんいん0）しょうは 出さない。
     */
    private fun memoryAwards(): List<String> {
        val out = ArrayList<String>()
        fun award(icon: String, name: String, note: String, value: (Player) -> Int) {
            val best = players.maxByOrNull { value(it) } ?: return
            val v = value(best)
            if (v <= 0) return
            val tops = players.filter { value(it) == v }
            out.add("$icon $name … ${tops.joinToString("と") { who(it) }}（$note$v）")
        }
        award("🍌", "ドジっこしょう", "もどったマス ") { it.backSteps }
        award("😴", "ねぼすけしょう", "おやすみ ") { it.restCount }
        award("🔥", "ぼうけんかしょう", "きけんな みちを ") { it.braveCount }
        award("🎒", "どうぐつかいしょう", "つかった もちもの ") { it.itemUsed }
        award("🚩", "いちばんのりしょう", "1ちゃく ") { it.stageWins }
        return out
    }

    /** 分岐マスから何マス先の本線に復帰するか（cave.json の returnSkip） */
    private var CAVE_SKIP = 20

    /** JSONデータを読み込む。onCreate で1回だけ呼ぶ */
    private fun loadGameData() {
        val warnings = ArrayList<String>()

        // 洞窟
        val cave = GameData.loadCave(this)
        caveEvents = cave.data.events
        caveCellCount = cave.data.cellCount
        CAVE_SKIP = cave.data.returnSkip
        caveBgRes = cave.data.bgRes
        Board.CAVE_COUNT = caveCellCount
        warnings.addAll(cave.warnings)

        // 職業・スキル
        jobsData = GameData.loadJobs(this)
        warnings.addAll(jobsData.warnings)

        // もちもの
        itemsData = GameData.loadItems(this)
        warnings.addAll(itemsData.warnings)

        // 本線ステージ（モードごとにファイルが変わる）
        val st = GameData.loadStages(this, mode.file)
        statNames = st.statNames
        charas = GameData.loadCharas(this, st.charaSet)
        if (charas.isEmpty()) warnings.add("キャラクターを読み込めませんでした")
        stages = st.stages.map {
            Stage(name = it.name, bgRes = it.bgRes, events = it.events, branchCell = it.branchCell)
        }
        Board.MAIN_COUNT = st.mainCellCount
        warnings.addAll(st.warnings)

        // ステージが1つも読めなければ最低限の空ステージを用意して起動だけは通す
        if (stages.isEmpty()) {
            stages = listOf(
                Stage("ステージ1", GameData.drawableId(this, "bg_forest"), emptyMap(), -1)
            )
        }

        if (warnings.isNotEmpty()) {
            Toast.makeText(
                this,
                "データの警告(${warnings.size}件):\n" + warnings.take(3).joinToString("\n"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private lateinit var itemsData: GameData.ItemsData

    /** id から もちものの定義を引く */
    private fun itemOf(id: String): GameData.ItemDef? = itemsData.items.find { it.id == id }

    private val players = ArrayList<Player>()
    private var turn = 0
    private var stageIndex = 0
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var boardView: BoardView
    private lateinit var rouletteView: RouletteView
    private lateinit var statusText: TextView
    private lateinit var speedButton: Button
    private lateinit var zoomButton: Button
    private lateinit var startButton: Button
    private lateinit var statsBar: TextView
    private lateinit var manpukuSkillButton: Button
    private lateinit var itemButton: Button
    private lateinit var juujitsuSkillButton: Button

    private val stage: Stage get() = stages[stageIndex]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadGameData()
        showTitle()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10).toFloat()
            if (stroke != 0) setStroke(dp(2), stroke)
        }

    /** 現在のステージ定義を盤面へ反映 */
    private fun applyStage() {
        Board.normalEventCells = stage.events.filterValues { it.kind == EventKind.NORMAL }.keys
        Board.weddingCells = stage.events.filterValues { it.kind == EventKind.WEDDING }.keys
        Board.birthCells = stage.events.filterValues { it.kind == EventKind.BIRTH }.keys
        Board.jobCells = stage.events.filterValues { it.kind == EventKind.JOB }.keys
        Board.shopCells = stage.events.filterValues { it.kind == EventKind.SHOP }.keys
        Board.examCells = stage.events.filterValues { it.kind == EventKind.EXAM }.keys
        Board.loveCells = stage.events.filterValues { it.kind == EventKind.LOVE }.keys
        Board.badCells = stage.events.filterValues { isBad(it) }.keys
        Board.caveBadCells = caveEvents.filterValues { isBad(it) }.keys
        Board.restCells = stage.events.filterValues { isRest(it) }.keys
        Board.caveRestCells = caveEvents.filterValues { isRest(it) }.keys
        Board.choiceCells = stage.events.filterValues { isChoice(it) }.keys
        Board.caveChoiceCells = caveEvents.filterValues { isChoice(it) }.keys
        Board.branchCell = stage.branchCell
        Board.caveEventCells = caveEvents.keys
    }

    // ---------------- タイトル画面 ----------------
    private fun showTitle() {
        handler.removeCallbacksAndMessages(null)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E8F5E9"))
        }
        root.addView(TextView(this).apply {
            text = "🌲 どうぶつすごろく 🌲"
            textSize = 32f
            setTextColor(Color.parseColor("#33691E"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "ぜん${stages.size}ステージ！\nもりのなかまと ゴールをめざそう！"
            textSize = 16f
            setTextColor(Color.parseColor("#558B2F"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(32))
        })
        root.addView(Button(this).apply {
            text = "はじめる"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#FF9800"))
            setPadding(dp(48), dp(16), dp(48), dp(16))
            setOnClickListener { showCharaSelect() }
        })

        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        subRow.addView(Button(this).apply {
            text = "📖 ずかん"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#4CAF50"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { editorScreens.showZukan() }
        })
        subRow.addView(Button(this).apply {
            text = "✏️ エディタ"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#5E35B1"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { editorScreens.showEditorTop() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(12) })
        root.addView(Button(this).apply {
            text = "📶 つうしん たいせん"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#1E88E5"))
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { showNetLobby() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        root.addView(subRow)

        setContentView(root)
    }

    /**
     * つうしん対戦のロビー。v6.0-A では「2台がつながること」の確認まで。
     * キャラ選択と対戦本体は v6.1-A で載せる。
     */
    private fun showNetLobby() {
        handler.removeCallbacksAndMessages(null)
        NetLobby(
            act = this,
            appVersion = appVersionName(),
            onBack = { showTitle() }
        ).showTop()
    }

    /** 自分のバージョン名。つないだ相手と つき合わせるのに使う */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "バージョン名を取れませんでした", e)
        "?"
    }

    /** エディタ・図鑑の画面。データを編集したら読み直してタイトルに戻す */
    private val editorScreens by lazy {
        EditorScreens(
            act = this,
            onBack = { showTitle() },
            onDataChanged = { loadGameData() }
        )
    }

    // ---------------- キャラ選択画面 ----------------
    private fun showCharaSelect() {
        handler.removeCallbacksAndMessages(null)
        showCharaGrid(
            title = "1P の キャラクターを えらぼう",
            options = charas,
            step = 1,
            sub = "きみが うごかす どうぶつだよ",
            onBack = { showTitle() }
        ) { c -> showModeSelect(c) }
    }

    /**
     * キャラ選択のグリッド。1Pと2Pの両方で使う共通処理。
     * @param step ステップ表示に使う番号（1P選択=1、2P選択=2）
     * @param onBack もどるボタンの遷移先。null なら表示しない
     */
    private fun showCharaGrid(
        title: String,
        options: List<Chara>,
        step: Int = 1,
        sub: String? = null,
        onBack: (() -> Unit)? = null,
        onPick: (Chara) -> Unit
    ) {
        val root = baseColumn().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(stepBar(step))
        root.addView(titleText(title))
        root.addView(subText(sub ?: "タップして えらんでね"))

        // キャラが増えても崩れないよう、列数・行数を匹数から決める
        val cols = if (options.size > 6) 3 else 2
        val grid = GridLayout(this).apply {
            columnCount = cols
            rowCount = (options.size + cols - 1) / cols
        }
        // 列が増えるほどセルは小さくなるので、余白も控えめにする
        val gap = if (cols == 3) dp(20) else dp(40)
        val cellSize = resources.displayMetrics.widthPixels / cols - gap
        for (c in options) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            cell.addView(ImageButton(this).apply {
                setImageResource(c.resId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = roundedBg(Color.WHITE, Color.parseColor("#A5D6A7"))
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
                setOnClickListener { onPick(c) }
            })
            cell.addView(TextView(this).apply {
                text = c.name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#33691E"))
                typeface = Typeface.DEFAULT_BOLD
            })
            grid.addView(cell)
        }
        root.addView(grid)
        onBack?.let { root.addView(backButtonView(it)) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------- あそびかた選択（ステップ2/3）----------------

    /**
     * 「ひとりで」か「ふたりで」かだけを聞く画面。
     * 以前は 合計人数×人間の人数 の7通りを1画面に並べていて分かりにくかったため、
     * 「だれと」→「なんびき」の2段階に分けている。
     */
    private fun showModeSelect(p1: Chara) {
        val root = baseColumn()
        root.addView(stepBar(2))
        root.addView(titleText("だれと あそぶ？"))
        root.addView(subText("1P は ${p1.name}"))

        root.addView(
            modeCard(
                icons = listOf(p1.resId),
                cpuCount = 3,
                title = "ひとりで あそぶ",
                desc = "あいてを コンピュータが うごかすよ",
                color = Color.parseColor("#7CB342")
            ) { showTotalSelect(p1, null) }
        )
        root.addView(
            modeCard(
                icons = listOf(p1.resId),
                cpuCount = 0,
                secondSlot = true,
                title = "ふたりで あそぶ",
                desc = "1だいの スマホを こうたいで つかうよ",
                color = Color.parseColor("#00897B")
            ) { showSecondPlayerSelect(p1) }
        )

        root.addView(backButtonView { showCharaSelect() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 2人目の人間プレイヤーのキャラを選ぶ */
    private fun showSecondPlayerSelect(p1: Chara) {
        showCharaGrid(
            title = "2P の キャラクターを えらぼう",
            options = charas.filter { it != p1 },
            step = 2,
            sub = "ふたりめの ひとが うごかす どうぶつ",
            onBack = { showModeSelect(p1) }
        ) { p2 -> showTotalSelect(p1, p2) }
    }

    // ---------------- 何匹で遊ぶか（ステップ3/3）----------------

    /**
     * 合計何匹で遊ぶかを選ぶ。
     * 実際のキャラ画像を並べて「誰が出るか」を目で見て分かるようにしている。
     */
    private fun showTotalSelect(p1: Chara, p2: Chara?) {
        val humans = if (p2 == null) 1 else 2
        val root = baseColumn()
        root.addView(stepBar(3))
        root.addView(titleText("なんびきで あそぶ？"))
        root.addView(subText(
            if (p2 == null) "きみは ${p1.name}"
            else "1P ${p1.name}　2P ${p2.name}"
        ))

        val others = charas.filter { it != p1 && it != p2 }
        val maxTotal = (humans + others.size).coerceAtMost(4)
        for (total in humans..maxTotal) {
            val cpu = total - humans
            val icons = ArrayList<Int>()
            icons.add(p1.resId)
            p2?.let { icons.add(it.resId) }
            // キャラ数が少ないセットでも落ちないよう範囲を確認する
            for (i in 0 until cpu) others.getOrNull(i)?.let { icons.add(it.resId) }

            val label = when {
                cpu == 0 && humans == 1 -> "ひとりだけ"
                cpu == 0 -> "ふたりだけ"
                else -> "CPU $cpu ひき"
            }
            root.addView(
                lineupCard(total, humans, icons, label,
                    if (cpu == 0) Color.parseColor("#00897B") else Color.parseColor("#7CB342")
                ) { buildPlayers(p1, p2, total) }
            )
        }

        root.addView(backButtonView {
            if (p2 == null) showModeSelect(p1) else showSecondPlayerSelect(p1)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------- 選択画面のパーツ ----------------

    private fun baseColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#E8F5E9"))
        setPadding(dp(18), dp(16), dp(18), dp(20))
    }

    /** 今どのステップにいるかを点で示す */
    private fun stepBar(current: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        val names = listOf("キャラ", "あそびかた", "にんずう")
        for (i in 1..3) {
            row.addView(TextView(this).apply {
                text = if (i == current) "● ${names[i - 1]}" else "○ ${names[i - 1]}"
                textSize = 12f
                setTextColor(
                    if (i == current) Color.parseColor("#33691E") else Color.parseColor("#A5B5A0")
                )
                typeface = if (i == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(6), 0, dp(6), 0)
            })
        }
        return row
    }

    private fun titleText(t: String) = TextView(this).apply {
        text = t
        textSize = 23f
        setTextColor(Color.parseColor("#33691E"))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun subText(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#558B2F"))
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(14))
    }

    private fun backButtonView(onClick: () -> Unit) = Button(this).apply {
        text = "◀ もどる"
        textSize = 14f
        setTextColor(Color.WHITE)
        background = roundedBg(Color.parseColor("#90A4AE"))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(20)
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    /** キャラ画像を1つ置く（?マークのプレースホルダにもできる） */
    private fun charaIcon(resId: Int?, size: Int): View =
        if (resId != null) ImageView(this).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
        } else TextView(this).apply {
            text = "？"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    /** 「ひとりで／ふたりで」のカード */
    private fun modeCard(
        icons: List<Int>,
        cpuCount: Int,
        secondSlot: Boolean = false,
        title: String,
        desc: String,
        color: Int,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(color)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Color.parseColor("#E8F5E9"))
            setPadding(0, dp(2), 0, dp(8))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (r in icons) row.addView(charaIcon(r, dp(52)))
        if (secondSlot) {
            row.addView(TextView(this).apply {
                text = "＋"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(dp(4), 0, dp(4), 0)
            })
            row.addView(charaIcon(null, dp(52)))
        }
        if (cpuCount > 0) {
            row.addView(TextView(this).apply {
                text = "＋ CPU"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(6), 0, 0, 0)
            })
        }
        card.addView(row)
        return card
    }

    /** 「N匹であそぶ」のカード。出てくる animals を実際の絵で見せる */
    private fun lineupCard(
        total: Int,
        humans: Int,
        icons: List<Int>,
        label: String,
        color: Int,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = "${total}ひき　（$label）"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        for ((i, r) in icons.withIndex()) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(6), 0)
            }
            col.addView(charaIcon(r, dp(48)))
            col.addView(TextView(this).apply {
                text = if (i < humans) "${i + 1}P" else "CPU"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = if (i < humans) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            })
            row.addView(col)
        }
        card.addView(row)
        return card
    }

    /** プレイヤー構成を作ってゲーム開始 */
    private fun buildPlayers(p1: Chara, p2: Chara?, total: Int) {
        players.clear()
        players.add(Player(p1, isHuman = true, humanNo = 1, money = jobsData.startMoney))
        if (p2 != null) players.add(Player(p2, isHuman = true, humanNo = 2, money = jobsData.startMoney))
        val used = players.map { it.chara }.toSet()
        val rest = charas.filter { it !in used }
        var i = 0
        while (players.size < total && i < rest.size) {
            players.add(Player(rest[i], isHuman = false, money = jobsData.startMoney))
            i++
        }
        turn = 0
        stageIndex = 0
        applyStage()
        showGame()
    }

    // ---------------- ゲーム画面 ----------------
    private fun showGame() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
        }

        boardView = BoardView(this, players, stage.bgRes, caveBgRes)
        root.addView(boardView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        infoRow.addView(TextView(this).apply {
            text = "ステージ${stageIndex + 1}/${stages.size}「${stage.name}」\n" +
                if (mode == GameMode.SCHOOL)
                    "🟢いいこと 🟣わるいこと 🔵えらぶ ⚪おやすみ\n" +
                        "🔴じゅけん（${statNames[0]}しだい） 🩷こくはく（${statNames[2]}しだい）"
                else
                    "🟢いいこと 🟣わるいこと 🔵えらぶ ⚪おやすみ\n🩵しごと 🟡おみせ 🟠ワープ 🩷けっこん・出産"
            textSize = 11f
            setTextColor(Color.parseColor("#558B2F"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btnCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        zoomButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#5E35B1"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                // 表示中のプレイヤーを中心に保ったまま倍率だけ変える
                boardView.cycleZoom(players.getOrNull(turn)?.position ?: 0)
                updateZoomLabel()
            }
        }
        btnCol.addView(zoomButton)
        speedButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                Speed.fast = !Speed.fast
                updateSpeedLabel()
            }
        }
        btnCol.addView(speedButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        updateSpeedLabel()
        infoRow.addView(btnCol)
        root.addView(infoRow)
        updateZoomLabel()

        statusText = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#33691E"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        root.addView(statusText)

        val skillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        manpukuSkillButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#EF6C00"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            text = (if (mode == GameMode.SCHOOL) "📖" else "🍖") +
                "${statNames[0]}${Skill.MANPUKU_COST}→+${Skill.MANPUKU_BONUS}"
            setOnClickListener { useManpukuSkill() }
        }
        skillRow.addView(manpukuSkillButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { rightMargin = dp(4) })
        juujitsuSkillButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#6A1B9A"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            text = "✨${statNames[1]}${Skill.JUUJITSU_COST}→みんな-${Skill.JUUJITSU_PUSH}"
            setOnClickListener { useJuujitsuSkill() }
        }
        skillRow.addView(juujitsuSkillButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = dp(4) })
        itemButton = Button(this).apply {
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#00838F"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            text = "🎒もちもの"
            setOnClickListener { showItemDialog() }
        }
        skillRow.addView(itemButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = dp(4) })
        root.addView(skillRow)

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        rouletteView = RouletteView(this) { result -> onRouletteResult(result) }
        controlRow.addView(rouletteView, LinearLayout.LayoutParams(0, dp(200), 1.3f))

        val buttonCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)
        }
        startButton = Button(this).apply {
            text = "スタート！"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#FF9800"))
            setPadding(dp(8), dp(20), dp(8), dp(20))
            setOnClickListener { onStartPressed() }
        }
        buttonCol.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        buttonCol.addView(Button(this).apply {
            text = "ステータス"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#4CAF50"))
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { showStatusDialog() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        controlRow.addView(buttonCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { gravity = Gravity.CENTER_VERTICAL })
        root.addView(controlRow)

        statsBar = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#33691E"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        root.addView(statsBar)

        setContentView(root)
        updateStatsBar()
        startTurn()
    }

    /** 表示名。人間が2人いるときは 1P/2P を付ける */
    private fun who(p: Player): String = when {
        !p.isHuman -> "${p.chara.name}（CPU）"
        players.count { it.isHuman } >= 2 -> "${p.chara.name}（${p.humanNo}P）"
        else -> "${p.chara.name}（きみ）"
    }

    private fun updateSpeedLabel() {
        speedButton.text = if (Speed.fast) "はやさ: はやい⚡" else "はやさ: ふつう"
    }

    /** ズームボタンには「今どれだけ見えているか」を出す */
    private fun updateZoomLabel() {
        if (!::zoomButton.isInitialized) return
        zoomButton.text = "🔍 ${Zoom.label}ぶん" + if (Zoom.isMax) "（さいだい）" else ""
    }

    private fun updateStatsBar() {
        val me = players.getOrNull(turn) ?: return
        val bonus = StringBuilder()
        if (me.hasChild) bonus.append("　👶x${Skill.CHILD_MULTIPLIER}")
        else if (me.married) bonus.append("　💍x${Skill.MARRIED_MULTIPLIER}")
        if (me.yuujou >= Skill.YUUJOU_THRESHOLD) bonus.append("　🤝+${Skill.YUUJOU_BONUS}")
        if (me.boostNext) {
            bonus.append((if (mode == GameMode.SCHOOL) "　📖+" else "　🍖+") + Skill.MANPUKU_BONUS)
        }
        val sd = me.skills.sumOf { skillOf(it)?.dice ?: 0 }
        if (sd > 0) bonus.append("　💪+$sd")
        val tag = if (me.isHuman && players.count { it.isHuman } >= 2) "${me.humanNo}P " else ""
        // 学校編は おかね・しごとを使わないので、そのぶん ごうかく・こいびとを出す
        val extra = if (mode == GameMode.SCHOOL) {
            (if (me.passedExam) "　🌸" else "") + (if (me.hasLover) "　💗" else "")
        } else {
            "　💰${me.money}${jobOf(me.jobId)?.icon ?: ""}"
        }
        statsBar.text = "$tag${statNames[0]}${me.manpuku} ${statNames[1]}${me.juujitsu} " +
            "${statNames[2]}${me.yuujou}$extra$bonus"
    }

    private fun updateSkillButtons() {
        if (!::manpukuSkillButton.isInitialized) return
        val p = players.getOrNull(turn)
        val myTurn = p != null && p.isHuman && !rouletteView.locked
        manpukuSkillButton.isEnabled = myTurn && p!!.manpuku >= Skill.MANPUKU_COST && !p.boostNext
        juujitsuSkillButton.isEnabled = myTurn && p!!.juujitsu >= Skill.JUUJITSU_COST && canPushOthers(p)
        manpukuSkillButton.alpha = if (manpukuSkillButton.isEnabled) 1f else 0.4f
        juujitsuSkillButton.alpha = if (juujitsuSkillButton.isEnabled) 1f else 0.4f
        if (::itemButton.isInitialized) {
            val n = p?.items?.size ?: 0
            itemButton.text = if (n > 0) "🎒もちもの×$n" else "🎒もちもの"
            itemButton.isEnabled = myTurn && n > 0
            itemButton.alpha = if (itemButton.isEnabled) 1f else 0.4f
        }
    }

    /**
     * もちものを つかう画面。自分の番のあいだ、ルーレットを回す前に つかえる。
     * 「あいて」に つかうものは、同じ世界の いちばん さきにいる 相手が ねらい。
     */
    private fun showItemDialog() {
        val p = players.getOrNull(turn) ?: return
        if (!p.isHuman || rouletteView.locked || p.items.isEmpty()) return
        val defs = p.items.mapNotNull { itemOf(it) }
        if (defs.isEmpty()) return
        val labels = defs.map { "${it.icon} ${it.name}
　${it.desc}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎒 どれを つかう？")
            .setItems(labels) { _, which -> useItem(p, defs[which]) }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 同じ世界で いちばん さきにいる 相手。いなければ null */
    private fun frontRival(p: Player): Player? =
        players.filter { it !== p && it.inCave == p.inCave }.maxByOrNull { it.position }

    private fun useItem(p: Player, def: GameData.ItemDef) {
        val rival = if (def.target == "other") frontRival(p) else null
        if (def.target == "other" && rival == null) {
            Toast.makeText(this, "いま つかえる あいてが いません", Toast.LENGTH_SHORT).show()
            return
        }
        p.items.remove(def.id)
        p.itemUsed++
        val sb = StringBuilder("${def.icon} ${def.name} を つかった！
")
        if (def.useMessage.isNotBlank()) sb.append("${def.useMessage}
")
        if (rival != null) {
            if (def.swap) {
                val tmp = p.position
                p.position = rival.position
                rival.position = tmp
                sb.append("${who(p)} と ${who(rival)} が いれかわった！
")
            }
            if (def.dMove < 0) {
                rival.position = (rival.position + def.dMove).coerceAtLeast(0)
                sb.append("${who(rival)} が ${-def.dMove}マス もどった！
")
            }
        }
        if (def.dManpuku != 0 || def.dJuujitsu != 0 || def.dYuujou != 0) {
            p.manpuku = (p.manpuku + gain(p, def.dManpuku)).coerceIn(0, 999)
            p.juujitsu = (p.juujitsu + gain(p, def.dJuujitsu)).coerceIn(0, 999)
            p.yuujou = (p.yuujou + gain(p, def.dYuujou)).coerceIn(0, 999)
        }
        if (def.boost > 0) {
            p.itemBoost += def.boost
            sb.append("つぎの ルーレットが +${def.boost}！
")
        }
        if (def.guard) {
            p.restGuard = true
            sb.append("つぎの おやすみを 1かい ふせげる！
")
        }
        statusText.text = sb.toString().trimEnd()
        updateStatsBar()
        updateSkillButtons()
        boardView.invalidate()
    }

    /**
     * もちものを わたす。いっぱいなら もらえない（どれを捨てるかは聞かない。
     * テンポを止めないほうが小さい子には遊びやすい）。
     */
    private fun giveItem(p: Player, id: String): String {
        if (id.isBlank()) return ""
        val def = itemOf(id) ?: return ""
        if (p.items.size >= itemsData.maxHold) {
            return "\n🎒 もちものが いっぱいで ${def.icon}${def.name} を もらえなかった…"
        }
        p.items.add(id)
        return "\n🎒 ${def.icon}${def.name} を てにいれた！"
    }

    private fun showStatusDialog() {
        val sb = StringBuilder("ステージ${stageIndex + 1}/${stages.size}「${stage.name}」\n\n")
        for (p in players.sortedByDescending { scoreOf(it) }) {
            sb.append("${who(p)}　ごうけい ${p.total}\n")
            if (p.items.isNotEmpty()) {
                sb.append("  🎒 ")
                sb.append(p.items.mapNotNull { itemOf(it) }.joinToString("、") { "${it.icon}${it.name}" })
                sb.append("\n")
            }
            val where = if (p.inCave) "どうくつ" else "ほんせん"
            sb.append("  $where ${p.position} / ${Board.goal(p.inCave)}\n")
            sb.append("  ${statNames[0]} ${p.manpuku}　${statNames[1]} ${p.juujitsu}" +
                "　${statNames[2]} ${p.yuujou}\n")
            if (mode == GameMode.SCHOOL) {
                val st = ArrayList<String>()
                if (p.passedExam) st.add("🌸じゅけん ごうかく")
                if (p.hasLover) st.add("💗こいびとが いる")
                if (st.isNotEmpty()) sb.append("  ${st.joinToString("／")}\n")
            } else {
                val j = jobOf(p.jobId)
                sb.append("  おかね ${p.money}")
                if (j != null) sb.append("　しごと ${j.icon}${j.name}（きゅうりょう ${j.salary}）")
                sb.append("\n")
                if (p.skills.isNotEmpty()) {
                    val names = p.skills.mapNotNull { skillOf(it) }
                        .joinToString("、") { "${it.icon}${it.name}" }
                    sb.append("  スキル $names\n")
                }
            }
            val marks = ArrayList<String>()
            if (p.hasChild) marks.add("👶こども あり（イベント${Skill.CHILD_MULTIPLIER}倍）")
            else if (p.married) marks.add("💍けっこん済み（イベント${Skill.MARRIED_MULTIPLIER}倍）")
            if (p.yuujou >= Skill.YUUJOU_THRESHOLD) marks.add("友情ボーナス +${Skill.YUUJOU_BONUS}")
            if (p.boostNext) marks.add("パワーアップ中 +${Skill.MANPUKU_BONUS}")
            if (marks.isNotEmpty()) sb.append("  ${marks.joinToString("／")}\n")
            sb.append("\n")
        }
        AlertDialog.Builder(this)
            .setTitle("ステータス")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("とじる", null)
            .show()
    }

    // ---------------- スキル ----------------
    private fun useManpukuSkill() {
        val p = players[turn]
        if (!p.isHuman || p.manpuku < Skill.MANPUKU_COST || p.boostNext) return
        p.manpuku -= Skill.MANPUKU_COST
        p.boostNext = true
        statusText.text = (if (mode == GameMode.SCHOOL) "📖" else "🍖") +
            " パワーアップ！ つぎのルーレットに +${Skill.MANPUKU_BONUS}"
        updateStatsBar()
        updateSkillButtons()
    }

    private fun useJuujitsuSkill() {
        val p = players[turn]
        if (!p.isHuman || p.juujitsu < Skill.JUUJITSU_COST || !canPushOthers(p)) return
        p.juujitsu -= Skill.JUUJITSU_COST
        val n = pushOthers(p)
        statusText.text = "✨ ${n}ひきを ${Skill.JUUJITSU_PUSH}マス もどした！"
        boardView.invalidate()
        updateStatsBar()
        updateSkillButtons()
    }

    /**
     * 自分と同じ世界（本線/洞窟）にいる相手だけを後ろに下げる。
     * 世界をまたいで作用すると、洞窟にいるプレイヤーが本線からの妨害で
     * 洞窟のスタートまで押し戻されてしまうため必ず inCave で絞る。
     */
    private fun pushOthers(p: Player): Int {
        var n = 0
        for (other in players) {
            if (other !== p && other.inCave == p.inCave) {
                other.position = (other.position - Skill.JUUJITSU_PUSH).coerceAtLeast(0)
                n++
            }
        }
        return n
    }

    /** 同じ世界に相手がいるときだけスキルを使える */
    private fun canPushOthers(p: Player): Boolean =
        players.any { it !== p && it.inCave == p.inCave }

    private fun cpuUseSkills(p: Player) {
        if (p.juujitsu >= Skill.JUUJITSU_COST && canPushOthers(p) && Random.nextFloat() < 0.4f) {
            p.juujitsu -= Skill.JUUJITSU_COST
            val n = pushOthers(p)
            statusText.text = "✨ ${who(p)} が ${n}ひきを ${Skill.JUUJITSU_PUSH}マス もどした！"
            boardView.invalidate()
            updateStatsBar()
        }
        if (p.manpuku >= Skill.MANPUKU_COST && !p.boostNext && Random.nextFloat() < 0.5f) {
            p.manpuku -= Skill.MANPUKU_COST
            p.boostNext = true
            statusText.text = "🍖 ${who(p)} が パワーアップ！"
        }
    }

    // ---------------- 手番 ----------------
    private fun onStartPressed() {
        val p = players[turn]
        if (!p.isHuman || rouletteView.locked) return
        startButton.isEnabled = false
        boardView.panEnabled = false
        rouletteView.locked = true
        updateSkillButtons()
        boardView.focusCell(p.position, slow = false)
        handler.postDelayed({ rouletteView.autoSpin() }, 380)
    }

    private fun startTurn() {
        val p = players[turn]
        // 1回休み: フラグを消して この手番は とばす（全員が休んでも消えるので止まらない）
        if (p.skipNext && p.restGuard) {
            p.skipNext = false
            p.restGuard = false
            statusText.text = "⏰ ${who(p)} は めざましで とびおきた！"
        }
        if (p.skipNext) {
            p.skipNext = false
            p.restCount++
            boardView.turnIndex = turn
            boardView.world = p.inCave
            boardView.snapToCell(p.position)
            statusText.text = "💤 ${who(p)} は おやすみ…"
            rouletteView.locked = true
            startButton.isEnabled = false
            boardView.panEnabled = false
            updateSkillButtons()
            handler.postDelayed({ nextTurn() }, Speed.eventWaitMs)
            return
        }
        val worldChanged = boardView.world != p.inCave
        boardView.turnIndex = turn
        boardView.world = p.inCave
        // 世界が切り替わった直後はカメラ位置に意味がないので、アニメせず即座に合わせる
        if (worldChanged) boardView.snapToCell(p.position)
        else boardView.focusCell(p.position, slow = true)
        if (p.isHuman) {
            statusText.text = "${who(p)}のばん！ スタートを おしてね"
            rouletteView.locked = false
            startButton.isEnabled = true
            boardView.panEnabled = true
            updateSkillButtons()
        } else {
            statusText.text = "${who(p)}のばん…"
            rouletteView.locked = true
            startButton.isEnabled = false
            boardView.panEnabled = false
            updateSkillButtons()
            cpuUseSkills(p)
            handler.postDelayed({ rouletteView.autoSpin() }, Speed.cpuWaitMs)
        }
    }

    private fun onRouletteResult(raw: Int) {
        val p = players[turn]
        var steps = raw
        val extras = StringBuilder()
        if (p.boostNext) {
            steps += Skill.MANPUKU_BONUS
            p.boostNext = false
            extras.append(" 🍖+${Skill.MANPUKU_BONUS}")
        }
        if (p.itemBoost > 0) {
            steps += p.itemBoost
            extras.append(" 🎒+${p.itemBoost}")
            p.itemBoost = 0
        }
        if (p.yuujou >= Skill.YUUJOU_THRESHOLD) {
            steps += Skill.YUUJOU_BONUS
            extras.append(" 🤝+${Skill.YUUJOU_BONUS}")
        }
        // 取得スキルによる常時ボーナス（例: たいりょく → +1）
        val skillDice = p.skills.sumOf { skillOf(it)?.dice ?: 0 }
        if (skillDice > 0) {
            steps += skillDice
            extras.append(" 💪+$skillDice")
        }
        statusText.text = if (extras.isEmpty()) "${p.chara.name} は「$steps」！"
                          else "${p.chara.name} は「$raw」$extras → $steps マス！"
        updateStatsBar()
        movePiece(p, steps, fromEvent = false)
    }

    private fun movePiece(p: Player, steps: Int, fromEvent: Boolean) {
        rouletteView.locked = true
        startButton.isEnabled = false
        boardView.panEnabled = false
        updateSkillButtons()
        val delta = if (steps > 0) 1 else -1
        var remaining = abs(steps)
        // 洞窟では入口(0)まで戻さない。0はスタート地点なので「振り出しに戻った」ように見えてしまう
        val backLimit = if (p.inCave) 1 else 0
        val stepRunnable = object : Runnable {
            override fun run() {
                val canMove = if (delta > 0) p.position < Board.goal(p.inCave) else p.position > backLimit
                if (remaining > 0 && canMove) {
                    p.position += delta
                    boardView.focusCell(p.position, slow = false)
                    boardView.invalidate()
                    remaining--
                    handler.postDelayed(this, Speed.stepMs)
                } else {
                    onLanded(p, fromEvent)
                }
            }
        }
        handler.postDelayed(stepRunnable, Speed.stepMs)
    }

    private fun nextTurn() {
        turn = (turn + 1) % players.size
        startTurn()
    }

    /**
     * ゴール到達時に、まだ受けていないステージ内の「ちょうせん」を返す。
     * 出目によってマスを飛び越しても受験を必ず通すための救済。
     */
    private fun pendingChallenge(p: Player): GameEvent? =
        stage.events.values.firstOrNull {
            it.kind == EventKind.EXAM && !p.passedExam && p.examTries < Skill.EXAM_MAX_TRIES
        }

    /** そのイベントが今のプレイヤーに発生するか */
    private fun eventAvailable(p: Player, ev: GameEvent): Boolean = when (ev.kind) {
        EventKind.NORMAL -> true
        EventKind.WEDDING -> !p.married
        EventKind.BIRTH -> p.married && !p.hasChild
        EventKind.JOB -> jobsData.jobs.size > 1        // 選べる職業があるときだけ
        EventKind.SHOP -> jobsData.skills.isNotEmpty() // 売るものがあるときだけ
        EventKind.EXAM -> !p.passedExam && p.examTries < Skill.EXAM_MAX_TRIES
        EventKind.LOVE -> !p.hasLover   // こいびとが いないときだけ
    }

    private fun onLanded(p: Player, fromEvent: Boolean) {
        if (p.position >= Board.goal(p.inCave)) {
            if (p.inCave) {
                exitCave(p)
            } else {
                // ゴール手前の「ちょうせん」は飛び越されやすいので、
                // ゴールに着いた時点で未挑戦なら必ず受けさせる（受験のとりこぼし防止）
                val pending = pendingChallenge(p)
                if (pending != null && !fromEvent) showChallenge(p, pending)
                else stageClear(p)
            }
            return
        }
        // 分岐マス（本線のみ）: 洞窟ルートへ
        if (!p.inCave && p.position == stage.branchCell && !fromEvent) {
            enterCave(p)
            return
        }
        val ev = if (p.inCave) caveEvents[p.position] else stage.events[p.position]
        if (ev != null && !fromEvent && eventAvailable(p, ev)) {
            // 図鑑に記録（人間が出会ったものだけ。CPUの遭遇では埋まらない）
            if (p.isHuman) {
                val where = if (p.inCave) Zukan.CAVE else stage.name
                Zukan.record(this, where, p.position)
            }
            when (ev.kind) {
                EventKind.JOB -> showJobSelect(p, ev)
                EventKind.SHOP -> showShop(p, ev)
                EventKind.EXAM, EventKind.LOVE -> showChallenge(p, ev)
                else -> showEvent(p, ev)
            }
            return
        }
        nextTurn()
    }

    // ---------------- 洞窟ルート ----------------
    private fun enterCave(p: Player) {
        if (p.inCave) return   // 二重入場の保険（本来 onLanded 側で弾かれる）
        p.caveReturn = (p.position + CAVE_SKIP).coerceAtMost(Board.goal(false))
        showPhotoDialog(
            p, caveBgRes,
            "ほらあなを みつけた！\nくらい どうくつへ はいってみよう。\n\nぬけると ${p.caveReturn}マスめに でられるよ（ぜん${Board.CAVE_COUNT}マス）"
        ) {
            p.inCave = true
            p.position = 0
            boardView.world = true
            boardView.snapToCell(0)
            nextTurn()
        }
    }

    private fun exitCave(p: Player) {
        showPhotoDialog(
            p, caveBgRes,
            "どうくつを ぬけた！\nあかるい ばしょに でたよ。\n\n${p.caveReturn}マスめから さいかいだ！"
        ) {
            p.inCave = false
            p.position = p.caveReturn
            boardView.world = false
            boardView.snapToCell(p.position)
            onLanded(p, fromEvent = false)   // 復帰先のマスは通常どおり発動する
        }
    }

    /** 写真＋家族＋メッセージのシンプルなダイアログ（洞窟の出入りなど） */
    private fun showPhotoDialog(p: Player, bgRes: Int, message: String, onOk: () -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        row.addView(ImageView(this).apply { setImageResource(p.chara.resId); rotation = 3f },
            LinearLayout.LayoutParams(dp(100), dp(100)).apply { bottomMargin = dp(6) })
        if (p.married) row.addView(ImageView(this).apply {
            setImageResource(p.chara.partnerRes); rotation = -4f
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(6) })
        if (p.hasChild) row.addView(ImageView(this).apply {
            setImageResource(p.chara.childRes); rotation = -4f
        }, LinearLayout.LayoutParams(dp(66), dp(66)).apply { bottomMargin = dp(6) })
        frame.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        content.addView(frame)
        content.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.parseColor("#263238"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.WHITE, Color.parseColor("#4527A0"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> onOk() }
            .show()
    }

    // ---------------- ステージクリア / 最終結果 ----------------
    private fun stageClear(winner: Player) {
        winner.stageWins++
        winner.juujitsu = (winner.juujitsu + Skill.CLEAR_BONUS).coerceIn(0, 999)
        winner.yuujou = (winner.yuujou + Skill.CLEAR_BONUS).coerceIn(0, 999)
        // ステージ終了時、全員が給料をもらい職業のステータス補正を受ける
        val payLines = StringBuilder()
        for (p in players) {
            val j = jobOf(p.jobId) ?: continue
            p.money += j.salary
            p.manpuku = (p.manpuku + j.dManpuku).coerceIn(0, 999)
            p.juujitsu = (p.juujitsu + j.dJuujitsu).coerceIn(0, 999)
            p.yuujou = (p.yuujou + j.dYuujou).coerceIn(0, 999)
            payLines.append("${j.icon}${who(p)} +${j.salary}\n")
        }
        updateStatsBar()
        if (stageIndex >= stages.size - 1) {
            showFinalResult(winner)
            return
        }
        val next = stages[stageIndex + 1]
        AlertDialog.Builder(this)
            .setTitle("🎉 ステージ${stageIndex + 1} クリア！")
            .setMessage(
                "${who(winner)} が 1ばんに ゴール！（1ちゃく ${winner.stageWins}かいめ）\n" +
                "1ちゃくボーナス 充実+${Skill.CLEAR_BONUS}　友情+${Skill.CLEAR_BONUS}\n\n" +
                (if (mode == GameMode.SCHOOL) "" else "【 きゅうりょう 】\n$payLines\n") +
                "つぎは「${next.name}」！\nみんなで すすもう。\n" +
                (if (mode == GameMode.SCHOOL) "（ステータスは そのまま ひきつぐよ）"
                 else "（ステータス・けっこん・こども・しごとは そのまま）")
            )
            .setCancelable(false)
            .setPositiveButton("つぎのステージへ") { _, _ ->
                stageIndex++
                players.forEach {
                    it.position = 0; it.inCave = false; it.caveReturn = 0
                    it.skipNext = false
                    it.passedExam = false; it.examTries = 0   // 受験はステージごと
                }
                turn = players.indexOf(winner).coerceAtLeast(0)
                applyStage()
                showGame()
            }
            .show()
    }

    /**
     * 最終結果。だれが勝ったかを 先頭に大きく出す。
     * 勝敗は スコア → ステージ1着の かず の順で決め、
     * どちらも同じなら ひきわけ にする（ゴール順では決めない）。
     */
    private fun showFinalResult(winner: Player) {
        val ranking = players.sortedWith(
            compareByDescending<Player> { scoreOf(it) }.thenByDescending { it.stageWins }
        )
        val top = ranking[0]
        val tied = players.filter {
            scoreOf(it) == scoreOf(top) && it.stageWins == top.stageWins
        }
        val sb = StringBuilder()
        if (tied.size >= 2) {
            sb.append("🤝 ひきわけ！\n")
            sb.append(tied.joinToString(" と ") { who(it) })
            sb.append(" が おなじ てんすう！\n\n")
        } else {
            sb.append("🏆 ${who(top)} の かち！\n")
            val second = ranking.getOrNull(1)
            if (second != null) {
                sb.append("2いに ${scoreOf(top) - scoreOf(second)}てん さを つけた！\n")
            }
            sb.append("\n")
        }
        sb.append("さいごのステージで 1ばんに ゴールしたのは ${who(winner)}\n\n")
        // 人間どうしの対戦は 見くらべやすいように 1対1でも出す
        val humans = players.filter { it.isHuman }
        if (humans.size == 2) {
            val a = humans[0]
            val b = humans[1]
            val diff = abs(scoreOf(a) - scoreOf(b))
            sb.append("【 ${who(a)}　vs　${who(b)} 】\n")
            sb.append("てんすう　${scoreOf(a)} - ${scoreOf(b)}（さ ${diff}てん）\n")
            sb.append("1ちゃく　　${a.stageWins} - ${b.stageWins}\n")
            sb.append("${statNames[0]}　${a.manpuku} - ${b.manpuku}\n")
            sb.append("${statNames[1]}　${a.juujitsu} - ${b.juujitsu}\n")
            sb.append("${statNames[2]}　${a.yuujou} - ${b.yuujou}\n\n")
        }
        // モードによってスコアの内訳がちがうので、説明も出し分ける
        sb.append(
            if (mode == GameMode.SCHOOL)
                "【 スコア = ${statNames[0]} + ${statNames[1]} + ${statNames[2]}\n" +
                    "　　　　+ ごうかく + こいびと 】\n"
            else
                "【 スコア = ステータス + おかね÷10 + きゅうりょう÷10\n" +
                    "　　　　+ スキル×10 + かぞく 】\n"
        )
        for ((i, p) in ranking.withIndex()) {
            val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "　" }
            val marks = StringBuilder()
            if (mode == GameMode.SCHOOL) {
                if (p.passedExam) marks.append(" 🌸")
                if (p.hasLover) marks.append(" 💗")
            } else {
                if (p.hasChild) marks.append(" 👶") else if (p.married) marks.append(" 💍")
                jobOf(p.jobId)?.let { marks.append(it.icon) }
            }
            val winMark = if (p.stageWins > 0) " 🚩${p.stageWins}" else ""
            sb.append("$medal ${who(p)}${marks}${winMark}　${scoreOf(p)}てん\n")
            sb.append("　　${statNames[0]}${p.manpuku} ${statNames[1]}${p.juujitsu}" +
                " ${statNames[2]}${p.yuujou}")
            if (mode != GameMode.SCHOOL) {
                sb.append(" 💰${p.money} 💪${p.skills.size}")
            }
            sb.append("\n")
        }
        val awards = memoryAwards()
        if (awards.isNotEmpty()) {
            sb.append("\n【 おもいでしょう 】\n")
            for (a in awards) sb.append("$a\n")
        }
        val title = if (mode == GameMode.SCHOOL) "🎓 そつぎょう おめでとう！" else "🏆 ぜんステージ クリア！"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setCancelable(false)
            .setPositiveButton("もういちど") { _, _ ->
                players.forEach {
                    it.position = 0; it.manpuku = 0; it.juujitsu = 0; it.yuujou = 0
                    it.boostNext = false; it.married = false; it.hasChild = false
                    it.inCave = false; it.caveReturn = 0; it.skipNext = false
                    it.money = jobsData.startMoney; it.jobId = "none"; it.skills.clear()
                    it.passedExam = false; it.hasLover = false; it.examTries = 0
                    it.stageWins = 0
                    it.items.clear(); it.itemBoost = 0; it.restGuard = false
                    it.backSteps = 0; it.restCount = 0; it.braveCount = 0; it.itemUsed = 0
                }
                stageIndex = 0
                turn = 0
                applyStage()
                showGame()
            }
            .setNegativeButton("タイトルへ") { _, _ -> showTitle() }
            .show()
    }

    /**
     * 条件ボーナスの発動判定。condStat が 1〜3 のときだけ働く。
     * 例: 運動(2)が25以上なら モテモテ+8。
     */
    private fun meetsCond(p: Player, ev: GameEvent): Boolean = when (ev.condStat) {
        1 -> p.manpuku >= ev.condMin
        2 -> p.juujitsu >= ev.condMin
        3 -> p.yuujou >= ev.condMin
        else -> false
    }

    /** 家族が増えるほどプラスのステータス上昇が大きくなる（マイナスはそのまま） */
    private fun gain(p: Player, delta: Int): Int {
        if (delta <= 0) return delta
        val mul = when {
            p.hasChild -> Skill.CHILD_MULTIPLIER
            p.married -> Skill.MARRIED_MULTIPLIER
            else -> 1f
        }
        return Math.round(delta * mul)
    }

    // ---------------- 就職イベント ----------------

    /**
     * 就職マス。必要スキルを満たす職業だけ選べる。
     * CPUは条件を満たすなかで最も給料の高い職業を自動で選ぶ。
     */
    private fun showJobSelect(p: Player, ev: GameEvent) {
        val available = jobsData.jobs.filter { j -> p.skills.containsAll(j.requires) }
        if (available.isEmpty()) {
            nextTurn()
            return
        }
        if (!p.isHuman) {
            val best = available.maxByOrNull { it.salary } ?: available.first()
            p.jobId = best.id
            statusText.text = "${who(p)} は ${best.icon}${best.name} に なった！"
            updateStatsBar()
            handler.postDelayed({ nextTurn() }, Speed.eventWaitMs)
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        content.addView(ImageView(this).apply {
            setImageResource(ev.bgRes)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        content.addView(TextView(this).apply {
            text = ev.message
            textSize = 15f
            setTextColor(Color.parseColor("#263238"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(Color.WHITE, Color.parseColor("#33691E"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .create()

        for (j in available) {
            val cur = if (j.id == p.jobId) "（いまの しごと）" else ""
            val bonus = buildString {
                if (j.dManpuku != 0) append(" 満腹${plus(j.dManpuku)}")
                if (j.dJuujitsu != 0) append(" 充実${plus(j.dJuujitsu)}")
                if (j.dYuujou != 0) append(" 友情${plus(j.dYuujou)}")
            }
            content.addView(Button(this).apply {
                text = "${j.icon} ${j.name}$cur\nきゅうりょう ${j.salary}$bonus"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = roundedBg(Color.parseColor("#00897B"))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    p.jobId = j.id
                    statusText.text = "${j.icon} ${j.name} に なった！"
                    updateStatsBar()
                    dialog.dismiss()
                    nextTurn()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        content.addView(Button(this).apply {
            text = "いまは やめておく"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#78909C"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                dialog.dismiss()
                nextTurn()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        dialog.show()
    }

    private fun plus(v: Int): String = if (v > 0) "+$v" else "$v"

    // ---------------- お店（スキル購入）----------------

    /**
     * お店マス。おかねを払ってスキルを買う。
     * CPUは買えるなかで最も安いものを1つだけ買う（買い占めて有利になりすぎないように）。
     */
    private fun showShop(p: Player, ev: GameEvent) {
        val sellable = jobsData.skills.filter { it.id !in p.skills }
        if (sellable.isEmpty()) {
            nextTurn()
            return
        }
        if (!p.isHuman) {
            val buy = sellable.filter { it.cost <= p.money }.minByOrNull { it.cost }
            if (buy != null) {
                p.money -= buy.cost
                p.skills.add(buy.id)
                statusText.text = "${who(p)} は ${buy.icon}${buy.name} を てにいれた！"
                updateStatsBar()
            }
            handler.postDelayed({ nextTurn() }, Speed.eventWaitMs)
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        content.addView(ImageView(this).apply {
            setImageResource(ev.bgRes)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        val moneyLabel = TextView(this).apply {
            text = "もっている おかね: ${p.money}"
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#263238"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(Color.WHITE, Color.parseColor("#33691E"))
        }
        content.addView(moneyLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .create()

        val buttons = HashMap<String, Button>()
        for (sk in sellable) {
            val b = Button(this).apply {
                text = "${sk.icon} ${sk.name}  ${sk.cost}\n${sk.desc}"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = roundedBg(Color.parseColor("#6A1B9A"))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    if (p.money < sk.cost) return@setOnClickListener
                    p.money -= sk.cost
                    p.skills.add(sk.id)
                    moneyLabel.text = "もっている おかね: ${p.money}"
                    isEnabled = false
                    alpha = 0.4f
                    text = "${sk.icon} ${sk.name}  かった！"
                    // 買った結果、他の商品が買えなくなることがあるので毎回すべて更新する
                    for ((id, btn) in buttons) {
                        val s2 = skillOf(id) ?: continue
                        if (id !in p.skills) {
                            btn.isEnabled = p.money >= s2.cost
                            btn.alpha = if (btn.isEnabled) 1f else 0.4f
                        }
                    }
                    updateStatsBar()
                }
            }
            b.isEnabled = p.money >= sk.cost
            b.alpha = if (b.isEnabled) 1f else 0.4f
            buttons[sk.id] = b
            content.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        content.addView(Button(this).apply {
            text = "おみせを でる"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#78909C"))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                dialog.dismiss()
                nextTurn()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        dialog.show()
    }

    // ---------------- ちょうせん（受験・こくはく）----------------

    object Exam {
        const val MIN_RATE = 20
        const val MAX_RATE = 95      // どれだけ高くても必ず成功するわけではない
    }

    /** ちょうせんの対象ステータス値を取り出す */
    private fun challengeValue(p: Player, ev: GameEvent): Int = when (ev.challengeStat) {
        2 -> p.juujitsu
        3 -> p.yuujou
        else -> p.manpuku
    }

    /** 成功率(%)。指定ステータスが高いほど上がる */
    private fun successRate(p: Player, ev: GameEvent): Int =
        (ev.baseRate + challengeValue(p, ev)).coerceIn(Exam.MIN_RATE, Exam.MAX_RATE)

    /**
     * 受験・こくはくの共通処理。
     * 成功すると全ステータスが上がり、失敗すると少し戻ってやり直せる。
     */
    private fun showChallenge(p: Player, ev: GameEvent) {
        if (ev.kind == EventKind.EXAM) p.examTries++
        val rate = successRate(p, ev)
        val passed = Random.nextInt(100) < rate
        val statName = statNames[(ev.challengeStat - 1).coerceIn(0, 2)]

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(ImageView(this).apply {
            setImageResource(ev.bgRes)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        val msg = StringBuilder(ev.message)
        msg.append("\n\n$statName ${challengeValue(p, ev)} → せいこうりつ ${rate}%\n\n")
        if (ev.kind == EventKind.EXAM) {
            val left = Skill.EXAM_MAX_TRIES - p.examTries
            if (!passed && left > 0) msg.append("（あと ${left}かい ちょうせんできる）\n")
        }
        if (passed) {
            msg.append(ev.passMessage.ifBlank { "🌸 せいこう！ おめでとう！" })
            msg.append("\n${statNames[0]}・${statNames[1]}・${statNames[2]} が +${ev.passGain}")
        } else {
            msg.append(ev.failMessage.ifBlank { "😢 ざんねん… もういちど がんばろう。" })
            msg.append("\n$statName -${ev.failLoss}　${-ev.failMove}マス もどる")
        }
        content.addView(TextView(this).apply {
            text = msg.toString()
            textSize = 16f
            setTextColor(Color.parseColor("#263238"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(
                Color.WHITE,
                if (passed) Color.parseColor("#E91E63") else Color.parseColor("#5E35B1")
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (passed) {
                    p.manpuku = (p.manpuku + gain(p, ev.passGain)).coerceIn(0, 999)
                    p.juujitsu = (p.juujitsu + gain(p, ev.passGain)).coerceIn(0, 999)
                    p.yuujou = (p.yuujou + gain(p, ev.passGain)).coerceIn(0, 999)
                    if (ev.kind == EventKind.EXAM) p.passedExam = true
                    if (ev.kind == EventKind.LOVE) p.hasLover = true
                    updateStatsBar()
                    nextTurn()
                } else {
                    when (ev.challengeStat) {
                        2 -> p.juujitsu = (p.juujitsu - ev.failLoss).coerceIn(0, 999)
                        3 -> p.yuujou = (p.yuujou - ev.failLoss).coerceIn(0, 999)
                        else -> p.manpuku = (p.manpuku - ev.failLoss).coerceIn(0, 999)
                    }
                    updateStatsBar()
                    statusText.text = "${who(p)} は ${-ev.failMove}マス もどる…"
                    handler.postDelayed(
                        { movePiece(p, ev.failMove, fromEvent = true) },
                        Speed.eventWaitMs
                    )
                }
            }
            .show()
    }

    // ---------------- イベント表示（汎用） ----------------

    /**
     * イベントの結果をステータスへ反映して手番を進める。
     * 通常イベントと選択肢イベントの共通処理。
     */
    private fun applyOutcome(
        p: Player, d1: Int, d2: Int, d3: Int, dMove: Int, skip: Boolean, kind: EventKind,
        itemId: String = ""
    ) {
        if (dMove < 0) p.backSteps += -dMove
        val got = giveItem(p, itemId)
        if (got.isNotEmpty()) statusText.text = got.trim()
        p.manpuku = (p.manpuku + gain(p, d1)).coerceIn(0, 999)
        p.juujitsu = (p.juujitsu + gain(p, d2)).coerceIn(0, 999)
        p.yuujou = (p.yuujou + gain(p, d3)).coerceIn(0, 999)
        if (kind == EventKind.WEDDING) p.married = true
        if (kind == EventKind.BIRTH) p.hasChild = true
        if (skip) p.skipNext = true
        updateStatsBar()
        if (dMove != 0) {
            statusText.text = if (dMove > 0) "${p.chara.name} は ${dMove}マス すすむ！"
                              else "${p.chara.name} は ${-dMove}マス もどる…"
            handler.postDelayed({ movePiece(p, dMove, fromEvent = true) }, Speed.eventWaitMs)
        } else {
            nextTurn()
        }
    }

    /**
     * 選択肢イベント。人間はボタンで えらび、CPUは自動で えらぶ。
     * えらんだ結果は showChoiceResult で写真つきに見せる。
     */
    private fun showChoice(p: Player, ev: GameEvent) {
        if (!p.isHuman) {
            handler.postDelayed({ showChoiceResult(p, ev, cpuPickChoice(ev)) }, Speed.eventWaitMs)
            statusText.text = "${who(p)} は かんがえている…"
            return
        }
        val content = buildEventContent(
            p, ev.bgRes, ev.groupSize, ev.kind, ev.message, Color.parseColor("#1565C0")
        )
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .create()
        for (c in ev.choices) {
            content.addView(Button(this).apply {
                text = c.label
                textSize = 16f
                setTextColor(Color.WHITE)
                background = roundedBg(Color.parseColor("#1E88E5"))
                setOnClickListener {
                    dialog.dismiss()
                    showChoiceResult(p, ev, c)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        dialog.show()
    }

    /** イベントで もちものが もらえるときの ひとこと */
    private fun itemPreview(id: String): String {
        val def = itemOf(id) ?: return ""
        return "\n\n🎒 ${def.icon}${def.name} を てにいれた！\n（${def.desc}）"
    }

    /** CPUの えらびかた。のびが大きいものを えらぶが、3割は 気まぐれにする */
    private fun cpuPickChoice(ev: GameEvent): Choice {
        if (Random.nextInt(100) < 30) return ev.choices[Random.nextInt(ev.choices.size)]
        return ev.choices.maxByOrNull {
            it.dManpuku + it.dJuujitsu + it.dYuujou + it.dMove * 3 + (if (it.skipTurn) -15 else 0)
        } ?: ev.choices[0]
    }

    /** えらんだ結果を写真つきで見せる */
    private fun showChoiceResult(p: Player, ev: GameEvent, c: Choice) {
        val head = if (p.isHuman) "" else "${who(p)} は「${c.label}」を えらんだ\n\n"
        val text = head + c.message + (if (c.skipTurn) "\n\n💤 つぎの ばんは おやすみ…" else "") +
            itemPreview(if (c.itemId.isNotBlank()) c.itemId else ev.itemId)
        val content = buildEventContent(
            p, if (c.bgRes != 0) c.bgRes else ev.bgRes, ev.groupSize, ev.kind,
            text, Color.parseColor("#1565C0")
        )
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (c.dMove < 0) p.braveCount++
                applyOutcome(
                    p, c.dManpuku, c.dJuujitsu, c.dYuujou, c.dMove, c.skipTurn, ev.kind,
                    if (c.itemId.isNotBlank()) c.itemId else ev.itemId
                )
            }
            .show()
    }

    /** 写真＋キャラの並び＋メッセージ枠 を組み立てる（通常イベントと選択肢で共用） */
    private fun buildEventContent(
        p: Player, bgRes: Int, groupSize: Int, kind: EventKind,
        message: String, borderColor: Int
    ): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Triple(画像res, 本人か, 家族か)
        val lineup = ArrayList<Triple<Int, Boolean, Boolean>>()
        val showPartner = p.married || kind == EventKind.WEDDING
        val showChild = p.hasChild || kind == EventKind.BIRTH
        val friends = charas.filter { it != p.chara }.take((groupSize - 1).coerceIn(0, 3))
        if (friends.isNotEmpty()) lineup.add(Triple(friends[0].resId, false, false))
        lineup.add(Triple(p.chara.resId, true, false))
        if (showPartner) lineup.add(Triple(p.chara.partnerRes, false, true))
        if (showChild) lineup.add(Triple(p.chara.childRes, false, true))
        for (i in 1 until friends.size) lineup.add(Triple(friends[i].resId, false, false))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        val many = lineup.size >= 4
        val friendSize = if (many) dp(62) else dp(84)
        val meSize = if (many) dp(84) else dp(108)
        val partnerSize = if (many) dp(76) else dp(100)
        for ((idx, entry) in lineup.withIndex()) {
            val (res, isMe, isFamily) = entry
            val isChild = isFamily && res == p.chara.childRes
            val size = when {
                isMe -> meSize
                isChild -> (partnerSize * 0.72f).toInt()
                isFamily -> partnerSize
                else -> friendSize
            }
            row.addView(ImageView(this).apply {
                setImageResource(res)
                rotation = when {
                    isMe -> 3f
                    isFamily -> -4f
                    idx % 2 == 0 -> -9f
                    else -> 9f
                }
            }, LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = if (isMe || isFamily) dp(6) else dp(14)
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        frame.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        content.addView(frame)

        content.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.parseColor("#263238"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.WHITE, borderColor)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        return content
    }

    /** 通常イベント（選択肢が あれば showChoice へ まわす） */
    private fun showEvent(p: Player, ev: GameEvent) {
        if (ev.choices.isNotEmpty()) {
            showChoice(p, ev)
            return
        }
        val bonusHit = meetsCond(p, ev)
        val text = ev.message +
            (if (bonusHit && ev.condMessage.isNotBlank()) "\n\n✨ ${ev.condMessage}" else "") +
            (if (ev.skipTurn) "\n\n💤 つぎの ばんは おやすみ…" else "") +
            itemPreview(ev.itemId)
        val content = buildEventContent(
            p, ev.bgRes, ev.groupSize, ev.kind, text,
            if (bonusHit) Color.parseColor("#F9A825") else Color.parseColor("#33691E")
        )
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                var d1 = ev.dManpuku
                var d2 = ev.dJuujitsu
                var d3 = ev.dYuujou
                // 条件ボーナス（例: 運動が25以上なら モテモテ+8）
                if (meetsCond(p, ev)) {
                    d1 += ev.condBonus1
                    d2 += ev.condBonus2
                    d3 += ev.condBonus3
                }
                applyOutcome(p, d1, d2, d3, ev.dMove, ev.skipTurn, ev.kind, ev.itemId)
            }
            .show()
    }

    // ================= 盤面 =================
    object Board {
        /** 本線のマス数。stages.json の mainCellCount で上書きされる */
        var MAIN_COUNT = 30
        /** 洞窟のマス数。cave.json の cellCount で上書きされる */
        var CAVE_COUNT = 20

        var normalEventCells: Set<Int> = emptySet()
        var weddingCells: Set<Int> = emptySet()
        var birthCells: Set<Int> = emptySet()
        var branchCell: Int = -1

        var jobCells: Set<Int> = emptySet()
        var shopCells: Set<Int> = emptySet()
        var examCells: Set<Int> = emptySet()
        var loveCells: Set<Int> = emptySet()
        /** そのマスで損をするか（ステータスがへる、または もどる） */
        var badCells: Set<Int> = emptySet()
        var caveBadCells: Set<Int> = emptySet()
        var caveEventCells: Set<Int> = emptySet()
        /** 1回休みマス */
        var restCells: Set<Int> = emptySet()
        var caveRestCells: Set<Int> = emptySet()
        /** 選択肢マス */
        var choiceCells: Set<Int> = emptySet()
        var caveChoiceCells: Set<Int> = emptySet()

        fun count(cave: Boolean) = if (cave) CAVE_COUNT else MAIN_COUNT
        fun goal(cave: Boolean) = count(cave) - 1
    }

    class BoardView(
        context: Context,
        private val players: List<Player>,
        mainBgRes: Int,
        caveBgRes: Int
    ) : View(context) {
        var turnIndex = 0
        var panEnabled = false

        /** false=本線 / true=洞窟。手番プレイヤーのいる世界を描画する */
        var world = false
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val cellCount: Int get() = Board.count(world)
        private val goalIndex: Int get() = Board.goal(world)
        /** 今の世界にいるプレイヤーだけを描く */
        private fun visiblePlayers() = players.withIndex().filter { it.value.inCave == world }

        private val bitmaps: Map<Int, Bitmap> = players.map { it.chara.resId }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val partnerBitmaps: Map<Int, Bitmap> = players.map { it.chara.partnerRes }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val childBitmaps: Map<Int, Bitmap> = players.map { it.chara.childRes }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val mainBg: Bitmap = BitmapFactory.decodeResource(resources, mainBgRes)
        private val cave: Bitmap = BitmapFactory.decodeResource(resources, caveBgRes)
        // マスの目印
        private val markCave: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mark_cave)
        private val markChapel: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mark_chapel)
        private val markHeart: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.mark_heart)

        /** 次の段階へ切り替える。見ている位置は保ったまま倍率だけ変える */
        fun cycleZoom(focusCell: Int) {
            Zoom.next()
            applyMetrics()
            camAnim?.cancel()
            camX = worldX(focusCell)
            invalidate()
        }

        private var camX = 0f
        private var camAnim: ValueAnimator? = null
        private var spacing = 0f
        private var cellR = 0f
        private var laneY = 0f
        private var lastTouchX = 0f

        private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C9A66B"); strokeWidth = 26f; strokeCap = Paint.Cap.ROUND
        }
        private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF8E1") }
        // マスの色は種類がひと目で分かるよう用途ごとに固定する
        //   緑=good / 紫=bad / オレンジ=ワープ / ピンク=けっこん・出産
        private val goodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66BB6A") }
        private val badPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9575CD") }
        private val warpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9800") }
        private val familyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F48FB1") }
        private val examPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF5350") }
        private val jobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DD0E1") }
        private val shopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD54F") }
        private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#90A4AE") }
        private val choicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#42A5F5") }
        private val branchTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private val cellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8D6E63"); style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#81C784") }
        private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB74D") }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5D4037"); textAlign = Paint.Align.CENTER
        }
        private val eventTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#263238"); textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        private val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
        private val mapEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8D6E63"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val mapLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C9A66B"); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
        }
        private val mapCellPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var bounce = 0f
        private val bounceAnim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                bounce = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            bounceAnim.start()
        }

        override fun onDetachedFromWindow() {
            bounceAnim.cancel()
            camAnim?.cancel()
            super.onDetachedFromWindow()
        }

        private fun worldX(i: Int) = spacing * i

        /** アニメーションなしで即座にカメラを合わせる（世界の切り替え時に使う） */
        fun snapToCell(i: Int) {
            camAnim?.cancel()
            camX = worldX(i)
            invalidate()
        }

        fun focusCell(i: Int, slow: Boolean) {
            val target = worldX(i)
            camAnim?.cancel()
            camAnim = ValueAnimator.ofFloat(camX, target).apply {
                duration = if (slow) 1300 else 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    camX = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!panEnabled) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    camAnim?.cancel()
                    lastTouchX = e.x
                }
                MotionEvent.ACTION_MOVE -> {
                    camX = (camX - (e.x - lastTouchX)).coerceIn(0f, worldX(goalIndex))
                    lastTouchX = e.x
                    invalidate()
                }
            }
            return true
        }

        /** ズーム段階と表示サイズからマスの寸法を決める */
        private fun applyMetrics() {
            if (width == 0) return
            spacing = width / Zoom.cells
            cellR = spacing * Zoom.cellRatio
            laneY = height * 0.72f
            // 縮小してもマスの記号が読めるよう、文字サイズに下限を設ける
            val minText = 11f * resources.displayMetrics.density
            textPaint.textSize = maxOf(cellR * 0.62f, minText)
            eventTextPaint.textSize = maxOf(cellR * 0.62f, minText)
            branchTextPaint.textSize = maxOf(cellR * 0.46f, minText * 0.8f)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            applyMetrics()
            camX = worldX(players.getOrNull(turnIndex)?.position ?: 0)
        }

        override fun onDraw(canvas: Canvas) {
            if (spacing == 0f) return
            drawForest(canvas)
            drawTrack(canvas)
            drawMiniMap(canvas)
        }

        /** 森の背景。カメラの0.25倍でパララックス、左右反転しながら並べて継ぎ目なくループ */
        private fun drawForest(canvas: Canvas) {
            val bmp = if (world) cave else mainBg
            val scale = height.toFloat() / bmp.height
            val tileW = bmp.width * scale
            if (tileW <= 0f) return
            val offset = -camX * 0.25f
            val iMin = floor((0f - offset - tileW) / tileW).toInt()
            val iMax = floor((width - offset) / tileW).toInt() + 1
            for (i in iMin..iMax) {
                val left = offset + i * tileW
                val dst = RectF(left, 0f, left + tileW, height.toFloat())
                if (Math.floorMod(i, 2) == 0) {
                    canvas.drawBitmap(bmp, null, dst, null)
                } else {
                    canvas.save()
                    canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
                    canvas.drawBitmap(bmp, null, dst, null)
                    canvas.restore()
                }
            }
        }

        /** 出産マスは、結婚済みのプレイヤーが1人もいない間は姿を現さない */
        private fun lockedBirth(i: Int): Boolean =
            !world && i in Board.birthCells && players.none { it.married }

        private fun cellFill(i: Int): Paint = when {
            i == 0 -> startPaint
            i == goalIndex -> goalPaint
            world -> when {
                i in Board.caveChoiceCells -> choicePaint
                i in Board.caveRestCells -> restPaint
                i in Board.caveBadCells -> badPaint
                i in Board.caveEventCells -> goodPaint
                else -> cellPaint
            }
            i == Board.branchCell -> warpPaint            // ワープ = オレンジ
            lockedBirth(i) -> cellPaint
            i in Board.birthCells -> familyPaint          // 出産 = ピンク
            i in Board.weddingCells -> familyPaint        // けっこん = ピンク
            i in Board.examCells -> examPaint            // じゅけん = あか
            i in Board.loveCells -> familyPaint          // こくはく = ピンク
            i in Board.jobCells -> jobPaint
            i in Board.shopCells -> shopPaint
            i in Board.choiceCells -> choicePaint         // えらぶ = あお
            i in Board.restCells -> restPaint             // おやすみ = グレー
            i in Board.badCells -> badPaint               // bad = むらさき
            i in Board.normalEventCells -> goodPaint      // good = みどり
            else -> cellPaint
        }

        private fun drawTrack(canvas: Canvas) {
            val dx = width / 2f - camX
            canvas.save()
            canvas.translate(dx, 0f)

            val first = (((camX - width) / spacing).toInt() - 1).coerceIn(0, cellCount - 1)
            val last = (((camX + width) / spacing).toInt() + 1).coerceIn(0, cellCount - 1)

            canvas.drawLine(worldX(0), laneY, worldX(cellCount - 1), laneY, pathPaint)

            // 目印（洞窟の入口・チャペル・ハート）をマスの後ろに立てる
            if (!world) {
                for (i in first..last) {
                    val mark = when {
                        i == Board.branchCell -> markCave
                        i in Board.weddingCells -> markChapel
                        i in Board.birthCells && !lockedBirth(i) -> markHeart
                        else -> null
                    } ?: continue
                    val ms = cellR * 2.3f
                    val mx = worldX(i)
                    val mb = laneY - cellR * 0.35f
                    canvas.drawBitmap(mark, null,
                        RectF(mx - ms / 2, mb - ms, mx + ms / 2, mb), null)
                }
            }

            for (i in first..last) {
                val x = worldX(i)
                val eventCells = if (world) Board.caveEventCells else Board.normalEventCells
                val badCells = if (world) Board.caveBadCells else Board.badCells
                val restCells = if (world) Board.caveRestCells else Board.restCells
                val choiceCells = if (world) Board.caveChoiceCells else Board.choiceCells
                canvas.drawCircle(x, laneY, cellR, cellFill(i))
                canvas.drawCircle(x, laneY, cellR, cellEdge)
                when {
                    i == 0 -> canvas.drawText("S", x, laneY + textPaint.textSize / 3, textPaint)
                    i == goalIndex -> canvas.drawText("G", x, laneY + textPaint.textSize / 3, textPaint)
                    !world && i == Board.branchCell ->
                        canvas.drawText("あな", x, laneY + branchTextPaint.textSize / 3, branchTextPaint)
                    lockedBirth(i) -> canvas.drawText("$i", x, laneY + textPaint.textSize / 3, textPaint)
                    !world && i in Board.birthCells -> canvas.drawText("👶", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    !world && i in Board.weddingCells -> canvas.drawText("💒", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    !world && i in Board.examCells -> canvas.drawText("🌸", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    !world && i in Board.loveCells -> canvas.drawText("💗", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    !world && i in Board.jobCells -> canvas.drawText("💼", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    !world && i in Board.shopCells -> canvas.drawText("🛒", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    i in choiceCells -> canvas.drawText("❓", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    i in restCells -> canvas.drawText("💤", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    i in badCells -> canvas.drawText("💧", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    i in eventCells -> canvas.drawText("⭐", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    else -> canvas.drawText("$i", x, laneY + textPaint.textSize / 3, textPaint)
                }
            }

            val byCell = visiblePlayers().groupBy { it.value.position.coerceIn(0, goalIndex) }
            for ((cell, group) in byCell) {
                if (cell < first - 1 || cell > last + 1) continue
                val cx = worldX(cell)
                val sorted = group.sortedBy { if (it.index == turnIndex) 1 else 0 }
                for ((slot, entry) in sorted.withIndex()) {
                    val isTurn = entry.index == turnIndex
                    val bmp = bitmaps[entry.value.chara.resId] ?: continue
                    val s = cellR * (if (isTurn) 2.5f else 1.8f)
                    val pieceDx = (slot - (sorted.size - 1) / 2f) * cellR * 0.8f
                    val lift = if (isTurn) (sin(bounce) * 0.5f + 0.5f) * cellR * 0.4f else 0f
                    val shadowScale = 1f - (lift / (cellR * 0.4f)) * 0.35f
                    val shadowW = s * 0.40f * shadowScale
                    val shadowH = s * 0.12f * shadowScale
                    val baseY = laneY - cellR * 0.55f
                    canvas.drawOval(
                        cx + pieceDx - shadowW, baseY - shadowH,
                        cx + pieceDx + shadowW, baseY + shadowH,
                        shadowPaint
                    )
                    if (entry.value.married) {
                        val ps = s * 0.78f
                        val px = cx + pieceDx + s * 0.42f
                        canvas.drawOval(
                            px - ps * 0.36f, baseY - shadowH * 0.85f,
                            px + ps * 0.36f, baseY + shadowH * 0.85f,
                            shadowPaint
                        )
                        partnerBitmaps[entry.value.chara.partnerRes]?.let { pb ->
                            canvas.drawBitmap(pb, null,
                                RectF(px - ps / 2, baseY - ps, px + ps / 2, baseY), null)
                        }
                        if (entry.value.hasChild) {
                            val cs = s * 0.52f
                            val ccx = px + ps * 0.46f
                            canvas.drawOval(
                                ccx - cs * 0.36f, baseY - shadowH * 0.7f,
                                ccx + cs * 0.36f, baseY + shadowH * 0.7f,
                                shadowPaint
                            )
                            childBitmaps[entry.value.chara.childRes]?.let { cb ->
                                canvas.drawBitmap(cb, null,
                                    RectF(ccx - cs / 2, baseY - cs, ccx + cs / 2, baseY), null)
                            }
                        }
                    }
                    canvas.drawBitmap(bmp, null, RectF(
                        cx + pieceDx - s / 2, baseY - s - lift,
                        cx + pieceDx + s / 2, baseY - lift
                    ), null)
                }
            }
            canvas.restore()
        }

        /** 上部: スタート→ゴール全体が見えるミニマップ */
        private fun drawMiniMap(canvas: Canvas) {
            val frameL = width * 0.03f
            val frameR = width * 0.97f
            val frameT = height * 0.04f
            val frameB = height * 0.34f
            val rect = RectF(frameL, frameT, frameR, frameB)
            canvas.drawRoundRect(rect, 18f, 18f, mapBgPaint)
            canvas.drawRoundRect(rect, 18f, 18f, mapEdgePaint)

            val padX = width * 0.045f
            val l = frameL + padX
            val r = frameR - padX
            val lineY = frameT + (frameB - frameT) * 0.68f
            canvas.drawLine(l, lineY, r, lineY, mapLinePaint)

            val miniR = (frameB - frameT) * 0.085f
            for (i in 0 until cellCount) {
                val x = l + (r - l) * i / (cellCount - 1)
                mapCellPaint.color = cellFill(i).color
                canvas.drawCircle(x, lineY, miniR, mapCellPaint)
            }

            val byCell = visiblePlayers().groupBy { it.value.position.coerceIn(0, goalIndex) }
            for ((cell, group) in byCell) {
                val x = l + (r - l) * cell / (cellCount - 1)
                val sorted = group.sortedBy { if (it.index == turnIndex) 1 else 0 }
                for ((slot, entry) in sorted.withIndex()) {
                    val bmp = bitmaps[entry.value.chara.resId] ?: continue
                    val isTurn = entry.index == turnIndex
                    val s = (frameB - frameT) * (if (isTurn) 0.46f else 0.34f)
                    val ddx = (slot - (sorted.size - 1) / 2f) * s * 0.35f
                    canvas.drawBitmap(bmp, null, RectF(
                        x + ddx - s / 2, lineY - miniR * 1.6f - s,
                        x + ddx + s / 2, lineY - miniR * 1.6f
                    ), null)
                }
            }
        }
    }

    // ================= ルーレット =================
    class RouletteView(context: Context, private val onResult: (Int) -> Unit) : View(context) {
        var locked = false
        private var rotation2 = 0f
        private var spinning = false
        private var resultNum: Int? = null

        private val colors = intArrayOf(
            Color.parseColor("#EF9A9A"), Color.parseColor("#FFCC80"),
            Color.parseColor("#FFF59D"), Color.parseColor("#A5D6A7"),
            Color.parseColor("#90CAF9"), Color.parseColor("#CE93D8")
        )
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#37474F"); textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D32F2F"); textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val resultBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val resultEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D32F2F"); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F") }

        private var resultScale = 1f

        /**
         * 「ふつう」用の回転カーブ。
         * 前半72%の時間で回転量の88.5%を ほぼ等速の速いスピードで消化し、
         * 残り28%の時間で 一気に減速して止まる（切り替わりで速度が連続するよう係数を調整）。
         */
        private val snapSpin = TimeInterpolator { t ->
            val k = 0.72f
            val a = 0.885f
            if (t < k) t / k * a
            else {
                val u = (t - k) / (1f - k)
                val inv = 1f - u
                a + (1f - a) * (1f - inv * inv * inv)
            }
        }

        init {
            setOnClickListener { if (!locked) spin() }
        }

        fun autoSpin() = spin()

        private fun spin() {
            if (spinning) return
            spinning = true
            resultNum = null
            val result = Random.nextInt(1, 7)
            val from = rotation2
            val turns = if (Speed.fast) 3 else 5 + Random.nextInt(3)
            val to = from - (from % 360f) + 360f * turns + (330f - (result - 1) * 60f)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Speed.spinMs
                // ふつう=直前まで速く回して急停止 / はやい=短いので従来どおりなめらかに減速
                interpolator = if (Speed.fast) DecelerateInterpolator(2.2f) else snapSpin
                addUpdateListener {
                    rotation2 = from + (to - from) * (it.animatedValue as Float)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        spinning = false
                        showResult(result)
                    }
                })
                start()
            }
        }

        private fun showResult(result: Int) {
            resultNum = result
            ValueAnimator.ofFloat(0.3f, 1.15f, 1f).apply {
                duration = 350
                addUpdateListener {
                    resultScale = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        postDelayed({ onResult(result) }, Speed.resultMs)
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f * 0.82f
            numPaint.textSize = r * 0.28f
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)

            canvas.save()
            canvas.rotate(rotation2, cx, cy)
            for (i in 0 until 6) {
                arcPaint.color = colors[i]
                canvas.drawArc(rect, -90f + i * 60f, 60f, true, arcPaint)
                canvas.drawArc(rect, -90f + i * 60f, 60f, true, edgePaint)
                val ang = Math.toRadians((-90 + i * 60 + 30).toDouble())
                val tx = cx + (r * 0.62f) * Math.cos(ang).toFloat()
                val ty = cy + (r * 0.62f) * Math.sin(ang).toFloat() + numPaint.textSize / 3
                canvas.drawText("${i + 1}", tx, ty, numPaint)
            }
            canvas.restore()

            canvas.drawPath(Path().apply {
                moveTo(cx, cy - r - 6f)
                lineTo(cx - r * 0.1f, cy - r + r * 0.22f)
                lineTo(cx + r * 0.1f, cy - r + r * 0.22f)
                close()
            }, pinPaint)

            val res = resultNum
            if (res != null) {
                val br = r * 0.46f * resultScale
                canvas.drawCircle(cx, cy, br, resultBgPaint)
                canvas.drawCircle(cx, cy, br, resultEdgePaint)
                resultPaint.textSize = br * 1.2f
                canvas.drawText("$res", cx, cy + resultPaint.textSize * 0.36f, resultPaint)
            } else {
                arcPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r * 0.22f, arcPaint)
                numPaint.textSize = r * 0.16f
                canvas.drawText(if (spinning) "..." else "TAP", cx, cy + numPaint.textSize / 3, numPaint)
            }
        }
    }
}
