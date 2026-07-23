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
import android.widget.*
import kotlin.math.min
import kotlin.random.Random

/**
 * どうぶつすごろく v1.1
 * - 最大4キャラ同時プレイ（操作者1人＋残りはCPU）
 * - CPUの手番は自動でルーレットが回る
 * - ルーレット結果は中央に正立・拡大表示（回転につられない）
 *
 * フェーズ2フック:
 *   Board.CELL_TYPES … 現在は全て NORMAL。ここにイベント種別を定義する
 *   onLanded(player)  … 停止時に必ず呼ばれる。イベント処理はここに実装する
 */
class MainActivity : Activity() {

    data class Chara(val name: String, val resId: Int)
    data class Player(val chara: Chara, val isHuman: Boolean, var position: Int = 0)

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

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#33691E"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(statusText)

        rouletteView = RouletteView(this) { result -> onRouletteResult(result) }
        root.addView(rouletteView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(230)
        ))

        setContentView(root)
        startTurn()
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
            handler.postDelayed({ rouletteView.autoSpin() }, 1000)
        }
    }

    private fun onRouletteResult(steps: Int) {
        val p = players[turn]
        statusText.text = "${p.chara.name} は「$steps」！"
        movePiece(p, steps)
    }

    private fun movePiece(p: Player, steps: Int) {
        rouletteView.locked = true
        var remaining = steps
        val stepRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0 && p.position < Board.GOAL_INDEX) {
                    p.position++
                    boardView.invalidate()
                    remaining--
                    handler.postDelayed(this, 300)
                } else {
                    onLanded(p)
                }
            }
        }
        handler.postDelayed(stepRunnable, 300)
    }

    /**
     * 停止マス処理（フェーズ2のイベントはここに実装）
     * 現在はゴール判定のみ。
     */
    private fun onLanded(p: Player) {
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
        } else {
            // フェーズ2: Board.CELL_TYPES[p.position] に応じてイベント分岐
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

        enum class CellType { NORMAL /* フェーズ2で追加: ITEM, LOSE_TURN, MOVE, EVENT ... */ }

        val CELL_TYPES = Array(CELL_COUNT) { CellType.NORMAL }
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
        private val cellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8D6E63"); style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#81C784") }
        private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB74D") }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5D4037"); textAlign = Paint.Align.CENTER
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            centers.clear()
            val pad = w * 0.06f
            val cw = (w - pad * 2) / Board.COLS
            val ch = (h - pad * 2) / Board.ROWS
            cellR = min(cw, ch) * 0.38f
            textPaint.textSize = cellR * 0.6f
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
                val fill = when (i) {
                    0 -> startPaint
                    Board.GOAL_INDEX -> goalPaint
                    else -> cellPaint
                }
                canvas.drawCircle(p.x, p.y, cellR, fill)
                canvas.drawCircle(p.x, p.y, cellR, cellEdge)
                val label = when (i) {
                    0 -> "S"
                    Board.GOAL_INDEX -> "G"
                    else -> "$i"
                }
                canvas.drawText(label, p.x, p.y + textPaint.textSize / 3, textPaint)
            }
            // 駒：同じマスに複数いるときは横にずらして描画。手番の駒は大きく＆最後（最前面）に描く
            val byCell = players.withIndex().groupBy { it.value.position.coerceIn(0, Board.GOAL_INDEX) }
            for ((cell, group) in byCell) {
                val c = centers[cell]
                val sorted = group.sortedBy { if (it.index == turnIndex) 1 else 0 }
                for ((slot, entry) in sorted.withIndex()) {
                    val isTurn = entry.index == turnIndex
                    val bmp = bitmaps[entry.value.chara.resId] ?: continue
                    val s = cellR * (if (isTurn) 2.4f else 1.7f)
                    val dx = (slot - (sorted.size - 1) / 2f) * cellR * 0.7f
                    val dst = RectF(
                        c.x + dx - s / 2, c.y - s * 0.95f,
                        c.x + dx + s / 2, c.y + s * 0.05f
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

        // 結果表示の拡大アニメ用スケール
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
            val to = from - (from % 360f) + 360f * (5 + Random.nextInt(3)) + (330f - (result - 1) * 60f)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600
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
                        postDelayed({ onResult(result) }, 700)
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
