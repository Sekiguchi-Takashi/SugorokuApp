package com.appathy.sugoroku

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
        var hasChild: Boolean = false    // 子どもが生まれると2.0倍
    ) {
        val total: Int get() = manpuku + juujitsu + yuujou
    }

    enum class EventKind { NORMAL, WEDDING, BIRTH }

    data class GameEvent(
        val bgRes: Int,
        val message: String,
        val dManpuku: Int = 0,
        val dJuujitsu: Int = 0,
        val dYuujou: Int = 0,
        val groupSize: Int = 1,
        val kind: EventKind = EventKind.NORMAL
    )

    /** 1ステージ分の盤面定義 */
    data class Stage(
        val name: String,
        val events: Map<Int, GameEvent>,
        val moves: IntArray
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
    }

    object Speed {
        var fast = false
        val spinMs get() = if (fast) 1100L else 2600L
        val stepMs get() = if (fast) 140L else 300L
        val resultMs get() = if (fast) 300L else 700L
        val cpuWaitMs get() = if (fast) 400L else 1000L
        val eventWaitMs get() = if (fast) 350L else 700L
    }

    private val charas by lazy {
        listOf(
            Chara("しばいぬ", R.drawable.chara_shiba, R.drawable.chara_shiba_f, R.drawable.child_shiba),
            Chara("うさぎ", R.drawable.chara_usagi, R.drawable.chara_usagi_f, R.drawable.child_usagi),
            Chara("いのしし", R.drawable.chara_inoshishi, R.drawable.chara_inoshishi_f, R.drawable.child_inoshishi),
            Chara("トラ", R.drawable.chara_tora, R.drawable.chara_tora_f, R.drawable.child_tora)
        )
    }

    // ---- 結婚・出産イベント（全ステージ共通、各ステージに2箇所ずつ配置）----
    private val weddingEvent by lazy {
        GameEvent(
            R.drawable.bg_chapel,
            "きょうかいで けっこんしきを あげた！\nこれから ずっと いっしょ。\n満腹10　充実20　友情20\n\n💍 これから イベントの うれしさが 1.5ばい！",
            dManpuku = 10, dJuujitsu = 20, dYuujou = 20, kind = EventKind.WEDDING
        )
    }
    private val birthEvent by lazy {
        GameEvent(
            R.drawable.bg_hospital,
            "びょういんで あかちゃんが うまれた！\nさんにん かぞくに なった。\n満腹15　充実30　友情30\n\n👶 これから イベントの うれしさが 2ばい！",
            dManpuku = 15, dJuujitsu = 30, dYuujou = 30, kind = EventKind.BIRTH
        )
    }

    // ================= ステージ定義 =================
    private val stages: List<Stage> by lazy {
        listOf(
            Stage(
                "もりのまち",
                mapOf(
                    2 to GameEvent(R.drawable.bg_famiresu, "レストランななで ごはんを食べた。\n満腹15　友情5", 15, 0, 5, 3),
                    4 to GameEvent(R.drawable.bg_beach, "みんなで海へ行った。\n満腹5　友情10", 5, 0, 10, 3),
                    6 to GameEvent(R.drawable.bg_park, "こうえんで 楽しく遊んだ。\n充実10", 0, 10, 0, 1),
                    7 to weddingEvent,
                    9 to GameEvent(R.drawable.bg_yasai, "無人野菜販売所で 野菜を買った。\n満腹10　充実5", 10, 5, 0, 1),
                    11 to GameEvent(R.drawable.bg_pool, "プールで たくさん泳いだ。\n満腹-5　充実10　友情5", -5, 10, 5, 3),
                    13 to GameEvent(R.drawable.bg_library, "図書館で しずかに本を読んだ。\n充実15", 0, 15, 0, 1),
                    14 to birthEvent,
                    15 to GameEvent(R.drawable.bg_cafe, "喫茶店ななで ひとやすみ。\n満腹8　充実8", 8, 8, 0, 2),
                    18 to GameEvent(R.drawable.bg_ground, "グラウンドで みんなと運動した。\n満腹-5　友情15", -5, 0, 15, 4),
                    19 to weddingEvent,
                    22 to GameEvent(R.drawable.bg_cinema, "映画館で 映画を見た。\n充実12　友情8", 0, 12, 8, 2),
                    23 to birthEvent,
                    26 to GameEvent(R.drawable.bg_cabin, "山のログハウスに とまった。\n満腹10　充実10　友情10", 10, 10, 10, 4)
                ),
                IntArray(30).apply {
                    this[3] = -2; this[8] = -3; this[10] = 2; this[12] = -3; this[16] = -2
                    this[20] = 2; this[21] = -3; this[24] = -4; this[27] = -3; this[28] = -2
                }
            ),
            Stage(
                "たびのステージ",
                mapOf(
                    2 to GameEvent(R.drawable.bg_bus, "バスに のって おでかけ。\n充実5　友情5", 0, 5, 5, 3),
                    4 to GameEvent(R.drawable.bg_train, "でんしゃで うみぞいを たびした。\n充実10　友情5", 0, 10, 5, 3),
                    6 to GameEvent(R.drawable.bg_airport, "くうこうから しゅっぱつ！\n充実15", 0, 15, 0, 1),
                    8 to weddingEvent,
                    9 to GameEvent(R.drawable.bg_paris, "パリの エッフェルとうを 見た。\n充実25　友情10", 0, 25, 10, 2),
                    12 to GameEvent(R.drawable.bg_ship, "ごうかきゃくせんに のった。\n満腹20　充実20", 20, 20, 0, 2),
                    13 to birthEvent,
                    15 to GameEvent(R.drawable.bg_onsen, "ゆのやどで おんせんに つかった。\n満腹15　充実15", 15, 15, 0, 2),
                    18 to GameEvent(R.drawable.bg_ryokan, "ふるい りょかんに とまった。\n充実15　友情10", 0, 15, 10, 3),
                    20 to weddingEvent,
                    22 to GameEvent(R.drawable.bg_mountain, "やまに のぼった。\n満腹-10　充実20　友情15", -10, 20, 15, 4),
                    24 to birthEvent,
                    26 to GameEvent(R.drawable.bg_ski, "スキーで すべった。\n満腹-5　充実20　友情15", -5, 20, 15, 4)
                ),
                IntArray(30).apply {
                    this[3] = -2; this[5] = -3; this[10] = -2; this[11] = -3; this[14] = 2
                    this[16] = -3; this[17] = -2; this[21] = -4; this[23] = -2; this[25] = 2
                    this[27] = -3; this[28] = -3
                }
            ),
            Stage(
                "あそびのステージ",
                mapOf(
                    2 to GameEvent(R.drawable.bg_mall, "モールで かいものした。\n満腹10　充実10", 10, 10, 0, 2),
                    4 to GameEvent(R.drawable.bg_amusement, "ゆうえんちで あそんだ。\n充実20　友情15", 0, 20, 15, 4),
                    6 to weddingEvent,
                    7 to GameEvent(R.drawable.bg_ferris, "かんらんしゃで ゆうやけを 見た。\n充実25　友情10", 0, 25, 10, 2),
                    10 to GameEvent(R.drawable.bg_waterpark, "ウォーターパークで あそんだ。\n満腹-5　充実20　友情15", -5, 20, 15, 4),
                    12 to birthEvent,
                    13 to GameEvent(R.drawable.bg_gym, "たいいくかんで あそんだ。\n満腹-5　友情20", -5, 0, 20, 4),
                    16 to GameEvent(R.drawable.bg_sickroom, "かぜを ひいて にゅういん…。\n満腹-15　充実-15", -15, -15, 0, 1),
                    18 to weddingEvent,
                    19 to GameEvent(R.drawable.bg_cinema, "えいがを 見た。\n充実12　友情8", 0, 12, 8, 2),
                    22 to GameEvent(R.drawable.bg_famiresu, "レストランで ごはんを 食べた。\n満腹15　友情5", 15, 0, 5, 3),
                    24 to birthEvent,
                    25 to GameEvent(R.drawable.bg_cafe, "きっさてんで ひとやすみ。\n満腹8　充実8", 8, 8, 0, 2),
                    27 to GameEvent(R.drawable.bg_park, "こうえんで あそんだ。\n充実10", 0, 10, 0, 1)
                ),
                IntArray(30).apply {
                    this[3] = -2; this[5] = -3; this[8] = -3; this[9] = 2; this[11] = -3
                    this[14] = -4; this[15] = 3; this[17] = -3; this[20] = -2; this[21] = 2
                    this[23] = -3; this[26] = -3; this[28] = -4
                }
            )
        )
    }

    private val players = ArrayList<Player>()
    private var turn = 0
    private var stageIndex = 0
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var boardView: BoardView
    private lateinit var rouletteView: RouletteView
    private lateinit var statusText: TextView
    private lateinit var speedButton: Button
    private lateinit var startButton: Button
    private lateinit var statsBar: TextView
    private lateinit var manpukuSkillButton: Button
    private lateinit var juujitsuSkillButton: Button

    private val stage: Stage get() = stages[stageIndex]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        Board.moves = stage.moves
        Board.normalEventCells = stage.events.filterValues { it.kind == EventKind.NORMAL }.keys
        Board.weddingCells = stage.events.filterValues { it.kind == EventKind.WEDDING }.keys
        Board.birthCells = stage.events.filterValues { it.kind == EventKind.BIRTH }.keys
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
            setPadding(dp(48), dp(16), dp(48), dp(16))
            setOnClickListener { showCharaSelect() }
        })
        setContentView(root)
    }

    // ---------------- キャラ選択画面 ----------------
    private fun showCharaSelect() {
        handler.removeCallbacksAndMessages(null)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "じぶんの キャラクターを えらんでね"
            textSize = 20f
            setTextColor(Color.parseColor("#33691E"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })
        val grid = GridLayout(this).apply { rowCount = 2; columnCount = 2 }
        val cellSize = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 2 - dp(32)
        for (c in charas) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            cell.addView(ImageButton(this).apply {
                setImageResource(c.resId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = roundedBg(Color.WHITE, Color.parseColor("#A5D6A7"))
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
                setOnClickListener { showCountSelect(c) }
            })
            cell.addView(TextView(this).apply {
                text = c.name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#33691E"))
            })
            grid.addView(cell)
        }
        root.addView(grid)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------- 人数選択画面 ----------------
    private fun showCountSelect(myChara: Chara) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "なんびきで あそぶ？"
            textSize = 22f
            setTextColor(Color.parseColor("#33691E"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "きみは ${myChara.name}！ ほかのなかまは コンピュータだよ"
            textSize = 14f
            setTextColor(Color.parseColor("#558B2F"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })
        val others = charas.filter { it != myChara }
        for (n in 1..4) {
            val label = if (n == 1) "ひとりで あそぶ" else "${n}ひきで あそぶ（CPU ${n - 1}ひき）"
            root.addView(Button(this).apply {
                text = label
                textSize = 18f
                setOnClickListener {
                    players.clear()
                    players.add(Player(myChara, isHuman = true))
                    for (i in 0 until n - 1) players.add(Player(others[i], isHuman = false))
                    turn = 0
                    stageIndex = 0
                    applyStage()
                    showGame()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        setContentView(root)
    }

    // ---------------- ゲーム画面 ----------------
    private fun showGame() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
        }

        boardView = BoardView(this, players)
        root.addView(boardView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        infoRow.addView(TextView(this).apply {
            text = "ステージ${stageIndex + 1}/${stages.size}「${stage.name}」\n🔵すすむ 🔴もどる ⭐イベント 💒けっこん 👶あかちゃん"
            textSize = 11f
            setTextColor(Color.parseColor("#558B2F"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedButton = Button(this).apply {
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                Speed.fast = !Speed.fast
                updateSpeedLabel()
            }
        }
        updateSpeedLabel()
        infoRow.addView(speedButton)
        root.addView(infoRow)

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
            text = "🍖満腹${Skill.MANPUKU_COST}→+${Skill.MANPUKU_BONUS}"
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
            text = "✨充実${Skill.JUUJITSU_COST}→みんな-${Skill.JUUJITSU_PUSH}"
            setOnClickListener { useJuujitsuSkill() }
        }
        skillRow.addView(juujitsuSkillButton, LinearLayout.LayoutParams(
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

    private fun updateSpeedLabel() {
        speedButton.text = if (Speed.fast) "はやさ: はやい⚡" else "はやさ: ふつう"
    }

    private fun updateStatsBar() {
        val me = players.firstOrNull { it.isHuman } ?: return
        val bonus = StringBuilder()
        if (me.hasChild) bonus.append("　👶x${Skill.CHILD_MULTIPLIER}")
        else if (me.married) bonus.append("　💍x${Skill.MARRIED_MULTIPLIER}")
        if (me.yuujou >= Skill.YUUJOU_THRESHOLD) bonus.append("　🤝+${Skill.YUUJOU_BONUS}")
        if (me.boostNext) bonus.append("　🍖+${Skill.MANPUKU_BONUS}")
        statsBar.text = "満腹 ${me.manpuku}　　充実 ${me.juujitsu}　　友情 ${me.yuujou}$bonus"
    }

    private fun updateSkillButtons() {
        if (!::manpukuSkillButton.isInitialized) return
        val p = players.getOrNull(turn)
        val myTurn = p != null && p.isHuman && !rouletteView.locked
        manpukuSkillButton.isEnabled = myTurn && p!!.manpuku >= Skill.MANPUKU_COST && !p.boostNext
        juujitsuSkillButton.isEnabled = myTurn && p!!.juujitsu >= Skill.JUUJITSU_COST && players.size > 1
        manpukuSkillButton.alpha = if (manpukuSkillButton.isEnabled) 1f else 0.4f
        juujitsuSkillButton.alpha = if (juujitsuSkillButton.isEnabled) 1f else 0.4f
    }

    private fun showStatusDialog() {
        val sb = StringBuilder("ステージ${stageIndex + 1}/${stages.size}「${stage.name}」\n\n")
        for (p in players.sortedByDescending { it.total }) {
            val who = if (p.isHuman) "${p.chara.name}（きみ）" else "${p.chara.name}（CPU）"
            sb.append("$who　ごうけい ${p.total}\n")
            sb.append("  マス: ${p.position} / ${Board.GOAL_INDEX}\n")
            sb.append("  満腹 ${p.manpuku}　充実 ${p.juujitsu}　友情 ${p.yuujou}\n")
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
        statusText.text = "🍖 パワーアップ！ つぎのルーレットに +${Skill.MANPUKU_BONUS}"
        updateStatsBar()
        updateSkillButtons()
    }

    private fun useJuujitsuSkill() {
        val p = players[turn]
        if (!p.isHuman || p.juujitsu < Skill.JUUJITSU_COST || players.size < 2) return
        p.juujitsu -= Skill.JUUJITSU_COST
        for (other in players) {
            if (other !== p) other.position = (other.position - Skill.JUUJITSU_PUSH).coerceAtLeast(0)
        }
        statusText.text = "✨ みんなを ${Skill.JUUJITSU_PUSH}マス もどした！"
        boardView.invalidate()
        updateStatsBar()
        updateSkillButtons()
    }

    private fun cpuUseSkills(p: Player) {
        if (p.juujitsu >= Skill.JUUJITSU_COST && players.size > 1 && Random.nextFloat() < 0.4f) {
            p.juujitsu -= Skill.JUUJITSU_COST
            for (other in players) {
                if (other !== p) other.position = (other.position - Skill.JUUJITSU_PUSH).coerceAtLeast(0)
            }
            statusText.text = "✨ ${p.chara.name} が みんなを ${Skill.JUUJITSU_PUSH}マス もどした！"
            boardView.invalidate()
            updateStatsBar()
        }
        if (p.manpuku >= Skill.MANPUKU_COST && !p.boostNext && Random.nextFloat() < 0.5f) {
            p.manpuku -= Skill.MANPUKU_COST
            p.boostNext = true
            statusText.text = "🍖 ${p.chara.name} が パワーアップ！"
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
        boardView.turnIndex = turn
        boardView.focusCell(p.position, slow = true)
        if (p.isHuman) {
            statusText.text = "きみ（${p.chara.name}）のばん！ スタートを おしてね"
            rouletteView.locked = false
            startButton.isEnabled = true
            boardView.panEnabled = true
            updateSkillButtons()
        } else {
            statusText.text = "${p.chara.name}（CPU）のばん…"
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
        if (p.yuujou >= Skill.YUUJOU_THRESHOLD) {
            steps += Skill.YUUJOU_BONUS
            extras.append(" 🤝+${Skill.YUUJOU_BONUS}")
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
        val stepRunnable = object : Runnable {
            override fun run() {
                val canMove = if (delta > 0) p.position < Board.GOAL_INDEX else p.position > 0
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

    /** そのイベントが今のプレイヤーに発生するか */
    private fun eventAvailable(p: Player, ev: GameEvent): Boolean = when (ev.kind) {
        EventKind.NORMAL -> true
        EventKind.WEDDING -> !p.married
        EventKind.BIRTH -> p.married && !p.hasChild
    }

    private fun onLanded(p: Player, fromEvent: Boolean) {
        if (p.position >= Board.GOAL_INDEX) {
            stageClear(p)
            return
        }
        val ev = stage.events[p.position]
        if (ev != null && !fromEvent && eventAvailable(p, ev)) {
            showEvent(p, ev)
            return
        }
        val mv = Board.moves[p.position]
        if (mv != 0 && !fromEvent) {
            statusText.text = if (mv > 0) "${p.chara.name} は ${mv}マス すすむ！"
                              else "${p.chara.name} は ${-mv}マス もどる…"
            handler.postDelayed({ movePiece(p, mv, fromEvent = true) }, Speed.eventWaitMs)
        } else {
            nextTurn()
        }
    }

    // ---------------- ステージクリア / 最終結果 ----------------
    private fun stageClear(winner: Player) {
        winner.juujitsu = (winner.juujitsu + Skill.CLEAR_BONUS).coerceIn(0, 999)
        winner.yuujou = (winner.yuujou + Skill.CLEAR_BONUS).coerceIn(0, 999)
        updateStatsBar()
        val who = if (winner.isHuman) "きみ（${winner.chara.name}）" else "${winner.chara.name}（CPU）"

        if (stageIndex >= stages.size - 1) {
            showFinalResult(winner)
            return
        }
        val next = stages[stageIndex + 1]
        AlertDialog.Builder(this)
            .setTitle("🎉 ステージ${stageIndex + 1} クリア！")
            .setMessage(
                "$who が 1ばんに ゴール！\n" +
                "1ちゃくボーナス 充実+${Skill.CLEAR_BONUS}　友情+${Skill.CLEAR_BONUS}\n\n" +
                "つぎは「${next.name}」！\nみんなで すすもう。\n" +
                "（ステータス・けっこん・こどもは そのまま）"
            )
            .setCancelable(false)
            .setPositiveButton("つぎのステージへ") { _, _ ->
                stageIndex++
                players.forEach { it.position = 0 }
                turn = players.indexOf(winner).coerceAtLeast(0)
                applyStage()
                showGame()
            }
            .show()
    }

    private fun showFinalResult(winner: Player) {
        val ranking = players.sortedByDescending { it.total }
        val sb = StringBuilder()
        val who = if (winner.isHuman) "きみ（${winner.chara.name}）" else "${winner.chara.name}（CPU）"
        sb.append("さいごに ゴールしたのは $who！\n\n【 ごうけいスコア 】\n")
        for ((i, p) in ranking.withIndex()) {
            val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "　" }
            val name = if (p.isHuman) "${p.chara.name}（きみ）" else p.chara.name
            val fam = when {
                p.hasChild -> " 👶"
                p.married -> " 💍"
                else -> ""
            }
            sb.append("$medal $name$fam　${p.total}\n")
            sb.append("　　満腹${p.manpuku} 充実${p.juujitsu} 友情${p.yuujou}\n")
        }
        AlertDialog.Builder(this)
            .setTitle("🏆 ぜんステージ クリア！")
            .setMessage(sb.toString().trimEnd())
            .setCancelable(false)
            .setPositiveButton("もういちど") { _, _ ->
                players.forEach {
                    it.position = 0; it.manpuku = 0; it.juujitsu = 0; it.yuujou = 0
                    it.boostNext = false; it.married = false; it.hasChild = false
                }
                stageIndex = 0
                turn = 0
                applyStage()
                showGame()
            }
            .setNegativeButton("タイトルへ") { _, _ -> showTitle() }
            .show()
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

    // ---------------- イベント表示（汎用） ----------------
    private fun showEvent(p: Player, ev: GameEvent) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            setImageResource(ev.bgRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Triple(画像res, 本人か, 家族か)
        val lineup = ArrayList<Triple<Int, Boolean, Boolean>>()
        val showPartner = p.married || ev.kind == EventKind.WEDDING
        val showChild = p.hasChild || ev.kind == EventKind.BIRTH
        val friends = charas.filter { it != p.chara }.take((ev.groupSize - 1).coerceIn(0, 3))
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
            text = ev.message
            textSize = 16f
            setTextColor(Color.parseColor("#263238"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.WHITE, Color.parseColor("#33691E"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                p.manpuku = (p.manpuku + gain(p, ev.dManpuku)).coerceIn(0, 999)
                p.juujitsu = (p.juujitsu + gain(p, ev.dJuujitsu)).coerceIn(0, 999)
                p.yuujou = (p.yuujou + gain(p, ev.dYuujou)).coerceIn(0, 999)
                if (ev.kind == EventKind.WEDDING) p.married = true
                if (ev.kind == EventKind.BIRTH) p.hasChild = true
                updateStatsBar()
                nextTurn()
            }
            .show()
    }

    // ================= 盤面 =================
    object Board {
        const val CELL_COUNT = 30
        const val GOAL_INDEX = CELL_COUNT - 1

        /** +n=nマスすすむ / -n=nマスもどる / 0=通常。ステージ切替時に applyStage() が差し替える */
        var moves: IntArray = IntArray(CELL_COUNT)
        var normalEventCells: Set<Int> = emptySet()
        var weddingCells: Set<Int> = emptySet()
        var birthCells: Set<Int> = emptySet()
    }

    class BoardView(context: Context, private val players: List<Player>) : View(context) {
        var turnIndex = 0
        var panEnabled = false

        private val bitmaps: Map<Int, Bitmap> = players.map { it.chara.resId }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val partnerBitmaps: Map<Int, Bitmap> = players.map { it.chara.partnerRes }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val childBitmaps: Map<Int, Bitmap> = players.map { it.chara.childRes }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val forest: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.bg_forest)

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
        private val fwdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#81D4FA") }
        private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF9A9A") }
        private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CE93D8") }
        private val weddingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8BBD0") }
        private val babyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFE082") }
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
                    camX = (camX - (e.x - lastTouchX)).coerceIn(0f, worldX(Board.GOAL_INDEX))
                    lastTouchX = e.x
                    invalidate()
                }
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            spacing = w / 3f
            cellR = spacing * 0.27f
            laneY = h * 0.72f
            textPaint.textSize = cellR * 0.62f
            eventTextPaint.textSize = cellR * 0.62f
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
            val scale = height.toFloat() / forest.height
            val tileW = forest.width * scale
            if (tileW <= 0f) return
            val offset = -camX * 0.25f
            val iMin = floor((0f - offset - tileW) / tileW).toInt()
            val iMax = floor((width - offset) / tileW).toInt() + 1
            for (i in iMin..iMax) {
                val left = offset + i * tileW
                val dst = RectF(left, 0f, left + tileW, height.toFloat())
                if (Math.floorMod(i, 2) == 0) {
                    canvas.drawBitmap(forest, null, dst, null)
                } else {
                    canvas.save()
                    canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
                    canvas.drawBitmap(forest, null, dst, null)
                    canvas.restore()
                }
            }
        }

        /** 出産マスは、結婚済みのプレイヤーが1人もいない間は姿を現さない */
        private fun lockedBirth(i: Int): Boolean =
            i in Board.birthCells && players.none { it.married }

        private fun cellFill(i: Int): Paint {
            val mv = Board.moves[i]
            return when {
                i == 0 -> startPaint
                i == Board.GOAL_INDEX -> goalPaint
                lockedBirth(i) -> cellPaint
                i in Board.birthCells -> babyPaint
                i in Board.weddingCells -> weddingPaint
                i in Board.normalEventCells -> eventPaint
                mv > 0 -> fwdPaint
                mv < 0 -> backPaint
                else -> cellPaint
            }
        }

        private fun drawTrack(canvas: Canvas) {
            val dx = width / 2f - camX
            canvas.save()
            canvas.translate(dx, 0f)

            val first = (((camX - width) / spacing).toInt() - 1).coerceIn(0, Board.CELL_COUNT - 1)
            val last = (((camX + width) / spacing).toInt() + 1).coerceIn(0, Board.CELL_COUNT - 1)

            canvas.drawLine(worldX(0), laneY, worldX(Board.CELL_COUNT - 1), laneY, pathPaint)

            for (i in first..last) {
                val x = worldX(i)
                val mv = Board.moves[i]
                canvas.drawCircle(x, laneY, cellR, cellFill(i))
                canvas.drawCircle(x, laneY, cellR, cellEdge)
                when {
                    i == 0 -> canvas.drawText("S", x, laneY + textPaint.textSize / 3, textPaint)
                    i == Board.GOAL_INDEX -> canvas.drawText("G", x, laneY + textPaint.textSize / 3, textPaint)
                    lockedBirth(i) -> canvas.drawText("$i", x, laneY + textPaint.textSize / 3, textPaint)
                    i in Board.birthCells -> canvas.drawText("👶", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    i in Board.weddingCells -> canvas.drawText("💒", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    i in Board.normalEventCells -> canvas.drawText("⭐", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    mv != 0 -> canvas.drawText(if (mv > 0) "+$mv" else "$mv", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
                    else -> canvas.drawText("$i", x, laneY + textPaint.textSize / 3, textPaint)
                }
            }

            val byCell = players.withIndex().groupBy { it.value.position.coerceIn(0, Board.GOAL_INDEX) }
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
            for (i in 0 until Board.CELL_COUNT) {
                val x = l + (r - l) * i / (Board.CELL_COUNT - 1)
                mapCellPaint.color = cellFill(i).color
                canvas.drawCircle(x, lineY, miniR, mapCellPaint)
            }

            val byCell = players.withIndex().groupBy { it.value.position.coerceIn(0, Board.GOAL_INDEX) }
            for ((cell, group) in byCell) {
                val x = l + (r - l) * cell / (Board.CELL_COUNT - 1)
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
                interpolator = DecelerateInterpolator(2.2f)
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
