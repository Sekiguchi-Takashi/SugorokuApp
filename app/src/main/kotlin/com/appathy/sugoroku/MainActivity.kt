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
 * どうぶつすごろく v1.0（フェーズ1）
 * - タイトル → キャラ選択 → すごろく盤面
 * - ルーレット(1〜6)をタップで回転 → 駒が1マスずつ進む → ゴール判定
 * - UIは全てプログラマティック（XMLレイアウト不使用・外部依存ゼロ）
 *
 * フェーズ2フック:
 *   Board.CELL_TYPES … 現在は全て NORMAL。ここにイベント種別を定義する
 *   onLanded(index)  … 停止時に必ず呼ばれる。イベント処理はここに実装する
 */
class MainActivity : Activity() {

    // ---- キャラ定義 ----
    data class Chara(val name: String, val resId: Int)

    private val charas by lazy {
        listOf(
            Chara("しばいぬ", R.drawable.chara_shiba),
            Chara("うさぎ", R.drawable.chara_usagi),
            Chara("いのしし", R.drawable.chara_inoshishi),
            Chara("トラ", R.drawable.chara_tora)
        )
    }

    private var selected: Chara? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var boardView: BoardView
    private lateinit var rouletteView: RouletteView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTitle()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- タイトル画面 ----------------
    private fun showTitle() {
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "キャラクターを えらんでね"
            textSize = 22f
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
                setOnClickListener {
                    selected = c
                    showGame()
                }
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

    // ---------------- ゲーム画面 ----------------
    private fun showGame() {
        val chara = selected ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
        }

        boardView = BoardView(this, chara.resId)
        root.addView(boardView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        statusText = TextView(this).apply {
            text = "${chara.name} の ぼうけん！ ルーレットを タップしてね"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#33691E"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(statusText)

        rouletteView = RouletteView(this) { result -> onRouletteResult(result) }
        root.addView(rouletteView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
        ))

        setContentView(root)
    }

    private fun onRouletteResult(steps: Int) {
        statusText.text = "「$steps」が でたよ！"
        movePiece(steps)
    }

    private fun movePiece(steps: Int) {
        rouletteView.locked = true
        var remaining = steps
        val stepRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0 && boardView.position < Board.GOAL_INDEX) {
                    boardView.position++
                    boardView.invalidate()
                    remaining--
                    handler.postDelayed(this, 300)
                } else {
                    onLanded(boardView.position)
                }
            }
        }
        handler.postDelayed(stepRunnable, 300)
    }

    /**
     * 停止マス処理（フェーズ2のイベントはここに実装）
     * 現在はゴール判定のみ。
     */
    private fun onLanded(index: Int) {
        if (index >= Board.GOAL_INDEX) {
            AlertDialog.Builder(this)
                .setTitle("🎉 ゴール！")
                .setMessage("${selected?.name} は もりのおくに たどりついた！")
                .setCancelable(false)
                .setPositiveButton("もういちど") { _, _ -> showCharaSelect() }
                .setNegativeButton("タイトルへ") { _, _ -> showTitle() }
                .show()
        } else {
            // フェーズ2: Board.CELL_TYPES[index] に応じてイベント分岐
            statusText.text = "つぎの ルーレットを まわそう！"
            rouletteView.locked = false
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

    class BoardView(context: Context, pieceRes: Int) : View(context) {
        var position = 0

        private val piece: Bitmap = BitmapFactory.decodeResource(resources, pieceRes)
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
            // 駒
            val p = centers[position.coerceIn(0, Board.GOAL_INDEX)]
            val s = cellR * 2.4f
            val dst = RectF(p.x - s / 2, p.y - s * 0.95f, p.x + s / 2, p.y + s * 0.05f)
            canvas.drawBitmap(piece, null, dst, null)
        }
    }

    // ================= ルーレット =================
    class RouletteView(context: Context, private val onResult: (Int) -> Unit) : View(context) {
        var locked = false
        private var rotation2 = 0f
        private var spinning = false

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
        private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F") }

        init {
            setOnClickListener { spin() }
        }

        private fun spin() {
            if (spinning || locked) return
            spinning = true
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
                        onResult(result)
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

            // 中央ボタン風
            arcPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.22f, arcPaint)
            numPaint.textSize = r * 0.16f
            canvas.drawText(if (spinning) "..." else "TAP", cx, cy + numPaint.textSize / 3, numPaint)

            // 針（真上）
            val path = Path().apply {
                moveTo(cx, cy - r - 6f)
                lineTo(cx - r * 0.1f, cy - r + r * 0.22f)
                lineTo(cx + r * 0.1f, cy - r + r * 0.22f)
                close()
            }
            canvas.drawPath(path, pinPaint)
        }
    }
}
