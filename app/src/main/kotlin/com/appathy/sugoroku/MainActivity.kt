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
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * どうぶつすごろく v2.1
 * - マスを縮小し画面に約3マス表示（spacing = 幅/3）
 * - 盤面上部にS→G全体ミニマップ（マス種別色＋全キャラ位置）
 * - 自分の番は盤面を左右スライドで前後確認可。スタートで自位置に戻ってからルーレット
 * - イベント汎用化: EVENTS マップに 背景/メッセージ/ステータス変化/登場匹数 を定義
 *   4マス目「うみ」(みんなで海へ行った。満腹+5 友情+10、3匹登場) / 6マス目「こうえん」(充実+10)
 */
class MainActivity : Activity() {

    data class Chara(val name: String, val resId: Int)
    data class Player(
        val chara: Chara, val isHuman: Boolean, var position: Int = 0,
        var manpuku: Int = 0, var juujitsu: Int = 0, var yuujou: Int = 0
    )

    /** イベント定義: 背景・メッセージ・ステータス変化・登場するどうぶつの数（本人含む） */
    data class GameEvent(
        val bgRes: Int,
        val message: String,
        val dManpuku: Int = 0,
        val dJuujitsu: Int = 0,
        val dYuujou: Int = 0,
        val groupSize: Int = 1
    )

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
            Chara("しばいぬ", R.drawable.chara_shiba),
            Chara("うさぎ", R.drawable.chara_usagi),
            Chara("いのしし", R.drawable.chara_inoshishi),
            Chara("トラ", R.drawable.chara_tora)
        )
    }

    private val events by lazy {
        mapOf(
            4 to GameEvent(
                R.drawable.bg_beach,
                "みんなで海へ行った。\n満腹5　友情10",
                dManpuku = 5, dYuujou = 10, groupSize = 3
            ),
            6 to GameEvent(
                R.drawable.bg_park,
                "今回は\n公園で楽しく遊んだ。充実が10",
                dJuujitsu = 10, groupSize = 1
            )
        )
    }

    private val players = ArrayList<Player>()
    private var turn = 0
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var boardView: BoardView
    private lateinit var rouletteView: RouletteView
    private lateinit var statusText: TextView
    private lateinit var speedButton: Button
    private lateinit var startButton: Button
    private lateinit var statsBar: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Board.eventCells = events.keys
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
            text = "もりのなかまと ゴールをめざそう！"
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

        val grid = GridLayout(this).apply {
            rowCount = 2
            columnCount = 2
        }
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
            text = "🔵 すすむ　🔴 もどる　⭐ イベント"
            textSize = 12f
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
        statsBar.text = "満腹 ${me.manpuku}　　充実 ${me.juujitsu}　　友情 ${me.yuujou}"
    }

    private fun showStatusDialog() {
        val sb = StringBuilder()
        for (p in players) {
            val who = if (p.isHuman) "${p.chara.name}（きみ）" else "${p.chara.name}（CPU）"
            sb.append("$who\n  マス: ${p.position} / ${Board.GOAL_INDEX}\n")
            sb.append("  満腹 ${p.manpuku}　充実 ${p.juujitsu}　友情 ${p.yuujou}\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle("ステータス")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("とじる", null)
            .show()
    }

    private fun onStartPressed() {
        val p = players[turn]
        if (!p.isHuman || rouletteView.locked) return
        startButton.isEnabled = false
        boardView.panEnabled = false
        // スライドで見ていた位置から自分の位置へ戻ってから回す
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
            boardView.panEnabled = true   // 前後の状況をスライドで確認できる
        } else {
            statusText.text = "${p.chara.name}（CPU）のばん…"
            rouletteView.locked = true
            startButton.isEnabled = false
            boardView.panEnabled = false
            handler.postDelayed({ rouletteView.autoSpin() }, Speed.cpuWaitMs)
        }
    }

    private fun onRouletteResult(steps: Int) {
        val p = players[turn]
        statusText.text = "${p.chara.name} は「$steps」！"
        movePiece(p, steps, fromEvent = false)
    }

    /** steps が負なら後退。fromEvent=true のときは停止後にイベントを再発動しない（連鎖なし） */
    private fun movePiece(p: Player, steps: Int, fromEvent: Boolean) {
        rouletteView.locked = true
        startButton.isEnabled = false
        boardView.panEnabled = false
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

    /** 停止マス処理 */
    private fun onLanded(p: Player, fromEvent: Boolean) {
        if (p.position >= Board.GOAL_INDEX) {
            val msg = if (p.isHuman) "きみ（${p.chara.name}）の かち！"
                      else "${p.chara.name}（CPU）が さきに ついちゃった…"
            AlertDialog.Builder(this)
                .setTitle("🎉 ゴール！")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("もういちど") { _, _ ->
                    players.forEach { it.position = 0; it.manpuku = 0; it.juujitsu = 0; it.yuujou = 0 }
                    turn = 0
                    showGame()
                }
                .setNegativeButton("タイトルへ") { _, _ -> showTitle() }
                .show()
            return
        }
        val ev = events[p.position]
        if (ev != null && !fromEvent) {
            showEvent(p, ev)
            return
        }
        val mv = Board.CELL_MOVE[p.position]
        if (mv != 0 && !fromEvent) {
            statusText.text = if (mv > 0) "${p.chara.name} は ${mv}マス すすむ！"
                              else "${p.chara.name} は ${-mv}マス もどる…"
            handler.postDelayed({ movePiece(p, mv, fromEvent = true) }, Speed.eventWaitMs)
        } else {
            nextTurn()
        }
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
        // 登場するどうぶつ: 本人＋他のキャラから (groupSize-1) 匹
        val friends = charas.filter { it != p.chara }.take((ev.groupSize - 1).coerceAtLeast(0))
        if (friends.size >= 1) {
            frame.addView(ImageView(this).apply {
                setImageResource(friends[0].resId)
                rotation = -10f
            }, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(10); bottomMargin = dp(20)
            })
        }
        frame.addView(ImageView(this).apply {
            setImageResource(p.chara.resId)
            rotation = 4f
        }, FrameLayout.LayoutParams(dp(108), dp(108),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10)
        })
        if (friends.size >= 2) {
            frame.addView(ImageView(this).apply {
                setImageResource(friends[1].resId)
                rotation = 10f
            }, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(10); bottomMargin = dp(24)
            })
        }
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
                p.manpuku = min(999, p.manpuku + ev.dManpuku)
                p.juujitsu = min(999, p.juujitsu + ev.dJuujitsu)
                p.yuujou = min(999, p.yuujou + ev.dYuujou)
                updateStatsBar()
                nextTurn()
            }
            .show()
    }

    // ================= 盤面 =================
    object Board {
        const val CELL_COUNT = 30
        const val GOAL_INDEX = CELL_COUNT - 1   // 29

        /** +n=「nマスすすむ」(青) / -n=「nマスもどる」(赤) / 0=通常。S/G/イベントマスは0 */
        val CELL_MOVE = IntArray(CELL_COUNT).apply {
            this[8] = -3
            this[10] = 2
            this[12] = 3
            this[16] = -2
            this[20] = 2
            this[24] = -4
            this[27] = -3
        }

        /** イベントマス位置（MainActivity.events のキーから設定される） */
        var eventCells: Set<Int> = emptySet()
    }

    class BoardView(context: Context, private val players: List<Player>) : View(context) {
        var turnIndex = 0
        var panEnabled = false

        private val bitmaps: Map<Int, Bitmap> = players.map { it.chara.resId }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }

        private var camX = 0f
        private var camAnim: ValueAnimator? = null
        private var spacing = 0f
        private var cellR = 0f
        private var laneY = 0f
        private var lastTouchX = 0f

        private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A5D6A7"); strokeWidth = 22f; strokeCap = Paint.Cap.ROUND
        }
        private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF8E1") }
        private val fwdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#81D4FA") }
        private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF9A9A") }
        private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CE93D8") }
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
            color = Color.parseColor("#A5D6A7"); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
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

        /** 指定マスを画面中央へ。slow=true は後方プレイヤーへゆっくり戻る演出 */
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

        /** 自分の番のあいだだけ左右スライドで前後を確認できる */
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!panEnabled) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    camAnim?.cancel()
                    lastTouchX = e.x
                }
                MotionEvent.ACTION_MOVE -> {
                    camX = (camX - (e.x - lastTouchX))
                        .coerceIn(0f, worldX(Board.GOAL_INDEX))
                    lastTouchX = e.x
                    invalidate()
                }
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            // 画面に約3マス見えるサイズ
            spacing = w / 3f
            cellR = spacing * 0.27f
            laneY = h * 0.72f
            textPaint.textSize = cellR * 0.62f
            eventTextPaint.textSize = cellR * 0.62f
            camX = worldX(players.getOrNull(turnIndex)?.position ?: 0)
        }

        override fun onDraw(canvas: Canvas) {
            if (spacing == 0f) return
            drawTrack(canvas)
            drawMiniMap(canvas)
        }

        private fun cellFill(i: Int): Paint {
            val mv = Board.CELL_MOVE[i]
            return when {
                i == 0 -> startPaint
                i == Board.GOAL_INDEX -> goalPaint
                i in Board.eventCells -> eventPaint
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
                val mv = Board.CELL_MOVE[i]
                canvas.drawCircle(x, laneY, cellR, cellFill(i))
                canvas.drawCircle(x, laneY, cellR, cellEdge)
                when {
                    i == 0 -> canvas.drawText("S", x, laneY + textPaint.textSize / 3, textPaint)
                    i == Board.GOAL_INDEX -> canvas.drawText("G", x, laneY + textPaint.textSize / 3, textPaint)
                    i in Board.eventCells -> canvas.drawText("⭐", x, laneY + eventTextPaint.textSize / 3, eventTextPaint)
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
                    val dst = RectF(
                        cx + pieceDx - s / 2, baseY - s - lift,
                        cx + pieceDx + s / 2, baseY - lift
                    )
                    canvas.drawBitmap(bmp, null, dst, null)
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

            // 全キャラの現在位置（手番キャラは大きめ）
            val byCell = players.withIndex().groupBy { it.value.position.coerceIn(0, Board.GOAL_INDEX) }
            for ((cell, group) in byCell) {
                val x = l + (r - l) * cell / (Board.CELL_COUNT - 1)
                val sorted = group.sortedBy { if (it.index == turnIndex) 1 else 0 }
                for ((slot, entry) in sorted.withIndex()) {
                    val bmp = bitmaps[entry.value.chara.resId] ?: continue
                    val isTurn = entry.index == turnIndex
                    val s = (frameB - frameT) * (if (isTurn) 0.46f else 0.34f)
                    val ddx = (slot - (sorted.size - 1) / 2f) * s * 0.35f
                    val dst = RectF(
                        x + ddx - s / 2, lineY - miniR * 1.6f - s,
                        x + ddx + s / 2, lineY - miniR * 1.6f
                    )
                    canvas.drawBitmap(bmp, null, dst, null)
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

            val path = Path().apply {
                moveTo(cx, cy - r - 6f)
                lineTo(cx - r * 0.1f, cy - r + r * 0.22f)
                lineTo(cx + r * 0.1f, cy - r + r * 0.22f)
                close()
            }
            canvas.drawPath(path, pinPaint)

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
