package com.appathy.sugoroku.human

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 人生ゲーム（にんげんすごろく）
 * どうぶつすごろく（SugorokuApp）から切り出した人間版（B面）。
 * 45マスの学園すごろく。小学校 → 中学校 → 高校 の3ステージ。
 * 依存ゼロ / XMLレイアウト不使用 / 全てプログラマティックUI。
 */
class MainActivity : Activity() {

    // ---------------- データ定義 ----------------

    class Chara(val name: String, val img: String, val images: HashMap<String, String>)

    class Delta(val st: Int, val sp: Int, val pp: Int, val mn: Int)

    class Choice(val label: String, val text: String, val d: Delta)

    class Challenge(
        val stat: String, val need: Int,
        val okText: String, val ngText: String,
        val ok: Delta, val ng: Delta
    )

    class Cell(
        val i: Int, val type: String, val title: String, val text: String,
        val d: Delta, val move: Int, val rest: Int,
        val choices: List<Choice>, val ch: Challenge?, val love: Boolean,
        val goalKey: String, val bg: String
    )

    class Stage(val key: String, val name: String, val from: Int, val to: Int)

    class Ending(val key: String, val title: String, val text: String)

    class Player(val chara: Chara, val cpu: Boolean) {
        var pos = 0
        var st = 5
        var sp = 5
        var pp = 5
        var mn = 1000
        var rest = 0
        var done = false
        var goalOrder = 0
        var crush: Chara? = null
        var partner: Chara? = null
        val goals = HashSet<String>()
        var stageWins = 0
    }

    // ---------------- 状態 ----------------

    private val handler = Handler(Looper.getMainLooper())

    // ---- テンポ（A面 SugorokuApp と同じ値）----
    object Speed {
        var fast = false
        val spinMs get() = if (fast) 1100L else 2600L
        val stepMs get() = if (fast) 140L else 300L
        val resultMs get() = if (fast) 300L else 700L
        val cpuWaitMs get() = if (fast) 400L else 1000L
        val eventWaitMs get() = if (fast) 350L else 700L
    }
    private var charas: List<Chara> = ArrayList()
    private var partners: List<Chara> = ArrayList()
    private var cells: List<Cell> = ArrayList()
    private var stages: List<Stage> = ArrayList()
    private var endings: List<Ending> = ArrayList()

    private var players: MutableList<Player> = ArrayList()
    private var turn = 0
    private var goalCount = 0

    private var totalCount = 3
    private var humanCount = 1
    private val picked = ArrayList<Int>()

    private var boardView: BoardView? = null
    private var roulette: RouletteView? = null
    private var statusText: TextView? = null
    private var logText: TextView? = null
    private var statsBox: LinearLayout? = null
    private val logs = ArrayList<String>()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dpi(v: Float): Int = dp(v).toInt()

    private fun roundedBg(fill: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(10f)
        if (stroke != 0) g.setStroke(dpi(2f), stroke)
        return g
    }

    private fun styledButton(labelText: String, size: Float, fill: Int): Button {
        val b = Button(this)
        b.text = labelText
        b.textSize = size
        b.minHeight = 0
        b.minimumHeight = 0
        b.setTextColor(Color.WHITE)
        b.background = roundedBg(fill)
        b.setPadding(dpi(8f), dpi(10f), dpi(8f), dpi(10f))
        return b
    }

    // ---------------- ライフステージ別の画像解決 ----------------
    // images に無いステージは近いステージへフォールバック。
    // suffix は "_s"(側面) / "_b"(背面) など。存在しなければ suffix なしへ落ちる。

    private val stageKeys = listOf("baby", "kinder", "elem", "jhs", "high", "univ", "work", "senior")
    private val resCache = HashMap<String, Int>()

    private var currentBg = ""

    private fun stageIndexAt(pos: Int): Int {
        var i = 0
        while (i < stages.size) {
            if (pos >= stages[i].from && pos <= stages[i].to) return i
            i++
        }
        return if (stages.isEmpty()) 0 else stages.size - 1
    }

    private fun stageKeyAt(pos: Int): String {
        var i = 0
        while (i < stages.size) {
            val s = stages[i]
            if (pos >= s.from && pos <= s.to) return s.key
            i++
        }
        if (stages.isEmpty()) return "elem"
        return stages[stages.size - 1].key
    }

    private fun imageBaseFor(c: Chara, stageKey: String): String {
        var idx = stageKeys.indexOf(stageKey)
        if (idx < 0) idx = stageKeys.size - 1
        var i = idx
        while (i >= 0) {
            val b = c.images[stageKeys[i]]
            if (b != null) return b
            i--
        }
        i = idx + 1
        while (i < stageKeys.size) {
            val b = c.images[stageKeys[i]]
            if (b != null) return b
            i++
        }
        return c.img
    }

    private fun resOf(base: String, suffix: String): Int {
        val key = base + suffix
        val hit = resCache[key]
        if (hit != null) return hit
        var id = resources.getIdentifier(key, "drawable", packageName)
        if (id == 0) id = resources.getIdentifier(base, "drawable", packageName)
        resCache[key] = id
        return id
    }

    private fun charaRes(c: Chara, stageKey: String, suffix: String): Int {
        return resOf(imageBaseFor(c, stageKey), suffix)
    }

    // タイトルとキャラ選択で見せる姿（盤面の先頭＝あかちゃんではなく高校生を出す）
    private fun previewStageKey(): String = "high"

