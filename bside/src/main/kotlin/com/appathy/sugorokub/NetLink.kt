package com.appathy.sugorokub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

/**
 * つうしん対戦の土台（v6.0-A では「つながるところまで」）。
 *
 * 方針:
 * - 外部ライブラリは使わない。java.net の生ソケットだけで組む
 * - 1行1JSON（改行区切り）の TCP。画面は共有せず、操作と結果だけを流す
 * - ホスト機が権威。乱数はホストが決めて配る（v6.1で本実装）
 * - 送受信はワーカースレッド。コールバックは必ずUIスレッドへ戻す
 *
 * つなぎかた:
 * - ホスト: startHost() → ServerSocket で待ち、UDPの「さがして」にも返事する
 * - ゲスト: discover() でホストのIPを見つける → join(ip)
 *
 * 接続直後に hello / welcome を交換し、プロトコル番号・アプリのバージョン・
 * JSONデータの指紋が食い違ったら つながない（イベントエディタで
 * データを書き換えられるため、ここを見ないと片方だけ違う結果になる）。
 */
class NetLink {

    companion object {
        private const val TAG = "NetLink"

        /** 対戦用のTCPポート */
        const val TCP_PORT = 47821

        /** ホストさがし用のUDPポート */
        const val UDP_PORT = 47822

        /** プロトコル番号。通信の形をかえたら必ず上げる */
        const val PROTOCOL = 1

        private const val FIND = "sugoroku-find?"
        private const val HERE = "sugoroku-here!"

        /** 同じWi-Fi内での自分のIPv4アドレス。見つからなければ null */
        fun localIpv4(): String? {
            try {
                val list = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (ni in list) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in Collections.list(ni.inetAddresses)) {
                        if (addr.isLoopbackAddress) continue
                        val ip = addr.hostAddress ?: continue
                        if (ip.contains(":")) continue     // IPv6は使わない
                        return ip
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "IPアドレスの取得に失敗", e)
            }
            return null
        }

