package com.appathy.sugoroku

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * どうぶつすごろく v1.2
 * - 駒の立体表現（足元の楕円影＋手番キャラのジャンプアニメ）
 * - スピード切替（ふつう/はやい）: ルーレット回転・移動・待ち時間すべて短縮
 * - イベントマス第一弾: 「nマスすすむ(青)」「nマスもどる(赤)」を盤面に配置
 *
 * フェーズ2残り: ITEM(どんぐり), LOSE_TURN(1回休み), EVENT(選択肢) を
 * Board.CELL_MOVE と同様の仕組みで追加し onLanded() で分岐する
 */
class MainActivity : Activity() {

    data class Chara(val name: String, val resId: Int)
    data class Player(val chara: Chara, val isHuman: Boolean, var position: Int = 0)

    /** スピード設定（はやい=true） */
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

    private val players = ArrayList<Player>()
    private var turn = 0
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var boardView: BoardView
    private lateinit var rouletteView: RouletteView
    private lateinit var statusText: TextView
    private lateinit var speedButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTitle()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
                setBackgroundColor(Color.WHITE)
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

        // 凡例＋スピード切替の行
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        infoRow.addView(TextView(this).apply {
            text = "🔵 すすむ　🔴 もどる"
            textSize = 13f
            setTextColor(Color.parseColor("#558B2F"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedButton = Button(this).apply {
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            updateSpeedLabel()
            setOnClickListener {
                Speed.fast = !Speed.fast
                updateSpeedLabel()
            }
        }
        infoRow.addView(speedButton)
        root.addView(infoRow)

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#33691E"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(statusText)

        rouletteView = RouletteView(this) { result -> onRouletteResult(result) }
        root.addView(rouletteView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(230)
        ))

        setContentView(root)
        startTurn()
    }

    private fun updateSpeedLabel() {
        speedButton.text = if (Speed.fast) "はやさ: はやい⚡" else "はやさ: ふつう"
    }

    private fun startTurn() {
        val p = players[turn]
        boardView.turnIndex = turn
        boardView.invalidate()
        if (p.isHuman) {
            statusText.text = "きみ（${p.chara.name}）のばん！ ルーレットを タップ"
            rouletteView.locked = false
        } else {
            statusText.text = "${p.chara.name}（CPU）のばん…"
            rouletteView.locked = true
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
        val delta = if (steps > 0) 1 else -1
        var remaining = abs(steps)
        val stepRunnable = object : Runnable {
            override fun run() {
                val canMove = if (delta > 0) p.position < Board.GOAL_INDEX else p.position > 0
                if (remaining > 0 && canMove) {
                    p.position += delta
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

    /** 停止マス処理。フェーズ2の追加イベント（どんぐり等）もここに実装する */
    private fun onLanded(p: Player, fromEvent: Boolean) {
        if (p.position >= Board.GOAL_INDEX) {
            val msg = if (p.isHuman) "きみ（${p.chara.name}）の かち！"
                      else "${p.chara.name}（CPU）が さきに ついちゃった…"
            AlertDialog.Builder(this)
                .setTitle("🎉 ゴール！")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("もういちど") { _, _ ->
                    players.forEach { it.position = 0 }
                    turn = 0
                    showGame()
                }
                .setNegativeButton("タイトルへ") { _, _ -> showTitle() }
                .show()
            return
        }
        val mv = Board.CELL_MOVE[p.position]
        if (mv != 0 && !fromEvent) {
            statusText.text = if (mv > 0) "${p.chara.name} は ${mv}マス すすむ！"
                              else "${p.chara.name} は ${-mv}マス もどる…"
            handler.postDelayed({ movePiece(p, mv, fromEvent = true) }, Speed.eventWaitMs)
        } else {
            turn = (turn + 1) % players.size
            startTurn()
        }
    }

    // ================= 盤面 =================
    object Board {
        const val COLS = 5
        const val ROWS = 6
        const val CELL_COUNT = COLS * ROWS      // 30マス
        const val GOAL_INDEX = CELL_COUNT - 1   // 29

        /**
         * イベントマス: 値が +n なら「nマスすすむ」、-n なら「nマスもどる」、0は通常マス。
         * S(0)とG(29)は必ず0にすること。連鎖なし仕様なので隣接配置もループしない。
         */
        val CELL_MOVE = IntArray(CELL_COUNT).apply {
            this[4] = 2
            this[8] = -3
            this[12] = 3
            this[16] = -2
            this[20] = 2
            this[24] = -4
            this[27] = -3
        }
    }

    class BoardView(context: Context, private val players: List<Player>) : View(context) {
        var turnIndex = 0

        private val bitmaps: Map<Int, Bitmap> = players.map { it.chara.resId }.distinct()
            .associateWith { BitmapFactory.decodeResource(resources, it) }
        private val centers = ArrayList<PointF>(Board.CELL_COUNT)
        private var cellR = 0f

        private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A5D6A7"); strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
        }
        private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF8E1") }
        private val fwdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#81D4FA") }
        private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF9A9A") }
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

        // 手番キャラのジャンプ用（立体感の演出）
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
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            centers.clear()
            val pad = w * 0.06f
            val cw = (w - pad * 2) / Board.COLS
            val ch = (h - pad * 2) / Board.ROWS
            cellR = min(cw, ch) * 0.38f
            textPaint.textSize = cellR * 0.6f
            eventTextPaint.textSize = cellR * 0.65f
            // 下の行からスタートし、蛇行（サーペンタイン）で上へ
            for (i in 0 until Board.CELL_COUNT) {
                val row = i / Board.COLS
                val colInRow = i % Board.COLS
                val col = if (row % 2 == 0) colInRow else Board.COLS - 1 - colInRow
                val x = pad + cw * col + cw / 2
                val y = h - pad - ch * row - ch / 2
                centers.add(PointF(x, y))
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (centers.isEmpty()) return
            for (i in 0 until centers.size - 1) {
                canvas.drawLine(centers[i].x, centers[i].y, centers[i + 1].x, centers[i + 1].y, pathPaint)
            }
            for (i in centers.indices) {
                val p = centers[i]
                val mv = Board.CELL_MOVE[i]
                val fill = when {
                    i == 0 -> startPaint
                    i == Board.GOAL_INDEX -> goalPaint
                    mv > 0 -> fwdPaint
                    mv < 0 -> backPaint
                    else -> cellPaint
                }
                canvas.drawCircle(p.x, p.y, cellR, fill)
                canvas.drawCircle(p.x, p.y, cellR, cellEdge)
                when {
                    i == 0 -> canvas.drawText("S", p.x, p.y + textPaint.textSize / 3, textPaint)
                    i == Board.GOAL_INDEX -> canvas.drawText("G", p.x, p.y + textPaint.textSize / 3, textPaint)
                    mv != 0 -> {
                        val label = if (mv > 0) "+$mv" else "$mv"
                        canvas.drawText(label, p.x, p.y + eventTextPaint.textSize / 3, eventTextPaint)
                    }
                    else -> canvas.drawText("$i", p.x, p.y + textPaint.textSize / 3, textPaint)
                }
            }
            // 駒：同じマスに複数いるときは横にずらす。手番の駒は大きく＆ジャンプ＆最前面
            val byCell = players.withIndex().groupBy { it.value.position.coerceIn(0, Board.GOAL_INDEX) }
            for ((cell, group) in byCell) {
                val c = centers[cell]
                val sorted = group.sortedBy { if (it.index == turnIndex) 1 else 0 }
                for ((slot, entry) in sorted.withIndex()) {
                    val isTurn = entry.index == turnIndex
                    val bmp = bitmaps[entry.value.chara.resId] ?: continue
                    val s = cellR * (if (isTurn) 2.4f else 1.7f)
                    val dx = (slot - (sorted.size - 1) / 2f) * cellR * 0.7f
                    // 立体感: 手番キャラは上下にふわっとジャンプ、足元に楕円の影
                    val lift = if (isTurn) (sin(bounce) * 0.5f + 0.5f) * cellR * 0.4f else 0f
                    val shadowScale = 1f - (lift / (cellR * 0.4f)) * 0.35f
                    val shadowW = s * 0.42f * shadowScale
                    val shadowH = s * 0.13f * shadowScale
                    canvas.drawOval(
                        c.x + dx - shadowW, c.y + s * 0.02f - shadowH,
                        c.x + dx + shadowW, c.y + s * 0.02f + shadowH,
                        shadowPaint
                    )
                    val dst = RectF(
                        c.x + dx - s / 2, c.y - s * 0.95f - lift,
                        c.x + dx + s / 2, c.y + s * 0.05f - lift
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

        /** CPU手番用：ロック状態でも回す */
        fun autoSpin() = spin()

        private fun spin() {
            if (spinning) return
            spinning = true
            resultNum = null
            val result = Random.nextInt(1, 7)
            // 針(真上)に result のセグメント中心が来る回転角:
            //   セグメントkの中心は上から時計回りに (k-1)*60+30 度 → 330-(k-1)*60 度回すと針の位置に来る
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
            // ポンッと拡大して表示
            ValueAnimator.ofFloat(0.3f, 1.15f, 1f).apply {
                duration = 350
                addUpdateListener {
                    resultScale = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        // 大きな数字を見せてから結果を通知
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
                // セグメントiの中心 = 上(-90°)から時計回りに i*60+30
                canvas.drawArc(rect, -90f + i * 60f, 60f, true, arcPaint)
                canvas.drawArc(rect, -90f + i * 60f, 60f, true, edgePaint)
                val ang = Math.toRadians((-90 + i * 60 + 30).toDouble())
                val tx = cx + (r * 0.62f) * Math.cos(ang).toFloat()
                val ty = cy + (r * 0.62f) * Math.sin(ang).toFloat() + numPaint.textSize / 3
                canvas.drawText("${i + 1}", tx, ty, numPaint)
            }
            canvas.restore()

            // 針（真上）
            val path = Path().apply {
                moveTo(cx, cy - r - 6f)
                lineTo(cx - r * 0.1f, cy - r + r * 0.22f)
                lineTo(cx + r * 0.1f, cy - r + r * 0.22f)
                close()
            }
            canvas.drawPath(path, pinPaint)

            val res = resultNum
            if (res != null) {
                // 出た数字を中央に正立・特大で表示（盤の回転につられない）
                val br = r * 0.46f * resultScale
                canvas.drawCircle(cx, cy, br, resultBgPaint)
                canvas.drawCircle(cx, cy, br, resultEdgePaint)
                resultPaint.textSize = br * 1.2f
                canvas.drawText("$res", cx, cy + resultPaint.textSize * 0.36f, resultPaint)
            } else {
                // 中央ボタン風
                arcPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r * 0.22f, arcPaint)
                numPaint.textSize = r * 0.16f
                canvas.drawText(if (spinning) "..." else "TAP", cx, cy + numPaint.textSize / 3, numPaint)
            }
        }
    }
}
