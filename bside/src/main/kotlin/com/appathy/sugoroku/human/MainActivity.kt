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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * にんげんすごろく v1.0
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
        val choices: List<Choice>, val ch: Challenge?
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
    }

    // ---------------- 状態 ----------------

    private val handler = Handler(Looper.getMainLooper())
    private var charas: List<Chara> = ArrayList()
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

    // ---------------- ライフステージ別の画像解決 ----------------
    // images に無いステージは近いステージへフォールバック。
    // suffix は "_s"(側面) / "_b"(背面) など。存在しなければ suffix なしへ落ちる。

    private val stageKeys = listOf("baby", "kinder", "elem", "jhs", "high", "univ", "work", "senior")
    private val resCache = HashMap<String, Int>()

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

    private fun loadData() {
        val cl = ArrayList<Chara>()
        val cj = JSONObject(readAsset("charas_human.json"))
        val set = cj.getJSONObject("sets").getJSONObject("human")
        val arr = set.getJSONArray("charas")
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
        charas = cl

        val ej = JSONObject(readAsset("events_human.json"))

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
                    chal
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

        val t = label("にんげんすごろく", 34f, Color.parseColor("#3A5A40"))
        t.setTypeface(Typeface.DEFAULT_BOLD)
        root.addView(t)
        root.addView(label("しょうがっこう から こうこうまで", 15f, Color.parseColor("#6B705C")))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(0, dpi(18f), 0, dpi(8f))
        var i = 0
        while (i < 4 && i < charas.size) {
            val iv = ImageView(this)
            iv.setImageResource(charaRes(charas[i], stageKeyAt(0), ""))
            val lp = LinearLayout.LayoutParams(dpi(64f), dpi(64f))
            lp.leftMargin = dpi(4f)
            lp.rightMargin = dpi(4f)
            iv.layoutParams = lp
            row.addView(iv)
            i++
        }
        root.addView(row)

        root.addView(bigButton("はじめる") { showCountSelect() })
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

    // ---------------- 人数選択 ----------------

    private fun showCountSelect() {
        val root = column()
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))
        root.gravity = Gravity.CENTER
        root.addView(label("なんにんで あそぶ？", 24f, Color.parseColor("#3A5A40")))
        root.addView(bigButton("2にん") { totalCount = 2; showHumanSelect() })
        root.addView(bigButton("3にん") { totalCount = 3; showHumanSelect() })
        root.addView(bigButton("4にん") { totalCount = 4; showHumanSelect() })
        root.addView(bigButton("もどる") { showTitle() })
        setContentView(root)
    }

    private fun showHumanSelect() {
        val root = column()
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))
        root.gravity = Gravity.CENTER
        root.addView(label("そのうち 人が あそぶのは？", 22f, Color.parseColor("#3A5A40")))
        root.addView(label("のこりは CPU が うごきます", 14f, Color.parseColor("#6B705C")))
        var n = 1
        while (n <= totalCount) {
            val v = n
            root.addView(bigButton(v.toString() + "にん") {
                humanCount = v
                picked.clear()
                players = ArrayList()
                showCharaSelect(0)
            })
            n++
        }
        root.addView(bigButton("もどる") { showCountSelect() })
        setContentView(root)
    }

    // ---------------- キャラ選択 ----------------

    private fun showCharaSelect(index: Int) {
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
            iv.setImageResource(charaRes(c, stageKeyAt(0), ""))
            iv.layoutParams = LinearLayout.LayoutParams(dpi(84f), dpi(84f))
            item.addView(iv)
            item.addView(label(c.name, 14f, Color.parseColor("#3A3A3A")))
            if (picked.contains(idx)) {
                item.alpha = 0.25f
            } else {
                item.setOnClickListener {
                    picked.add(idx)
                    players.add(Player(c, false))
                    showCharaSelect(index + 1)
                }
            }
            row!!.addView(item)
            i++
        }
        root.addView(bigButton("さいしょから") { showTitle() })
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

    private fun startGame() {
        turn = 0
        goalCount = 0
        logs.clear()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#FFF6E5"))

        val st = TextView(this)
        st.textSize = 15f
        st.setTextColor(Color.parseColor("#2F3E46"))
        st.setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(4f))
        st.setTypeface(Typeface.DEFAULT_BOLD)
        statusText = st
        root.addView(st)

        val bv = BoardView(this)
        bv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        boardView = bv
        root.addView(bv)

        val sb = LinearLayout(this)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(2f))
        statsBox = sb
        root.addView(sb)

        val lg = TextView(this)
        lg.textSize = 13f
        lg.setTextColor(Color.parseColor("#52616B"))
        lg.setPadding(dpi(12f), dpi(2f), dpi(12f), dpi(4f))
        lg.minLines = 2
        logText = lg
        root.addView(lg)

        val bottom = FrameLayout(this)
        bottom.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpi(150f)
        )
        val rv = RouletteView(this)
        val rlp = FrameLayout.LayoutParams(dpi(140f), dpi(140f))
        rlp.gravity = Gravity.CENTER
        rv.layoutParams = rlp
        rv.onResult = { n -> onSpinResult(n) }
        roulette = rv
        bottom.addView(rv)
        root.addView(bottom)

        setContentView(root)
        buildStatsBox()
        log("ゲームスタート！")
        beginTurn()
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
            val t1 = label("", 13f, Color.parseColor("#2F3E46"))
            val t2 = label("", 11f, Color.parseColor("#52616B"))
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
            t2.text = "べ" + p.st + " う" + p.sp + " に" + p.pp + "\n¥" + p.mn
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
        statusText?.text = stageName(p.pos) + " ／ " + who + "・" + p.chara.name +
                " の ばん（" + (p.pos + 1) + " / " + cells.size + "マス）"
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
            handler.postDelayed({ nextTurn() }, 900)
            return
        }
        if (p.cpu) {
            roulette?.lock()
            handler.postDelayed({ roulette?.autoSpin() }, 800)
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

    private fun stepMove(p: Player, remain: Int) {
        if (remain <= 0 || p.pos >= cells.size - 1) {
            handler.postDelayed({ onLanded(p, true) }, 200)
            return
        }
        p.pos++
        boardView?.focus(p.pos)
        boardView?.invalidate()
        updateStatus()
        handler.postDelayed({ stepMove(p, remain - 1) }, 220)
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

    private fun message(title: String, body: String, after: () -> Unit) {
        val p = players[turn]
        if (p.cpu) {
            log("[" + title + "] " + body)
            handler.postDelayed({ after() }, 1200)
        } else {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> after() }
                .show()
        }
    }

    private fun onLanded(p: Player, allowChain: Boolean) {
        val cell = cells[p.pos]
        boardView?.invalidate()

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
                message(cell.title, c.label + "：" + c.text + "\n" + deltaText(c.d)) { afterCell(p, cell, allowChain) }
            } else {
                val b = AlertDialog.Builder(this)
                b.setTitle(cell.title)
                b.setMessage(cell.text)
                b.setCancelable(false)
                b.setPositiveButton(cell.choices[0].label) { _, _ ->
                    val c = cell.choices[0]
                    applyDelta(p, c.d)
                    message(cell.title, c.text + "\n" + deltaText(c.d)) { afterCell(p, cell, allowChain) }
                }
                b.setNegativeButton(cell.choices[1].label) { _, _ ->
                    val c = cell.choices[1]
                    applyDelta(p, c.d)
                    message(cell.title, c.text + "\n" + deltaText(c.d)) { afterCell(p, cell, allowChain) }
                }
                b.show()
            }
            return
        }

        if (cell.type == "CHALLENGE" && cell.ch != null) {
            val ch = cell.ch
            val v = statOf(p, ch.stat)
            val ok = v >= ch.need
            val d = if (ok) ch.ok else ch.ng
            applyDelta(p, d)
            val head = cell.text + "\n\n" + statLabel(ch.stat) + " " + v + " / ひつよう " + ch.need
            val body = head + "\n\n" + (if (ok) ch.okText else ch.ngText) + "\n" + deltaText(d)
            message(cell.title, body) { afterCell(p, cell, allowChain) }
            return
        }

        applyDelta(p, cell.d)
        if (cell.rest > 0) p.rest += cell.rest
        val dt = deltaText(cell.d)
        val body = if (dt.isEmpty()) cell.text else cell.text + "\n" + dt
        if (cell.type == "NORMAL" && dt.isEmpty()) {
            log("[" + cell.title + "] " + cell.text)
            handler.postDelayed({ afterCell(p, cell, allowChain) }, 700)
        } else {
            message(cell.title, body) { afterCell(p, cell, allowChain) }
        }
    }

    private fun afterCell(p: Player, cell: Cell, allowChain: Boolean) {
        if (cell.move != 0 && allowChain) {
            val to = clampPos(p.pos + cell.move)
            handler.postDelayed({ slideTo(p, to) }, 250)
            return
        }
        handler.postDelayed({ nextTurn() }, 250)
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
        handler.postDelayed({ slideTo(p, to) }, 200)
    }

    private fun statOf(p: Player, key: String): Int {
        if (key == "sp") return p.sp
        if (key == "pp") return p.pp
        if (key == "mn") return p.mn
        return p.st
    }

    private fun statLabel(key: String): String {
        if (key == "sp") return "うんどう"
        if (key == "pp") return "にんき"
        if (key == "mn") return "おこづかい"
        return "べんきょう"
    }

    // ---------------- けっか ----------------

    private fun score(p: Player): Int {
        return p.st * 3 + p.sp * 3 + p.pp * 3 + p.mn / 200
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
            col.addView(t3)
            box.addView(col)
            root.addView(box)
            rank++
        }

        root.addView(bigButton("もういちど") {
            picked.clear()
            players = ArrayList()
            showCharaSelect(0)
        })
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
            an.duration = 2100
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
            val ctr = if (spinning) "..." else if (locked) "まて" else "タップ"
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