        /**
         * 遊ぶデータの指紋。エディタで書き換えた側とは つながらないようにする。
         * ファイルが無い場合は空文字あつかいで、A面とB面でも値がちがう。
         */
        fun dataFingerprint(context: Context): String {
            var h = 1125899906842597L
            for (name in listOf("stages.json", "school_stages.json", "cave.json", "jobs.json")) {
                val s = GameData.rawJson(context, name) ?: ""
                for (ch in s) h = h * 31 + ch.code
                h = h * 31 + name.length
            }
            return java.lang.Long.toHexString(h)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val outbox = LinkedBlockingQueue<String>()

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var udp: DatagramSocket? = null

    @Volatile private var running = false
    @Volatile private var closedReported = false

    /** ホスト側か */
    @Volatile var isHost = false
        private set

    /** ハンドシェイクまで終わってつながっているか */
    @Volatile var connected = false
        private set

    /** あいての端末名（welcome/hello で受け取る） */
    @Volatile var peerName: String = ""
        private set

    // コールバックはすべてUIスレッドで呼ばれる
    var onStatus: ((String) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null
    var onMessage: ((JSONObject) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null

    private var myVersion = ""
    private var myFingerprint = ""
    private var myName = ""

    // ---------------- ホスト ----------------

    /** ホストとして待ちうける。すでに動いていたら何もしない */
    fun startHost(version: String, fingerprint: String, name: String) {
        if (running) return
        myVersion = version
        myFingerprint = fingerprint
        myName = name
        isHost = true
        running = true
        closedReported = false
        startUdpResponder()
        Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(TCP_PORT))
                server = ss
                post { onStatus?.invoke("あいての せつぞくを まっています…") }
                val s = ss.accept()
                s.tcpNoDelay = true
                socket = s
                serve(s, asHost = true)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "ホストの待ちうけに失敗", e)
                    fail("まちうけに しっぱいしました（${e.javaClass.simpleName}）")
                }
            }
        }, "net-host").start()
    }

    /** UDPの「さがして」に自分のIPを返す。ホストのあいだ動き続ける */
    private fun startUdpResponder() {
        Thread({
            try {
                val ds = DatagramSocket(null)
                ds.reuseAddress = true
                ds.broadcast = true
                ds.bind(InetSocketAddress(UDP_PORT))
                ds.soTimeout = 1000
                udp = ds
                val buf = ByteArray(64)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        ds.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (text.startsWith(FIND)) {
                        val reply = (HERE + myName).toByteArray(Charsets.UTF_8)
                        ds.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP応答スレッドを終了", e)
            } finally {
                try {
                    udp?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "UDPを閉じられませんでした", e)
                }
                udp = null
            }
        }, "net-udp").start()
    }

    // ---------------- ゲスト ----------------

    /**
     * 同じWi-Fi内のホストをさがす。timeoutMs だけ待ってから結果を返す。
     * 返るのは「IPアドレス と 端末名」の組。
     */
    fun discover(timeoutMs: Int, onFound: (List<Pair<String, String>>) -> Unit) {
        Thread({
            val found = LinkedHashMap<String, String>()
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket()
                ds.broadcast = true
                ds.soTimeout = 400
                val msg = FIND.toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName("255.255.255.255")
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                val buf = ByteArray(64)
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 700) {
                        ds.send(DatagramPacket(msg, msg.size, target, UDP_PORT))
                        lastSend = now
                    }
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        ds.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (text.startsWith(HERE)) {
                        val ip = pkt.address.hostAddress ?: continue
                        found[ip] = text.substring(HERE.length).ifBlank { ip }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ホストさがしに失敗", e)
            } finally {
                try {
                    ds?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "UDPを閉じられませんでした", e)
                }
            }
            val list = found.map { Pair(it.key, it.value) }
            post { onFound(list) }
        }, "net-discover").start()
    }

    /** ホストのIPを指定してつなぐ */
    fun join(ip: String, version: String, fingerprint: String, name: String) {
        if (running) return
        myVersion = version
        myFingerprint = fingerprint
        myName = name
        isHost = false
        running = true
        closedReported = false
        Thread({
            try {
                post { onStatus?.invoke("$ip に つないでいます…") }
                val s = Socket()
                s.connect(InetSocketAddress(ip, TCP_PORT), 5000)
                s.tcpNoDelay = true
                socket = s
                serve(s, asHost = false)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "接続に失敗", e)
                    fail("つながりませんでした。IPと Wi-Fi を たしかめてください")
                }
            }
        }, "net-join").start()
    }

    // ---------------- 送受信 ----------------

    /**
     * 1本のソケットを受けもつ。ハンドシェイクを済ませてから読み取りループに入る。
     * 書き込みは別スレッド（送信中に受信が止まらないようにするため）。
     */
    private fun serve(s: Socket, asHost: Boolean) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
        outbox.clear()
        Thread({
            try {
                while (running) {
                    val line = outbox.take()
                    if (line.isEmpty()) break
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "送信スレッドを終了", e)
            }
        }, "net-send").start()

        val hello = JSONObject()
        hello.put("t", if (asHost) "welcome" else "hello")
        hello.put("proto", PROTOCOL)
        hello.put("ver", myVersion)
        hello.put("data", myFingerprint)
        hello.put("name", myName)

        if (!asHost) outbox.put(hello.toString())

        val firstLine = reader.readLine()
        if (firstLine == null) {
            fail("あいてが きれました")
            return
        }
        val first = try {
            JSONObject(firstLine)
        } catch (e: Exception) {
            Log.w(TAG, "最初の1行を読めませんでした", e)
            fail("あいてから へんな へんじが きました")
            return
        }
        if (first.optString("t") == "reject") {
            fail(first.optString("reason", "つなげませんでした"))
            return
        }
        val ng = mismatchReason(first)
        if (ng != null) {
            val rej = JSONObject()
            rej.put("t", "reject")
            rej.put("reason", ng)
            outbox.put(rej.toString())
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Log.w(TAG, "待機を中断されました", e)
            }
            fail(ng)
            return
        }
        if (asHost) outbox.put(hello.toString())

        peerName = first.optString("name", "あいて")
        connected = true
        post { onConnected?.invoke(peerName) }

        try {
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    Log.w(TAG, "読めない行を とばしました", e)
                    continue
                }
                post { onMessage?.invoke(o) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "受信が とまりました", e)
        }
        fail("あいてが きれました")
    }

    /** つなげない理由。問題なければ null */
    private fun mismatchReason(o: JSONObject): String? {
        if (o.optInt("proto", -1) != PROTOCOL) {
            return "アプリの バージョンが ちがいます（つうしんの かたち）"
        }
        if (o.optString("ver") != myVersion) {
            return "アプリの バージョンが ちがいます\nこちら ${myVersion} / あいて ${o.optString("ver")}"
        }
        if (o.optString("data") != myFingerprint) {
            return "すごろくの データが ちがいます\nエディタの へんこうを どちらかに そろえてください"
        }
        return null
    }

    /** 1行送る。つながっていなければ捨てる */
    fun send(o: JSONObject) {
        if (!running) return
        outbox.offer(o.toString())
    }

    /** 通信を終える。理由は画面に出す文言 */
    fun close(reason: String) {
        if (!running) return
        running = false
        connected = false
        outbox.offer("")
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "ソケットを閉じられませんでした", e)
        }
        try {
            server?.close()
        } catch (e: Exception) {
            Log.w(TAG, "サーバソケットを閉じられませんでした", e)
        }
        try {
            udp?.close()
        } catch (e: Exception) {
            Log.w(TAG, "UDPを閉じられませんでした", e)
        }
        socket = null
        server = null
        report(reason)
    }

    /** 異常終了。close と同じ後始末をしてから理由を伝える */
    private fun fail(reason: String) {
        if (!running) {
            report(reason)
            return
        }
        close(reason)
    }

    /** onClosed は1回だけ呼ぶ */
    private fun report(reason: String) {
        if (closedReported) return
        closedReported = true
        post { onClosed?.invoke(reason) }
    }

    private fun post(block: () -> Unit) {
        ui.post(block)
    }
}