    // ---------------- 起動 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        showTitle()
    }

    private fun readAsset(name: String): String {
        val ins = assets.open(name)
        val bytes = ins.readBytes()
        ins.close()
        return String(bytes, Charsets.UTF_8)
    }

    private fun readDelta(o: JSONObject?): Delta {
        if (o == null) return Delta(0, 0, 0, 0)
        return Delta(o.optInt("st", 0), o.optInt("sp", 0), o.optInt("pp", 0), o.optInt("mn", 0))
    }

    private fun readCharaSet(root: JSONObject, setName: String): List<Chara> {
        val cl = ArrayList<Chara>()
        val sets = root.getJSONObject("sets")
        val set = sets.optJSONObject(setName) ?: return cl
        val arr = set.optJSONArray("charas") ?: return cl
        var i = 0
        while (i < arr.length()) {
            val o = arr.getJSONObject(i)
            val img = o.getString("img")
            val map = HashMap<String, String>()
            val io = o.optJSONObject("images")
            if (io != null) {
                val ks = io.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    map[k] = io.getString(k)
                }
            }
            if (resources.getIdentifier(img, "drawable", packageName) != 0) {
                cl.add(Chara(o.getString("name"), img, map))
            }
            i++
        }
        return cl
    }

    private fun loadData() {
        val cj = JSONObject(readAsset("charas_human.json"))
        charas = readCharaSet(cj, "human")
        partners = readCharaSet(cj, "partner")

        val ej = JSONObject(readAsset("events_human.json"))
        var i = 0

        val sl = ArrayList<Stage>()
        val sarr = ej.getJSONArray("stages")
        i = 0
        while (i < sarr.length()) {
            val o = sarr.getJSONObject(i)
            sl.add(Stage(o.optString("key", "elem"), o.getString("name"), o.getInt("from"), o.getInt("to")))
            i++
        }
        stages = sl

        val list = ArrayList<Cell>()
        val carr = ej.getJSONArray("cells")
        i = 0
        while (i < carr.length()) {
            val o = carr.getJSONObject(i)
            val chs = ArrayList<Choice>()
            val ca = o.optJSONArray("choices")
            if (ca != null) {
                var k = 0
                while (k < ca.length()) {
                    val co = ca.getJSONObject(k)
                    chs.add(Choice(co.getString("label"), co.getString("text"), readDelta(co)))
                    k++
                }
            }
            var chal: Challenge? = null
            if (o.optString("type") == "CHALLENGE") {
                chal = Challenge(
                    o.optString("stat", "st"),
                    o.optInt("need", 10),
                    o.optString("okText", "せいこう！"),
                    o.optString("ngText", "しっぱい…"),
                    readDelta(o.optJSONObject("ok")),
                    readDelta(o.optJSONObject("ng"))
                )
            }
            list.add(
                Cell(
                    o.optInt("i", i),
                    o.optString("type", "NORMAL"),
                    o.optString("title", ""),
                    o.optString("text", ""),
                    readDelta(o),
                    o.optInt("move", 0),
                    o.optInt("rest", 0),
                    chs,
                    chal,
                    o.optBoolean("love", false),
                    o.optString("goal", ""),
                    o.optString("bg", "")
                )
            )
            i++
        }
        cells = list

        val el = ArrayList<Ending>()
        val earr = ej.getJSONArray("endings")
        i = 0
        while (i < earr.length()) {
            val o = earr.getJSONObject(i)
            el.add(Ending(o.getString("key"), o.getString("title"), o.getString("text")))
            i++
        }
        endings = el
    }

    // ---------------- 共通UI部品 ----------------

    private fun bigButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 18f
        b.setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(dpi(240f), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dpi(10f)
        b.layoutParams = lp
        return b
    }

    private fun label(text: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        return t
    }

    private fun column(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER_HORIZONTAL
        l.setPadding(dpi(16f), dpi(24f), dpi(16f), dpi(24f))
        return l
    }

    // ---------------- タイトル ----------------

    private fun showTitle() {
        val root = column()
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))
        root.gravity = Gravity.CENTER

        val t = label("すごろく人生ゲーム", 30f, Color.parseColor("#3A5A40"))
        t.setTypeface(Typeface.DEFAULT_BOLD)
        root.addView(t)
        root.addView(label("しょうがっこう から こうこうまで の すごろく", 15f, Color.parseColor("#6B705C")))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(0, dpi(18f), 0, dpi(8f))
        var i = 0
        while (i < 4 && i < charas.size) {
            val iv = ImageView(this)
            iv.setImageResource(charaRes(charas[i], previewStageKey(), ""))
            val lp = LinearLayout.LayoutParams(dpi(64f), dpi(64f))
            lp.leftMargin = dpi(4f)
            lp.rightMargin = dpi(4f)
            iv.layoutParams = lp
            row.addView(iv)
            i++
        }
        root.addView(row)

        root.addView(bigButton("はじめる") { showModeSelect() })
        root.addView(bigButton("あそびかた") { showHelp() })
        setContentView(root)
    }

    private fun showHelp() {
        val msg = "ルーレットを タップして すすもう。\n\n" +
                "とまった マスで イベントが おこり、\n" +
                "べんきょう / うんどう / にんき / おこづかい が かわります。\n\n" +
                "あかい マスは ちょうせん。ステータスが たりないと しっぱいします。\n" +
                "ぜんいんが ゴールしたら けっかはっぴょう。"
        AlertDialog.Builder(this).setTitle("あそびかた").setMessage(msg)
            .setPositiveButton("とじる", null).show()
    }

    // ---------------- あそびかた選択（1画面）----------------

    private fun modeButton(labelText: String, total: Int, humans: Int): Button {
        return bigButton(labelText) {
            totalCount = total
            humanCount = humans
            picked.clear()
            players = ArrayList()
            showCharaSelect(0)
        }
    }

    private fun showModeSelect() {
        val sv = ScrollView(this)
        val root = column()
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))
        root.addView(label("あそびかたを えらぼう", 24f, Color.parseColor("#3A5A40")))
        root.addView(label("えらぶと キャラせんたくに すすみます", 13f, Color.parseColor("#6B705C")))
        root.addView(modeButton("ひとり ＋ CPU 1にん", 2, 1))
        root.addView(modeButton("ひとり ＋ CPU 2にん", 3, 1))
        root.addView(modeButton("ひとり ＋ CPU 3にん", 4, 1))
        root.addView(modeButton("ふたりで あそぶ", 2, 2))
        root.addView(modeButton("ふたり ＋ CPU 2にん", 4, 2))
        root.addView(bigButton("タイトルへ") { showTitle() })
        sv.addView(root)
        setContentView(sv)
    }

    // ---------------- 紹介ムービー ----------------
    // res/raw/intro_<NN>.mp4 があればキャラ決定時に再生する。無ければ何もせず次へ進む。

    // ムービー名はキャラの基準画像名に紐づける（プレイヤーの並び順に依存させない）
    // 例: あかり(chara_kid01) → res/raw/intro_chara_kid01.mp4
    private fun introResFor(c: Chara): Int {
        return resources.getIdentifier("intro_" + c.img, "raw", packageName)
    }

    private fun playIntro(resId: Int, after: () -> Unit) {
        if (resId == 0) {
            after()
            return
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val vv = VideoView(this)
        val vlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        vlp.gravity = Gravity.CENTER
        vv.layoutParams = vlp
        root.addView(vv)

        val tip = label("タップで スキップ", 14f, Color.WHITE)
        val tlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tlp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        tlp.bottomMargin = dpi(28f)
        tip.layoutParams = tlp
        root.addView(tip)

        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                vv.stopPlayback()
                after()
            }
        }

        root.setOnClickListener { finish() }
        vv.setOnClickListener { finish() }
        tip.setOnClickListener { finish() }
        vv.setOnCompletionListener { finish() }
        vv.setOnErrorListener { _: MediaPlayer?, _: Int, _: Int ->
            finish()
            true
        }
        vv.setOnPreparedListener { mp: MediaPlayer ->
            mp.setVolume(0.7f, 0.7f)
        }
        vv.setVideoURI(Uri.parse("android.resource://" + packageName + "/" + resId))
        setContentView(root)
        vv.start()
        handler.postDelayed({ finish() }, 15000)
    }

    // ---------------- キャラ選択 ----------------

    private fun showCharaSelect(index: Int) {
        if (charas.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("キャラが よみこめません")
                .setMessage("charas_human.json と drawable を かくにんして ください。")
                .setPositiveButton("OK") { _, _ -> showTitle() }
                .show()
            return
        }
        if (humanCount > charas.size) humanCount = charas.size
        if (totalCount > charas.size) totalCount = charas.size
        if (index >= humanCount) {
            fillCpu()
            startGame()
            return
        }
        val sv = ScrollView(this)
        val root = column()
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))
        root.addView(label((index + 1).toString() + "P の キャラを えらぼう", 22f, Color.parseColor("#3A5A40")))

        var row: LinearLayout? = null
        var i = 0
        while (i < charas.size) {
            if (i % 3 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER
                root.addView(row)
            }
            val c = charas[i]
            val idx = i
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(dpi(6f), dpi(8f), dpi(6f), dpi(8f))
            val iv = ImageView(this)
            iv.setImageResource(charaRes(c, previewStageKey(), ""))
            iv.layoutParams = LinearLayout.LayoutParams(dpi(84f), dpi(84f))
            item.addView(iv)
            item.addView(label(c.name, 14f, Color.parseColor("#3A3A3A")))
            if (picked.contains(idx)) {
                item.alpha = 0.25f
            } else {
                item.setOnClickListener {
                    picked.add(idx)
                    players.add(Player(c, false))
                    playIntro(introResFor(c)) { showCharaSelect(index + 1) }
                }
            }
            row!!.addView(item)
            i++
        }
        root.addView(bigButton("もどる") { showModeSelect() })
        sv.addView(root)
        setContentView(sv)
    }

    private fun fillCpu() {
        while (players.size < totalCount) {
            var idx = Random.nextInt(charas.size)
            var guard = 0
            while (picked.contains(idx) && guard < 100) {
                idx = Random.nextInt(charas.size)
                guard++
            }
            picked.add(idx)
            players.add(Player(charas[idx], true))
        }
    }

    // ---------------- ゲーム画面 ----------------

    private var statusText2: TextView? = null
    private var speedButton: Button? = null
    private var startButton: Button? = null

    private fun updateSpeedLabel() {
        speedButton?.text = if (Speed.fast) "はやさ: はやい⚡" else "はやさ: ふつう"
        speedButton?.background = roundedBg(
            if (Speed.fast) Color.parseColor("#EF6C00") else Color.parseColor("#78909C")
        )
    }

    private fun startGame() {
        turn = 0
        goalCount = 0
        logs.clear()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))

        // 情報行（ステージ名・凡例）＋ はやさ切替
        val infoRow = LinearLayout(this)
        infoRow.orientation = LinearLayout.HORIZONTAL
        infoRow.gravity = Gravity.CENTER_VERTICAL
        infoRow.setPadding(dpi(12f), dpi(4f), dpi(12f), 0)

        val st = TextView(this)
        st.textSize = 11f
        st.setTextColor(Color.parseColor("#558B2F"))
        statusText = st
        infoRow.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sb2 = Button(this)
        sb2.textSize = 12f
        sb2.minHeight = 0
        sb2.minimumHeight = 0
        sb2.setTextColor(Color.WHITE)
        sb2.setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f))
        sb2.setOnClickListener {
            Speed.fast = !Speed.fast
            updateSpeedLabel()
        }
        speedButton = sb2
        updateSpeedLabel()
        infoRow.addView(sb2)
        root.addView(infoRow)

        // 手番の見出し
        val st2 = TextView(this)
        st2.textSize = 17f
        st2.gravity = Gravity.CENTER
        st2.setTextColor(Color.parseColor("#33691E"))
        st2.setTypeface(Typeface.DEFAULT_BOLD)
        st2.setPadding(dpi(8f), dpi(2f), dpi(8f), dpi(2f))
        statusText2 = st2
        root.addView(st2)

        val bv = BoardView(this)
        bv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        boardView = bv
        root.addView(bv)

        val lg = TextView(this)
        lg.textSize = 12f
        lg.setTextColor(Color.parseColor("#52616B"))
        lg.setPadding(dpi(12f), dpi(2f), dpi(12f), dpi(2f))
        lg.minLines = 2
        logText = lg
        root.addView(lg)

        // ルーレットとスタートボタン
        val controlRow = LinearLayout(this)
        controlRow.orientation = LinearLayout.HORIZONTAL
        controlRow.setPadding(dpi(8f), 0, dpi(8f), dpi(4f))

        val rv = RouletteView(this)
        rv.onResult = { n -> onSpinResult(n) }
        roulette = rv
        controlRow.addView(rv, LinearLayout.LayoutParams(0, dpi(200f), 1.3f))

        val buttonCol = LinearLayout(this)
        buttonCol.orientation = LinearLayout.VERTICAL
        buttonCol.gravity = Gravity.CENTER
        buttonCol.setPadding(dpi(8f), 0, dpi(4f), 0)

        val sbtn = Button(this)
        sbtn.text = "スタート！"
        sbtn.textSize = 20f
        sbtn.setTypeface(Typeface.DEFAULT_BOLD)
        sbtn.setTextColor(Color.WHITE)
        sbtn.background = roundedBg(Color.parseColor("#FF9800"))
        sbtn.setPadding(dpi(8f), dpi(20f), dpi(8f), dpi(20f))
        sbtn.setOnClickListener { roulette?.pressStart() }
        startButton = sbtn
        buttonCol.addView(sbtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val stbtn = styledButton("ステータス", 15f, Color.parseColor("#4CAF50"))
        stbtn.setOnClickListener { showStatusDialog() }
        val slp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dpi(12f)
        buttonCol.addView(stbtn, slp)

        controlRow.addView(buttonCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controlRow)

        // 下部のステータスバー
        val sb = LinearLayout(this)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.setBackgroundColor(Color.parseColor("#33691E"))
        sb.setPadding(dpi(8f), dpi(5f), dpi(8f), dpi(5f))
        statsBox = sb
        root.addView(sb)

        setContentView(root)
        buildStatsBox()
        log("ゲームスタート！")
        beginTurn()
    }

    private fun showStatusDialog() {
        val sb = StringBuilder()
        var i = 0
        while (i < players.size) {
            val p = players[i]
            val who = if (p.cpu) "（CPU）" else "（" + (i + 1) + "P）"
            sb.append(p.chara.name).append(who).append("\n")
            sb.append("  べんきょう ").append(p.st).append(" / うんどう ").append(p.sp)
            sb.append(" / にんき ").append(p.pp).append(" / ¥").append(p.mn).append("\n")
            val pt = p.partner
            if (pt != null) sb.append("  ♥ ").append(pt.name).append("\n")
            sb.append("  ").append(goalLine(p)).append("\n\n")
            i++
        }
        AlertDialog.Builder(this).setTitle("ステータス")
            .setMessage(sb.toString().trim())
            .setPositiveButton("とじる", null).show()
    }

    private fun buildStatsBox() {
        val sb = statsBox ?: return
        sb.removeAllViews()
        var i = 0
        while (i < players.size) {
            val p = players[i]
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            box.layoutParams = lp
            val t1 = label("", 12f, Color.WHITE)
            val t2 = label("", 10f, Color.parseColor("#DCEDC8"))
            box.addView(t1)
            box.addView(t2)
            box.tag = arrayOf(t1, t2)
            sb.addView(box)
            i++
        }
        updateStats()
    }

    private fun updateStats() {
        val sb = statsBox ?: return
        var i = 0
        while (i < players.size && i < sb.childCount) {
            val p = players[i]
            val box = sb.getChildAt(i) as LinearLayout
            val tags = box.tag as Array<*>
            val t1 = tags[0] as TextView
            val t2 = tags[1] as TextView
            val mark = if (i == turn) "▶ " else ""
            val cpuMark = if (p.cpu) "(CPU)" else ""
            t1.text = mark + p.chara.name + cpuMark
            t1.setTypeface(if (i == turn) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            var extra = ""
            val pt = p.partner
            val cr = p.crush
            if (pt != null) extra = "\n♥" + pt.name else if (cr != null) extra = "\n…" + cr.name
            if (p.goals.size > 0) extra = extra + "\n★" + p.goals.size
            t2.text = "べ" + p.st + " う" + p.sp + " に" + p.pp + "\n¥" + p.mn + extra
            i++
        }
    }

    private fun log(s: String) {
        logs.add(s)
        while (logs.size > 2) logs.removeAt(0)
        val lt = logText ?: return
        lt.text = logs.joinToString("\n")
    }

    private fun stageName(pos: Int): String {
        var i = 0
        while (i < stages.size) {
            val s = stages[i]
            if (pos >= s.from && pos <= s.to) return s.name
            i++
        }
        return ""
    }

    private fun updateStatus() {
        val p = players[turn]
        val who = if (p.cpu) "CPU" else (turn + 1).toString() + "P"
        val si = stageIndexAt(p.pos)
        statusText?.text = "ステージ" + (si + 1) + "/" + stages.size + "「" + stageName(p.pos) + "」\n" +
                "🟢いいこと 🟣わるいこと 🟠ワープ 🔴ちょうせん 🩷こくはく"
        statusText2?.text = who + "・" + p.chara.name + " の ばん（" + (p.pos + 1) + " / " + cells.size + "マス）"
        startButton?.isEnabled = !p.cpu
        startButton?.alpha = if (p.cpu) 0.4f else 1f
        updateStats()
    }

    // ---------------- ターン進行 ----------------

    private fun beginTurn() {
        if (goalCount >= players.size) {
            showResult()
            return
        }
        val p = players[turn]
        if (p.done) {
            nextTurn()
            return
        }
        updateStatus()
        boardView?.focus(p.pos)
        if (p.rest > 0) {
            p.rest--
            log(p.chara.name + " は おやすみ中")
            handler.postDelayed({ nextTurn() }, 600)
            return
        }
        if (p.cpu) {
            roulette?.lock()
            handler.postDelayed({ roulette?.autoSpin() }, Speed.cpuWaitMs)
        } else {
            roulette?.unlock()
        }
    }

    private fun nextTurn() {
        turn = (turn + 1) % players.size
        beginTurn()
    }

    private fun onSpinResult(n: Int) {
        val p = players[turn]
        log(p.chara.name + " は " + n + " すすむ")
        stepMove(p, n)
    }

    private fun stageLimit(pos: Int): Int {
        val si = stageIndexAt(pos)
        if (stages.isEmpty()) return cells.size - 1
        return stages[si].to
    }

    private fun stepMove(p: Player, remain: Int) {
        if (remain <= 0 || p.pos >= cells.size - 1 || p.pos >= stageLimit(p.pos)) {
            handler.postDelayed({ onLanded(p, true) }, 150)
            return
        }
        p.pos++
        boardView?.focus(p.pos)
        boardView?.invalidate()
        updateStatus()
        handler.postDelayed({ stepMove(p, remain - 1) }, Speed.stepMs)
    }

    private fun applyDelta(p: Player, d: Delta) {
        p.st += d.st
        p.sp += d.sp
        p.pp += d.pp
        p.mn += d.mn
        if (p.st < 0) p.st = 0
        if (p.sp < 0) p.sp = 0
        if (p.pp < 0) p.pp = 0
        updateStats()
    }

    private fun deltaText(d: Delta): String {
        val sb = StringBuilder()
        if (d.st != 0) sb.append("べんきょう" + signed(d.st) + " ")
        if (d.sp != 0) sb.append("うんどう" + signed(d.sp) + " ")
        if (d.pp != 0) sb.append("にんき" + signed(d.pp) + " ")
        if (d.mn != 0) sb.append("おこづかい" + signed(d.mn) + "えん")
        return sb.toString().trim()
    }

    private fun signed(v: Int): String {
        return if (v >= 0) "+" + v else v.toString()
    }

    // タップ不要でそのまま進む結果表示（トースト＋ログ）
    private fun flash(title: String, body: String, after: () -> Unit) {
        log("[" + title + "] " + body.replace("\n", " "))
        statusText2?.text = title
        if (!players[turn].cpu) {
            Toast.makeText(this, body, Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ after() }, if (players[turn].cpu) Speed.cpuWaitMs else Speed.resultMs)
    }

    private fun message(title: String, body: String, after: () -> Unit) {
        val p = players[turn]
        if (p.cpu) {
            log("[" + title + "] " + body.replace("\n", " "))
            statusText2?.text = p.chara.name + "：" + title
            handler.postDelayed({ after() }, Speed.cpuWaitMs)
            return
        }

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))

        val rid = if (currentBg.isEmpty()) 0 else resources.getIdentifier(currentBg, "drawable", packageName)
        if (rid != 0) {
            val frame = FrameLayout(this)
            val iv = ImageView(this)
            iv.setImageResource(rid)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.adjustViewBounds = true
            frame.addView(iv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

            val key = stageKeyAt(p.pos)
            val me = ImageView(this)
            me.setImageResource(charaRes(p.chara, key, ""))
            me.rotation = 3f
            val mlp = LinearLayout.LayoutParams(dpi(108f), dpi(108f))
            mlp.bottomMargin = dpi(6f)
            mlp.leftMargin = dpi(2f)
            mlp.rightMargin = dpi(2f)
            row.addView(me, mlp)

            val pt = p.partner
            val cr = p.crush
            val mate = pt ?: cr
            if (mate != null) {
                val mv = ImageView(this)
                mv.setImageResource(charaRes(mate, key, ""))
                mv.rotation = -4f
                val plp = LinearLayout.LayoutParams(dpi(100f), dpi(100f))
                plp.bottomMargin = dpi(6f)
                plp.leftMargin = dpi(2f)
                plp.rightMargin = dpi(2f)
                row.addView(mv, plp)
            }

            frame.addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            content.addView(frame)
        }

        val tv = TextView(this)
        tv.text = body
        tv.textSize = 16f
        tv.setTextColor(Color.parseColor("#263238"))
        tv.setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        tv.background = roundedBg(Color.WHITE, Color.parseColor("#33691E"))
        val tlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dpi(10f)
        content.addView(tv, tlp)

        val sv = ScrollView(this)
        sv.addView(content)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> after() }
            .show()
    }

    private fun doCrush(p: Player, cell: Cell, after: () -> Unit) {
        if (partners.isEmpty()) {
            after()
            return
        }
        val idx = ArrayList<Int>()
        var i = 0
        while (i < partners.size) {
            idx.add(i)
            i++
        }
        idx.shuffle()
        val pool = ArrayList<Chara>()
        i = 0
        while (i < idx.size && pool.size < 3) {
            pool.add(partners[idx[i]])
            i++
        }
        if (p.cpu) {
            val c = pool[Random.nextInt(pool.size)]
            p.crush = c
            updateStats()
            flash(cell.title, c.name + " が きに なるみたい") { after() }
        } else {
            val names = Array<CharSequence>(pool.size) { k -> pool[k].name }
            val b = AlertDialog.Builder(this)
            b.setTitle(cell.title + "：きに なる人は？")
            b.setCancelable(false)
            b.setItems(names) { _, which ->
                val c = pool[which]
                p.crush = c
                updateStats()
                flash(cell.title, c.name + " が きに なりはじめた") { after() }
            }
            b.show()
        }
    }

    private fun advanceStage(fromStage: Int) {
        val next = fromStage + 1
        if (next >= stages.size) return
        val to = stages[next].from
        var i = 0
        while (i < players.size) {
            players[i].pos = to
            players[i].rest = 0
            i++
        }
        boardView?.focus(to)
        boardView?.invalidate()
        updateStatus()
    }

    private fun onLanded(p: Player, allowChain: Boolean) {
        val cell = cells[p.pos]
        currentBg = cell.bg
        boardView?.invalidate()

        if (cell.type == "STAGEGOAL") {
            val si = stageIndexAt(p.pos)
            p.stageWins++
            applyDelta(p, cell.d)
            val nextName = if (si + 1 < stages.size) stages[si + 1].name else ""
            val body = cell.text + "\n\n" + p.chara.name + " が いちばんに " + stages[si].name +
                    " を ぬけた！\n\nみんなで " + nextName + " へ すすむ。"
            message(cell.title, body) {
                advanceStage(si)
                handler.postDelayed({ nextTurn() }, 250)
            }
            return
        }

        if (cell.type == "GOAL") {
            p.done = true
            goalCount++
            p.goalOrder = goalCount
            message(cell.title, cell.text + "\n\n" + goalCount + "ばんめの ゴール！") { nextTurn() }
            return
        }

        if (cell.type == "CHOICE" && cell.choices.size >= 2) {
            if (p.cpu) {
                val c = cell.choices[Random.nextInt(cell.choices.size)]
                applyDelta(p, c.d)
                flash(cell.title, c.label + "：" + c.text + "\n" + deltaText(c.d)) { afterCell(p, cell, allowChain) }
            } else {
                val b = AlertDialog.Builder(this)
                b.setTitle(cell.title)
                b.setMessage(cell.text)
                b.setCancelable(false)
                b.setPositiveButton(cell.choices[0].label) { _, _ ->
                    val c = cell.choices[0]
                    applyDelta(p, c.d)
                    flash(cell.title, c.text + "\n" + deltaText(c.d)) { afterCell(p, cell, allowChain) }
                }
                b.setNegativeButton(cell.choices[1].label) { _, _ ->
                    val c = cell.choices[1]
                    applyDelta(p, c.d)
                    flash(cell.title, c.text + "\n" + deltaText(c.d)) { afterCell(p, cell, allowChain) }
                }
                b.show()
            }
            return
        }

        if (cell.type == "AGAIN") {
            applyDelta(p, cell.d)
            val dt0 = deltaText(cell.d)
            val b0 = if (dt0.isEmpty()) cell.text else cell.text + "\n" + dt0
            message(cell.title, b0 + "\n\nもう いちど ルーレットを まわせる！") { afterCell(p, cell, allowChain) }
            return
        }

        if (cell.type == "RANDOM" && cell.choices.size >= 2) {
            val c = cell.choices[Random.nextInt(cell.choices.size)]
            applyDelta(p, c.d)
            message(cell.title, cell.text + "\n\n" + c.label + "\n" + c.text + "\n" + deltaText(c.d)) {
                afterCell(p, cell, allowChain)
            }
            return
        }

        if (cell.type == "CRUSH") {
            applyDelta(p, cell.d)
            doCrush(p, cell) { afterCell(p, cell, allowChain) }
            return
        }

        if (cell.type == "CHALLENGE" && cell.ch != null) {
            val ch = cell.ch
            if (cell.love && p.crush == null && partners.size > 0) {
                p.crush = partners[Random.nextInt(partners.size)]
            }
            val v = statOf(p, ch.stat)
            val ok = v >= ch.need
            val d = if (ok) ch.ok else ch.ng
            applyDelta(p, d)
            var who = ""
            val cr = p.crush
            if (cell.love && cr != null) {
                who = "あいて: " + cr.name + "\n\n"
                if (ok) {
                    p.partner = cr
                    updateStats()
                }
            }
            val head = who + cell.text + "\n\n" + statLabel(ch.stat) + " " + v + " / ひつよう " + ch.need
            var body = head + "\n\n" + (if (ok) ch.okText else ch.ngText) + "\n" + deltaText(d)
            if (cell.love && ok && cr != null) body = body + "\n\n" + cr.name + " と こいびとに なった！"
            if (ok && cell.goalKey.isNotEmpty() && !p.goals.contains(cell.goalKey)) {
                p.goals.add(cell.goalKey)
                updateStats()
                body = body + "\n\n★ もくひょう たっせい： " + goalLabel(cell.goalKey)
            }
            message(cell.title, body) { afterCell(p, cell, allowChain) }
            return
        }

        applyDelta(p, cell.d)
        if (cell.rest > 0) p.rest += cell.rest
        val dt = deltaText(cell.d)
        val body = if (dt.isEmpty()) cell.text else cell.text + "\n" + dt
        if (cell.type == "NORMAL" && dt.isEmpty()) {
            log("[" + cell.title + "] " + cell.text)
            handler.postDelayed({ afterCell(p, cell, allowChain) }, Speed.eventWaitMs)
        } else {
            message(cell.title, body) { afterCell(p, cell, allowChain) }
        }
    }

    private fun afterCell(p: Player, cell: Cell, allowChain: Boolean) {
        if (cell.type == "AGAIN" && allowChain && !p.done) {
            handler.postDelayed({ beginTurn() }, 250)
            return
        }
        if (cell.move != 0 && allowChain) {
            val to = clampPos(p.pos + cell.move)
            handler.postDelayed({ slideTo(p, to) }, 250)
            return
        }
        handler.postDelayed({ nextTurn() }, 200)
    }

    private fun clampPos(v: Int): Int {
        if (v < 0) return 0
        if (v > cells.size - 1) return cells.size - 1
        return v
    }

    private fun slideTo(p: Player, to: Int) {
        if (p.pos == to) {
            onLanded(p, false)
            return
        }
        p.pos += if (to > p.pos) 1 else -1
        boardView?.focus(p.pos)
        boardView?.invalidate()
        updateStatus()
        handler.postDelayed({ slideTo(p, to) }, Speed.stepMs)
    }

    private fun statOf(p: Player, key: String): Int {
        if (key == "sp") return p.sp
        if (key == "pp") return p.pp
        if (key == "mn") return p.mn
        return p.st
    }

    private val goalKeys = listOf("exam", "sports", "love")

    private fun goalLabel(key: String): String {
        if (key == "exam") return "じゅけん せいこう"
        if (key == "sports") return "たいかい ゆうしょう"
        if (key == "love") return "こいびとが できる"
        return key
    }

    private fun goalLine(p: Player): String {
        val sb = StringBuilder()
        var i = 0
        while (i < goalKeys.size) {
            val k = goalKeys[i]
            sb.append(if (p.goals.contains(k)) "★" else "☆")
            sb.append(goalLabel(k))
            if (i < goalKeys.size - 1) sb.append("　")
            i++
        }
        return sb.toString()
    }

    private fun statLabel(key: String): String {
        if (key == "sp") return "うんどう"
        if (key == "pp") return "にんき"
        if (key == "mn") return "おこづかい"
        return "べんきょう"
    }

    // ---------------- けっか ----------------

    private fun score(p: Player): Int {
        var v = p.st * 3 + p.sp * 3 + p.pp * 3 + p.mn / 200
        if (p.partner != null) v += 15
        v += p.goals.size * 20
        v += p.stageWins * 10
        return v
    }

    private fun endingOf(p: Player): Ending {
        var key = "st"
        var best = p.st
        if (p.sp > best) {
            best = p.sp
            key = "sp"
        }
        if (p.pp > best) {
            best = p.pp
            key = "pp"
        }
        if (p.mn / 200 > best) {
            key = "mn"
        }
        var i = 0
        while (i < endings.size) {
            if (endings[i].key == key) return endings[i]
            i++
        }
        return Ending("st", "そつぎょう", "おつかれさま。")
    }

    private fun showResult() {
        val sv = ScrollView(this)
        val root = column()
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))
        root.addView(label("けっかはっぴょう", 28f, Color.parseColor("#3A5A40")))

        val sorted = players.sortedByDescending { score(it) }
        var rank = 1
        for (p in sorted) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            box.setPadding(dpi(8f), dpi(10f), dpi(8f), dpi(10f))

            val iv = ImageView(this)
            iv.setImageResource(charaRes(p.chara, stageKeyAt(cells.size - 1), ""))
            iv.layoutParams = LinearLayout.LayoutParams(dpi(72f), dpi(72f))
            box.addView(iv)

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dpi(10f), 0, 0, 0)
            val e = endingOf(p)
            val t1 = label(rank.toString() + "い　" + p.chara.name + "　" + score(p) + "てん", 17f, Color.parseColor("#2F3E46"))
            t1.gravity = Gravity.LEFT
            t1.setTypeface(Typeface.DEFAULT_BOLD)
            val t2 = label(
                "べんきょう" + p.st + " / うんどう" + p.sp + " / にんき" + p.pp + " / ¥" + p.mn,
                12f, Color.parseColor("#52616B")
            )
            t2.gravity = Gravity.LEFT
            val t3 = label("【" + e.title + "】" + e.text, 13f, Color.parseColor("#6B705C"))
            t3.gravity = Gravity.LEFT
            col.addView(t1)
            col.addView(t2)
            val tg = label(goalLine(p), 12f, Color.parseColor("#8A6D3B"))
            tg.gravity = Gravity.LEFT
            col.addView(tg)
            col.addView(t3)
            val pt = p.partner
            if (pt != null) {
                val t4 = label("♥ " + pt.name + " と いっしょに あるいていく", 13f, Color.parseColor("#B5838D"))
                t4.gravity = Gravity.LEFT
                col.addView(t4)
            }
            box.addView(col)
            root.addView(box)
            rank++
        }

        root.addView(bigButton("もういちど") { showModeSelect() })
        root.addView(bigButton("タイトルへ") { showTitle() })
        sv.addView(root)
        setContentView(sv)
    }

    // ---------------- 盤面ビュー ----------------

    inner class BoardView(ctx: Context) : View(ctx) {

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmps = HashMap<Int, Bitmap>()
        private var camX = 0f
        private var targetX = 0f
        private var inited = false

        private fun cellW(): Float = dp(88f)
        private fun cellX(i: Int): Float = dp(60f) + i * cellW()
        // 奥行き（0.56=奥 〜 1.0=手前）。道が奥へ入って手前へ戻る擬似3D
        private fun depth(i: Int): Float = 0.82f + 0.18f * sin(i * 0.40f)
        private fun cellY(i: Int): Float = height * 0.60f - (1f - depth(i)) * dp(70f)
        private fun tileRx(i: Int): Float = dp(26f) * depth(i)
        private fun tileRy(i: Int): Float = dp(26f) * depth(i) * 0.45f

        private fun drawPiece(canvas: Canvas, pl: Player, index: Int) {
            val d = depth(pl.pos)
            val cx = cellX(pl.pos) + (index - (players.size - 1) / 2f) * dp(20f) * d
            val cy = cellY(pl.pos) + tileRy(pl.pos) * 0.35f
            val size = dp(58f) * d
            p.color = Color.parseColor("#40000000")
            canvas.drawOval(RectF(cx - size * 0.26f, cy - size * 0.08f, cx + size * 0.26f, cy + size * 0.08f), p)
            if (index == turn) {
                p.color = Color.parseColor("#FFE08A")
                canvas.drawOval(RectF(cx - size * 0.34f, cy - size * 0.11f, cx + size * 0.34f, cy + size * 0.11f), p)
            }
            val rid = charaRes(pl.chara, stageKeyAt(pl.pos), "_s")
            if (rid != 0) {
                val b = bmp(rid)
                val dst = RectF(cx - size / 2f, cy - size, cx + size / 2f, cy)
                canvas.drawBitmap(b, Rect(0, 0, b.width, b.height), dst, null)
            }
        }

        fun focus(pos: Int) {
            targetX = cellX(pos) - width / 2f
            val maxX = cellX(cells.size - 1) - width / 2f + dp(60f)
            if (targetX > maxX) targetX = maxX
            if (targetX < 0f) targetX = 0f
            invalidate()
        }

        private fun bmp(resId: Int): Bitmap {
            var b = bmps[resId]
            if (b == null) {
                b = BitmapFactory.decodeResource(resources, resId)
                bmps[resId] = b
            }
            return b!!
        }

        private fun cellColor(type: String): Int {
            if (type == "START") return Color.parseColor("#B7B7A4")
            if (type == "GOAL") return Color.parseColor("#F4A259")
            if (type == "GOOD") return Color.parseColor("#8FC48F")
            if (type == "BAD") return Color.parseColor("#A98BC0")
            if (type == "WARP") return Color.parseColor("#F2C14E")
            if (type == "REST") return Color.parseColor("#9FB8C8")
            if (type == "CHOICE") return Color.parseColor("#F2A6B3")
            if (type == "CHALLENGE") return Color.parseColor("#E36B6B")
            return Color.parseColor("#F5EBD8")
        }

        private fun stageIndexAt(x: Float): Int {
            val idx = ((x - dp(60f)) / cellW()).toInt()
            var i = 0
            while (i < stages.size) {
                if (idx >= stages[i].from && idx <= stages[i].to) return i
                i++
            }
            return if (idx < 0) 0 else stages.size - 1
        }

        override fun onDraw(canvas: Canvas) {
            if (!inited && players.size > 0) {
                inited = true
                camX = cellX(players[turn].pos) - width / 2f
                if (camX < 0f) camX = 0f
                targetX = camX
            }

            val si = stageIndexAt(camX + width / 2f)
            val skyTop: Int
            val skyBottom: Int
            val groundC: Int
            if (si == 0) {
                skyTop = Color.parseColor("#9BD3F0")
                skyBottom = Color.parseColor("#DFF3FB")
                groundC = Color.parseColor("#9CCB86")
            } else if (si == 1) {
                skyTop = Color.parseColor("#7FB7E8")
                skyBottom = Color.parseColor("#E8F1F8")
                groundC = Color.parseColor("#8AB68B")
            } else {
                skyTop = Color.parseColor("#F5B183")
                skyBottom = Color.parseColor("#FDE6CD")
                groundC = Color.parseColor("#A3A87C")
            }

            p.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                skyTop, skyBottom, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.shader = null

            // 遠景（パララックス）
            p.color = Color.parseColor("#CFE3D6")
            val far = camX * 0.25f
            var hx = -(far % dp(220f))
            while (hx < width + dp(220f)) {
                canvas.drawCircle(hx + dp(110f), height * 0.42f, dp(120f), p)
                hx += dp(220f)
            }

            // 校舎（中景）
            p.color = Color.parseColor("#EDE7DC")
            val mid = camX * 0.5f
            var bx = -(mid % dp(320f))
            while (bx < width + dp(320f)) {
                val top = height * 0.14f
                canvas.drawRect(bx + dp(40f), top, bx + dp(200f), height * 0.44f, p)
                p.color = Color.parseColor("#C9BCA8")
                var wy = top + dp(14f)
                while (wy < height * 0.40f) {
                    var wx = bx + dp(52f)
                    while (wx < bx + dp(190f)) {
                        canvas.drawRect(wx, wy, wx + dp(16f), wy + dp(14f), p)
                        wx += dp(28f)
                    }
                    wy += dp(26f)
                }
                p.color = Color.parseColor("#EDE7DC")
                bx += dp(320f)
            }

            // 地面
            p.color = groundC
            canvas.drawRect(0f, height * 0.44f, width.toFloat(), height.toFloat(), p)

            canvas.save()
            canvas.translate(-camX, 0f)

            // 道（奥行きのある帯）
            p.style = Paint.Style.FILL
            val road = Path()
            var i = 0
            while (i < cells.size) {
                val x = cellX(i)
                val y = cellY(i) - tileRy(i) * 2.4f
                if (i == 0) road.moveTo(x - dp(60f), y) else road.lineTo(x, y)
                i++
            }
            i = cells.size - 1
            while (i >= 0) {
                road.lineTo(cellX(i), cellY(i) + tileRy(i) * 2.4f)
                i--
            }
            road.close()
            p.color = Color.parseColor("#EFE2CB")
            canvas.drawPath(road, p)

            // 奥のマスから手前のマスへ順に描く（重なりが自然になる）
            val order = ArrayList<Int>()
            i = 0
            while (i < cells.size) {
                order.add(i)
                i++
            }
            order.sortBy { depth(it) }

            var oi = 0
            while (oi < order.size) {
                val ci = order[oi]
                val cx = cellX(ci)
                val cy = cellY(ci)
                if (cx > camX - dp(200f) && cx < camX + width + dp(200f)) {
                    val c = cells[ci]
                    val rx = tileRx(ci)
                    val ry = tileRy(ci)
                    val d = depth(ci)
                    p.color = Color.parseColor("#26000000")
                    canvas.drawOval(RectF(cx - rx, cy - ry + dp(3f), cx + rx, cy + ry + dp(3f)), p)
                    p.color = Color.parseColor("#7A6A56")
                    canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), p)
                    p.color = cellColor(c.type)
                    canvas.drawOval(RectF(cx - rx * 0.88f, cy - ry * 0.82f, cx + rx * 0.88f, cy + ry * 0.82f), p)

                    p.textAlign = Paint.Align.CENTER
                    p.color = Color.parseColor("#4A3F35")
                    p.textSize = dp(12f) * d
                    canvas.drawText((ci + 1).toString(), cx, cy + dp(4f) * d, p)
                    p.textSize = dp(11f) * d
                    p.color = Color.parseColor("#3E3A34")
                    val ttl = if (c.title.length > 7) c.title.substring(0, 7) else c.title
                    canvas.drawText(ttl, cx, cy + ry + dp(16f) * d, p)
                    p.textAlign = Paint.Align.LEFT

                    var pi = 0
                    while (pi < players.size) {
                        if (players[pi].pos == ci) drawPiece(canvas, players[pi], pi)
                        pi++
                    }
                }
                oi++
            }

            canvas.restore()

            // ミニマップ
            val mmY = height - dp(16f)
            p.color = Color.parseColor("#66FFFFFF")
            canvas.drawRoundRect(
                RectF(dp(12f), mmY - dp(10f), width - dp(12f), mmY + dp(4f)),
                dp(7f), dp(7f), p
            )
            val mmW = width - dp(24f) - dp(8f)
            i = 0
            while (i < players.size) {
                val pl = players[i]
                val ratio = pl.pos.toFloat() / (cells.size - 1).toFloat()
                p.color = playerColor(i)
                canvas.drawCircle(dp(16f) + mmW * ratio, mmY - dp(3f), dp(5f), p)
                i++
            }

            // ステージ名
            p.color = Color.parseColor("#88000000")
            canvas.drawRoundRect(RectF(dp(10f), dp(10f), dp(130f), dp(38f)), dp(8f), dp(8f), p)
            p.color = Color.WHITE
            p.textSize = dp(14f)
            canvas.drawText(stages[si].name, dp(20f), dp(29f), p)

            // カメラ追従
            val diff = targetX - camX
            if (abs(diff) > 0.5f) {
                camX += diff * 0.18f
                postInvalidateOnAnimation()
            }
        }
    }

    private fun playerColor(i: Int): Int {
        if (i == 0) return Color.parseColor("#E76F51")
        if (i == 1) return Color.parseColor("#2A9D8F")
        if (i == 2) return Color.parseColor("#457B9D")
        return Color.parseColor("#B5838D")
    }

    // ---------------- ルーレット ----------------

    inner class RouletteView(ctx: Context) : View(ctx) {

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var rot = 0f
        private var spinning = false
        private var locked = true
        private var lastResult = 0
        var onResult: (Int) -> Unit = {}

        private val colors = intArrayOf(
            Color.parseColor("#E9C46A"), Color.parseColor("#F4A261"),
            Color.parseColor("#E76F51"), Color.parseColor("#2A9D8F"),
            Color.parseColor("#8AB17D"), Color.parseColor("#6D9DC5")
        )

        fun lock() {
            locked = true
            invalidate()
        }

        fun unlock() {
            locked = false
            invalidate()
        }

        fun autoSpin() {
            locked = false
            spin()
        }

        fun pressStart() {
            if (locked || spinning) return
            spin()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN && !locked && !spinning) {
                spin()
            }
            return true
        }

        private fun spin() {
            if (spinning) return
            spinning = true
            locked = true
            val n = Random.nextInt(1, 7)
            val idx = n - 1
            val base = ((rot % 360f) + 360f) % 360f
            val want = (((270f - (idx * 60f + 30f)) % 360f) + 360f) % 360f
            var delta = want - base
            if (delta < 0f) delta += 360f
            val end = rot + 1440f + delta
            val an = ValueAnimator.ofFloat(rot, end)
            an.duration = Speed.spinMs
            an.interpolator = DecelerateInterpolator(1.8f)
            an.addUpdateListener { a ->
                rot = a.animatedValue as Float
                invalidate()
            }
            an.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    spinning = false
                    lastResult = n
                    invalidate()
                    onResult(n)
                }
            })
            an.start()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = (if (width < height) width else height) / 2f - dp(10f)

            p.color = Color.parseColor("#33000000")
            canvas.drawCircle(cx, cy + dp(3f), r + dp(4f), p)

            canvas.save()
            canvas.rotate(rot, cx, cy)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            var i = 0
            while (i < 6) {
                p.color = colors[i]
                canvas.drawArc(rect, i * 60f, 60f, true, p)
                i++
            }
            p.color = Color.WHITE
            p.textSize = r * 0.34f
            p.textAlign = Paint.Align.CENTER
            p.setTypeface(Typeface.DEFAULT_BOLD)
            i = 0
            while (i < 6) {
                val ang = Math.toRadians((i * 60f + 30f).toDouble())
                val tx = cx + (cos(ang) * r * 0.66f).toFloat()
                val ty = cy + (sin(ang) * r * 0.66f).toFloat() + p.textSize * 0.35f
                canvas.drawText((i + 1).toString(), tx, ty, p)
                i++
            }
            canvas.restore()

            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.3f, p)
            p.color = Color.parseColor("#3A5A40")
            p.textSize = dp(13f)
            val ctr = if (spinning) "..." else if (locked) "..." else "スタート"
            canvas.drawText(ctr, cx, cy + dp(5f), p)

            // 上部のポインタ
            val path = Path()
            path.moveTo(cx - dp(11f), cy - r - dp(2f))
            path.lineTo(cx + dp(11f), cy - r - dp(2f))
            path.lineTo(cx, cy - r + dp(16f))
            path.close()
            p.color = Color.parseColor("#D62828")
            canvas.drawPath(path, p)
            p.textAlign = Paint.Align.LEFT
        }
    }
}
