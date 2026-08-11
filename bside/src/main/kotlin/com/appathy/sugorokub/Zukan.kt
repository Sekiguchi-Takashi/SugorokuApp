package com.appathy.sugorokub

import android.content.Context

/**
 * 図鑑。出会ったイベントを記録する。
 *
 * 保存先は SharedPreferences（外部依存ゼロの規約を守るため DB は使わない）。
 * キーは「ステージ名:マス番号」。ステージ名を含めるのは、
 * 別ステージの同じマス番号を別イベントとして数えるため。
 */
object Zukan {

    private const val PREF = "zukan"
    private const val KEY_SEEN = "seen"

    /** 洞窟は疑似ステージ名として扱う */
    const val CAVE = "どうくつ"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(stage: String, cell: Int) = "$stage:$cell"

    /** 遭遇済みのキー一覧 */
    fun seen(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    fun isSeen(context: Context, stage: String, cell: Int): Boolean =
        key(stage, cell) in seen(context)

    /** 出会ったことを記録する。既出なら false を返す（初遭遇の演出に使える） */
    fun record(context: Context, stage: String, cell: Int): Boolean {
        val k = key(stage, cell)
        val cur = seen(context)
        if (k in cur) return false
        // getStringSet の返り値は直接編集してはいけないので必ずコピーする
        prefs(context).edit().putStringSet(KEY_SEEN, HashSet(cur).apply { add(k) }).apply()
        return true
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SEEN).apply()
    }
}
