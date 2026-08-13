package com.appathy.sugorokub

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * つうしん対戦の ロビー画面（v6.0-A）。
 *
 * ここでやるのは「2台がつながること」の確認まで。
 * キャラ選択と対戦本体は v6.1-A で載せる。
 *
 * 画面は Activity を増やさず setContentView の差し替えで作る（EditorScreens と同じ方式）。
 */
class NetLobby(
    private val act: Activity,
    private val appVersion: String,
    private val onBack: () -> Unit
) {

    private val link = NetLink()
    private var statusView: TextView? = null
    private var listBox: LinearLayout? = null
    private var pingAt = 0L

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10).toFloat()
            if (stroke != 0) setStroke(dp(2), stroke)
        }

    private fun header(text: String) = TextView(act).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.parseColor("#0D47A1"))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(10))
    }

    private fun note(text: String) = TextView(act).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor("#37474F"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBg(Color.parseColor("#E3F2FD"))
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(act).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        background = roundedBg(color)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setOnClickListener { onClick() }
    }

    private fun column(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#E8F5E9"))
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun show(root: View) {
        act.setContentView(ScrollView(act).apply { addView(root) })
    }

    private fun spaced(top: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun toast(text: String) {
        Toast.makeText(act, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 入口 ----------------

    /** ロビーのトップ。ホストになるか、あいてをさがすかを選ぶ */
    fun showTop() {
        val root = column()
        root.addView(header("📶 つうしん たいせん"))
        root.addView(
            note(
                "スマホ2台を おなじ Wi-Fi に つないでね。\n\n" +
                    "① かたほうが「まつ」を おす\n" +
                    "② もういっぽうが「さがす」を おす\n\n" +
                    "アプリの バージョンと すごろくの データが\nそろっていないと つながりません。"
            )
        )
        root.addView(
            bigButton("📡 このスマホで まつ（ホスト）", Color.parseColor("#1E88E5")) { startHost() },
            spaced(16)
        )
        root.addView(
            bigButton("🔍 あいてを さがす（ゲスト）", Color.parseColor("#43A047")) { startSearch() },
            spaced()
        )
        root.addView(
            bigButton("↩ もどる", Color.parseColor("#78909C")) { leave() },
            spaced(20)
        )
        show(root)
    }

    /** タイトルへ戻る。つながっていたら切ってから戻る */
    private fun leave() {
        link.onClosed = null
        link.close("やめました")
        onBack()
    }

    // ---------------- ホスト ----------------

    private fun startHost() {
        val root = column()
        root.addView(header("📡 あいてを まっています"))
        val ip = NetLink.localIpv4()
        root.addView(
            note(
                if (ip == null) {
                    "Wi-Fi に つながっていないようです。\nせっていを たしかめてください。"
                } else {
                    "このスマホの ばんごう\n\n$ip\n\n" +
                        "あいてが「さがす」で 見つからないときは、\nこの ばんごうを 手で いれてもらってね。"
                }
            )
        )
        val st = TextView(act).apply {
            text = "じゅんびちゅう…"
            textSize = 15f
            setTextColor(Color.parseColor("#0D47A1"))
            setPadding(dp(4), dp(14), dp(4), dp(4))
        }
        statusView = st
        root.addView(st)
        root.addView(
            bigButton("やめる", Color.parseColor("#78909C")) {
                link.onClosed = null
                link.close("やめました")
                showTop()
            },
            spaced(16)
        )
        show(root)
        wire()
        link.startHost(appVersion, NetLink.dataFingerprint(act), deviceName())
    }

    // ---------------- ゲスト ----------------

    private fun startSearch() {
        val root = column()
        root.addView(header("🔍 あいてを さがしています"))
        val st = TextView(act).apply {
            text = "さがしています…（5びょうほど）"
            textSize = 15f
            setTextColor(Color.parseColor("#0D47A1"))
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        statusView = st
        root.addView(st)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        listBox = box
        root.addView(box, spaced())
        root.addView(
            bigButton("⌨ ばんごうを 手で いれる", Color.parseColor("#5E35B1")) { showManualEntry() },
            spaced(16)
        )
        root.addView(
            bigButton("↩ もどる", Color.parseColor("#78909C")) { showTop() },
            spaced()
        )
        show(root)
        link.discover(5000) { hosts -> onHostsFound(hosts) }
    }

    private fun onHostsFound(hosts: List<Pair<String, String>>) {
        val box = listBox ?: return
        box.removeAllViews()
        if (hosts.isEmpty()) {
            statusView?.text = "見つかりませんでした。\nあいてが「まつ」を おしているか、\nおなじ Wi-Fi かを たしかめてね。"
            box.addView(
                bigButton("🔍 もういちど さがす", Color.parseColor("#43A047")) { startSearch() },
                spaced()
            )
            return
        }
        statusView?.text = "見つかりました！ タップして つなごう。"
        for (h in hosts) {
            box.addView(
                bigButton("📱 ${h.second}\n${h.first}", Color.parseColor("#1E88E5")) { connectTo(h.first) },
                spaced()
            )
        }
    }

    private fun showManualEntry() {
        val root = column()
        root.addView(header("⌨ ばんごうを いれる"))
        root.addView(note("ホストの がめんに 出ている\n4つの すうじの ならびを いれてね。\nれい: 192.168.1.5"))
        val input = EditText(act).apply {
            hint = "192.168.1.5"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 18f
        }
        root.addView(input, spaced(12))
        root.addView(
            bigButton("つなぐ", Color.parseColor("#1E88E5")) {
                val ip = input.text.toString().trim()
                if (ip.isEmpty()) toast("ばんごうを いれてね") else connectTo(ip)
            },
            spaced(12)
        )
        root.addView(
            bigButton("↩ もどる", Color.parseColor("#78909C")) { startSearch() },
            spaced()
        )
        show(root)
    }

    private fun connectTo(ip: String) {
        val root = column()
        root.addView(header("🔌 つないでいます"))
        val st = TextView(act).apply {
            text = "$ip に つないでいます…"
            textSize = 15f
            setTextColor(Color.parseColor("#0D47A1"))
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        statusView = st
        root.addView(st)
        root.addView(
            bigButton("やめる", Color.parseColor("#78909C")) {
                link.onClosed = null
                link.close("やめました")
                showTop()
            },
            spaced(16)
        )
        show(root)
        wire()
        link.join(ip, appVersion, NetLink.dataFingerprint(act), deviceName())
    }

    // ---------------- つながったあと ----------------

    private fun wire() {
        link.onStatus = { text -> statusView?.text = text }
        link.onConnected = { name -> showConnected(name) }
        link.onMessage = { o -> onMessage(o) }
        link.onClosed = { reason -> showClosed(reason) }
    }

    private fun showConnected(name: String) {
        val root = column()
        root.addView(header("✅ つながりました！"))
        root.addView(
            note(
                "あいて: $name\n" +
                    "やくわり: " + (if (link.isHost) "ホスト（おやになる がわ）" else "ゲスト") + "\n" +
                    "バージョン: $appVersion\n\n" +
                    "ここまでが v6.0-A です。\nキャラえらびと たいせん本体は つぎの ばんで つきます。"
            )
        )
        val st = TextView(act).apply {
            text = "「ためしに おくる」で 通信を たしかめられます。"
            textSize = 15f
            setTextColor(Color.parseColor("#0D47A1"))
            setPadding(dp(4), dp(14), dp(4), dp(4))
        }
        statusView = st
        root.addView(st)
        root.addView(
            bigButton("📨 ためしに おくる", Color.parseColor("#1E88E5")) { sendPing() },
            spaced(12)
        )
        root.addView(
            bigButton("きる", Color.parseColor("#78909C")) {
                link.onClosed = null
                link.close("きりました")
                showTop()
            },
            spaced()
        )
        show(root)
    }

    private fun sendPing() {
        pingAt = System.currentTimeMillis()
        val o = JSONObject()
        o.put("t", "ping")
        o.put("at", pingAt)
        link.send(o)
        statusView?.text = "おくりました。へんじを まっています…"
    }

    /** v6.0-A では ping/pong だけ。対戦の中身は v6.1-A で足す */
    private fun onMessage(o: JSONObject) {
        when (o.optString("t")) {
            "ping" -> {
                val back = JSONObject()
                back.put("t", "pong")
                back.put("at", o.optLong("at"))
                link.send(back)
                statusView?.text = "あいてから とどきました！ へんじを かえしました。"
            }
            "pong" -> {
                val ms = System.currentTimeMillis() - o.optLong("at", pingAt)
                statusView?.text = "へんじが きました！ おうふく ${ms}ミリびょう"
            }
            else -> statusView?.text = "しらない メッセージが きました: ${o.optString("t")}"
        }
    }

    private fun showClosed(reason: String) {
        val root = column()
        root.addView(header("⚠️ せつぞくが おわりました"))
        root.addView(note(reason))
        root.addView(
            bigButton("↩ もういちど", Color.parseColor("#1E88E5")) { showTop() },
            spaced(16)
        )
        root.addView(
            bigButton("タイトルへ", Color.parseColor("#78909C")) { onBack() },
            spaced()
        )
        show(root)
    }

    private fun deviceName(): String {
        val m = android.os.Build.MODEL ?: "スマホ"
        return if (m.length > 20) m.substring(0, 20) else m
    }
}
