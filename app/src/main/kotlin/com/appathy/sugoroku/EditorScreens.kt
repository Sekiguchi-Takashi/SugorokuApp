package com.appathy.sugoroku

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * イベントエディタと図鑑の画面。
 *
 * MainActivity が肥大化しないよう、UI構築をこのクラスに分離している。
 * 画面は Activity を増やさず setContentView の差し替えで実現する（既存方式と同じ）。
 *
 * 編集対象は filesDir/stages.json と filesDir/cave.json。
 * 保存すると GameData.saveJson が呼ばれ、次回のデータ読み込みから反映される。
 */
class EditorScreens(
    private val act: Activity,
    private val onBack: () -> Unit,
    private val onDataChanged: () -> Unit
) {

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
        setTextColor(Color.parseColor("#33691E"))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun listButton(label: String, sub: String?, color: Int, onClick: () -> Unit): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        col.addView(TextView(act).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (!sub.isNullOrBlank()) {
            col.addView(TextView(act).apply {
                text = sub
                textSize = 12f
                setTextColor(Color.parseColor("#E8F5E9"))
            })
        }
        return col
    }

    private fun screen(build: LinearLayout.() -> Unit) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        root.build()
        act.setContentView(ScrollView(act).apply { addView(root) })
    }

    private fun backButton(onClick: () -> Unit) = Button(act).apply {
        text = "◀ もどる"
        textSize = 15f
        setTextColor(Color.WHITE)
        background = roundedBg(Color.parseColor("#78909C"))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) =
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()

    // ==================== 図鑑 ====================

    /**
     * 図鑑。出会ったイベントは写真つき、未遭遇は「？？？」で表示する。
     */
    fun showZukan() {
        val seen = Zukan.seen(act)
        val entries = ArrayList<Triple<String, Int, MainActivity.GameEvent>>()

        // 本線
        for (file in listOf("stages.json", "school_stages.json")) {
            for (s in GameData.loadStages(act, file).stages) {
                for ((cell, ev) in s.events.toSortedMap()) entries.add(Triple(s.name, cell, ev))
            }
        }
        // 洞窟
        val cave = GameData.loadCave(act)
        for ((cell, ev) in cave.data.events.toSortedMap()) {
            entries.add(Triple(Zukan.CAVE, cell, ev))
        }

        val found = entries.count { "${it.first}:${it.second}" in seen }

        screen {
            addView(backButton { onBack() })
            addView(header("📖 ずかん"))
            addView(TextView(act).apply {
                text = "$found / ${entries.size} こ みつけた！"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#558B2F"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            })

            var lastStage = ""
            for ((stage, cell, ev) in entries) {
                if (stage != lastStage) {
                    lastStage = stage
                    addView(TextView(act).apply {
                        text = stage
                        textSize = 15f
                        setTextColor(Color.parseColor("#33691E"))
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(4), dp(12), 0, dp(4))
                    })
                }
                val isSeen = "$stage:$cell" in seen
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(
                        if (isSeen) Color.WHITE else Color.parseColor("#CFD8DC"),
                        Color.parseColor("#A5D6A7")
                    )
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                val thumb = ImageView(act).apply {
                    if (isSeen && ev.bgRes != 0) {
                        setImageResource(ev.bgRes)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        setBackgroundColor(Color.parseColor("#90A4AE"))
                    }
                }
                row.addView(thumb, LinearLayout.LayoutParams(dp(76), dp(52)))
                val col = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, 0, 0)
                }
                val mark = when (ev.kind) {
                    MainActivity.EventKind.WEDDING -> "💒 "
                    MainActivity.EventKind.BIRTH -> "👶 "
                    MainActivity.EventKind.JOB -> "💼 "
                    MainActivity.EventKind.SHOP -> "🛒 "
                    MainActivity.EventKind.EXAM -> "🌸 "
                    else -> ""
                }
                col.addView(TextView(act).apply {
                    text = if (isSeen) "$mark${cell}マスめ" else "？？？（${cell}マスめ）"
                    textSize = 13f
                    setTextColor(Color.parseColor("#37474F"))
                    typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(act).apply {
                    text = if (isSeen) ev.message.replace("\n", " ") else "まだ 出会っていない"
                    textSize = 11f
                    maxLines = 2
                    setTextColor(Color.parseColor("#607D8B"))
                })
                row.addView(col, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
        }
    }

    // ==================== エディタ: ファイル選択 ====================

    fun showEditorTop() {
        screen {
            addView(backButton { onBack() })
            addView(header("✏️ イベントエディタ"))
            addView(TextView(act).apply {
                text = "イベントの ないようを かえられるよ。\nかえた ないようは この スマホに ほぞんされる。"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#558B2F"))
                setPadding(0, 0, 0, dp(12))
            })

            for (file in listOf("stages.json", "school_stages.json")) {
                val st = GameData.loadStages(act, file)
                for ((i, s) in st.stages.withIndex()) {
                    addView(
                        listButton(
                            s.name, "イベント ${s.events.size}こ", Color.parseColor("#7CB342")
                        ) { showCellList(file, i, s.name) },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
                    )
                }
            }
            val cave = GameData.loadCave(act)
            addView(
                listButton(
                    "どうくつ", "イベント ${cave.data.events.size}こ", Color.parseColor("#5E35B1")
                ) { showCellList("cave.json", -1, "どうくつ") },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )

            // 初期状態に戻す安全弁
            val edited = GameData.hasUserData(act, "stages.json") ||
                GameData.hasUserData(act, "school_stages.json") ||
                GameData.hasUserData(act, "cave.json")
            addView(TextView(act).apply {
                text = if (edited) "※ へんしゅう ずみ" else "※ まだ へんしゅう していない"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#8D6E63"))
                setPadding(0, dp(18), 0, dp(6))
            })
            addView(Button(act).apply {
                text = "はじめの ないように もどす"
                textSize = 14f
                isEnabled = edited
                setTextColor(Color.WHITE)
                background = roundedBg(Color.parseColor("#D32F2F"))
                alpha = if (edited) 1f else 0.4f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { confirmReset() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(act)
            .setTitle("もとに もどす")
            .setMessage("へんしゅうした ないようを ぜんぶ すてて、\nはじめの ないように もどします。よろしいですか？")
            .setPositiveButton("もどす") { _, _ ->
                GameData.resetToAssets(act, "stages.json")
                GameData.resetToAssets(act, "school_stages.json")
                GameData.resetToAssets(act, "cave.json")
                onDataChanged()
                toast("はじめの ないように もどしました")
                showEditorTop()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ==================== エディタ: マス一覧 ====================

    /**
     * @param file "stages.json" か "cave.json"
     * @param stageIndex stages.json のときのステージ番号。cave.json では -1
     */
    private fun showCellList(file: String, stageIndex: Int, title: String) {
        val arr = readEvents(file, stageIndex)
        if (arr == null) {
            toast("データを よみこめませんでした")
            showEditorTop()
            return
        }
        screen {
            addView(backButton { showEditorTop() })
            addView(header(title))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cell = o.optInt("cell", -1)
                val kind = o.optString("kind", "normal")
                val mark = when (kind) {
                    "wedding" -> "💒 けっこん"
                    "birth" -> "👶 あかちゃん"
                    "job" -> "💼 しごと"
                    "shop" -> "🛒 おみせ"
                    "exam" -> "🌸 じゅけん"
                    else -> o.optString("message", "").replace("\n", " ").take(24)
                }
                // 盤面と同じ配色にそろえる（緑=good 紫=bad ピンク=けっこん出産）
                val bad = kind == "normal" && (
                    o.optInt("manpuku") + o.optInt("juujitsu") + o.optInt("yuujou") < 0 ||
                        o.optInt("move") < 0
                    )
                val color = when {
                    kind == "wedding" || kind == "birth" -> Color.parseColor("#EC407A")
                    kind == "job" -> Color.parseColor("#00ACC1")
                    kind == "shop" -> Color.parseColor("#F9A825")
                    kind == "exam" -> Color.parseColor("#E53935")
                    bad -> Color.parseColor("#7E57C2")
                    else -> Color.parseColor("#43A047")
                }
                addView(
                    listButton("${cell}マスめ", mark, color) {
                        if (kind == "normal") showEventEdit(file, stageIndex, i, title)
                        else toast("とくべつな マスは かえられません")
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                )
            }
        }
    }

    // ==================== エディタ: イベント編集 ====================

    private fun showEventEdit(file: String, stageIndex: Int, evIndex: Int, title: String) {
        val arr = readEvents(file, stageIndex) ?: return
        val o = arr.optJSONObject(evIndex) ?: return
        val cell = o.optInt("cell", 0)

        val msgInput = EditText(act).apply {
            setText(o.optString("message", ""))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        fun numInput(key: String): EditText = EditText(act).apply {
            setText(o.optInt(key, 0).toString())
            textSize = 15f
            // マイナス値を入れるので signed を必ず付ける
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val manpuku = numInput("manpuku")
        val juujitsu = numInput("juujitsu")
        val yuujou = numInput("yuujou")
        val move = numInput("move")
        val group = numInput("group")

        fun labeled(label: String, hint: String, v: View): View {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            col.addView(TextView(act).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#33691E"))
                typeface = Typeface.DEFAULT_BOLD
            })
            if (hint.isNotBlank()) {
                col.addView(TextView(act).apply {
                    text = hint
                    textSize = 11f
                    setTextColor(Color.parseColor("#8D6E63"))
                })
            }
            col.addView(v)
            return col
        }

        screen {
            addView(backButton { showCellList(file, stageIndex, title) })
            addView(header("$title ${cell}マスめ"))

            // 背景プレビュー
            val bgName = o.optString("bg", "")
            val bgId = GameData.drawableId(act, bgName)
            if (bgId != 0) {
                addView(ImageView(act).apply {
                    setImageResource(bgId)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            addView(TextView(act).apply {
                text = "しゃしん: $bgName"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#8D6E63"))
                setPadding(0, dp(4), 0, 0)
            })

            addView(labeled("メッセージ", "", msgInput))
            addView(labeled("満腹", "ふえる数（マイナスも可）", manpuku))
            addView(labeled("充実", "ふえる数（マイナスも可）", juujitsu))
            addView(labeled("友情", "ふえる数（マイナスも可）", yuujou))
            addView(labeled("すすむ / もどる", "0なら うごかない。-3で3マス もどる", move))
            addView(labeled("でてくる どうぶつ", "1〜4ひき", group))

            addView(Button(act).apply {
                text = "ほぞんする"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = roundedBg(Color.parseColor("#00897B"))
                setPadding(dp(10), dp(14), dp(10), dp(14))
                setOnClickListener {
                    fun toInt(e: EditText, def: Int): Int =
                        e.text.toString().trim().toIntOrNull() ?: def

                    val msg = msgInput.text.toString()
                    if (msg.isBlank()) {
                        toast("メッセージを いれてね")
                        return@setOnClickListener
                    }
                    o.put("message", msg)
                    o.put("manpuku", toInt(manpuku, 0).coerceIn(-999, 999))
                    o.put("juujitsu", toInt(juujitsu, 0).coerceIn(-999, 999))
                    o.put("yuujou", toInt(yuujou, 0).coerceIn(-999, 999))
                    // 移動は盤面をはみ出さない範囲に制限する
                    o.put("move", toInt(move, 0).coerceIn(-10, 10))
                    o.put("group", toInt(group, 1).coerceIn(1, 4))

                    if (writeEvents(file, stageIndex, arr)) {
                        onDataChanged()
                        toast("ほぞんしました")
                        showCellList(file, stageIndex, title)
                    } else {
                        toast("ほぞんに しっぱいしました")
                    }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) })
        }
    }

    // ==================== JSON 読み書き ====================

    /** 対象ファイルのイベント配列を取り出す */
    private fun readEvents(file: String, stageIndex: Int): JSONArray? {
        val raw = GameData.rawJson(act, file) ?: return null
        return try {
            val root = JSONObject(raw)
            if (stageIndex < 0) root.optJSONArray("events")
            else root.optJSONArray("stages")?.optJSONObject(stageIndex)?.optJSONArray("events")
        } catch (e: Exception) {
            null
        }
    }

    /** 編集したイベント配列を書き戻して保存する */
    private fun writeEvents(file: String, stageIndex: Int, arr: JSONArray): Boolean {
        val raw = GameData.rawJson(act, file) ?: return false
        return try {
            val root = JSONObject(raw)
            if (stageIndex < 0) {
                root.put("events", arr)
            } else {
                val stages = root.optJSONArray("stages") ?: return false
                val st = stages.optJSONObject(stageIndex) ?: return false
                st.put("events", arr)
            }
            GameData.saveJson(act, file, root.toString(2))
        } catch (e: Exception) {
            false
        }
    }
}
