#!/usr/bin/env python3
"""
どうぶつすごろく データ検証スクリプト

使い方:
    python3 tools/validate.py

assets/stages.json と assets/cave.json を検査し、問題があれば終了コード1で終わる。
JSONを手で編集したあと、pushする前に必ず実行すること。
Kotlinコンパイラのない環境でも、データ起因の不具合はここでほぼ潰せる。
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 検査対象の面を引数で切り替える（既定はA面）
#   python3 tools/validate.py          → A面（:app）
#   python3 tools/validate.py bside    → B面（:bside）
SIDE = sys.argv[1] if len(sys.argv) > 1 else 'app'
if SIDE == 'bside':
    MODULE, PKG = 'bside', 'sugorokub'
else:
    MODULE, PKG = 'app', 'sugoroku'
ASSETS = os.path.join(ROOT, MODULE, 'src', 'main', 'assets')
DRAWABLE = os.path.join(ROOT, MODULE, 'src', 'main', 'res', 'drawable')
KOTLIN = os.path.join(ROOT, MODULE, 'src', 'main', 'kotlin', 'com', 'appathy', PKG)

errors = []
warns = []


def err(msg):
    errors.append(msg)
    print('  \033[91m❌ %s\033[0m' % msg)


def ok(msg):
    print('  ✅ %s' % msg)


def warn(msg):
    warns.append(msg)
    print('  \033[93m⚠️  %s\033[0m' % msg)


def drawables():
    return set(os.path.splitext(f)[0] for f in os.listdir(DRAWABLE))


def check_stages(files, fname='stages.json', item_ids=frozenset()):
    print('\n=== %s ===' % fname)
    path = os.path.join(ASSETS, fname)
    try:
        d = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        err('JSONとして読めません: %s' % e)
        return None

    if d.get('schemaVersion') != 1:
        err('schemaVersion が 1 ではありません')
    n = d.get('mainCellCount', 30)
    shared = d.get('shared', {})
    # 使われている kind の shared だけを必須にする
    used_kinds = set()
    for st in d.get('stages', []):
        for e in st.get('events', []):
            used_kinds.add(e.get('kind', 'normal'))
    for k in ('wedding', 'birth', 'job', 'shop', 'exam', 'love'):
        if k not in used_kinds:
            continue
        if k not in shared:
            err('shared.%s がありません' % k)
        elif shared[k].get('bg') not in files:
            err('shared.%s の画像 %s がありません' % (k, shared[k].get('bg')))

    stages = d.get('stages', [])
    if not stages:
        err('stages が空です')
    names = d.get('statNames')
    if names and len(names) != 3:
        err('statNames は3つにすること')
    if names:
        ok('ステータス名: %s' % ' / '.join(names))
    for st in stages:
        name = st.get('name', '(名前なし)')
        print('--- %s' % name)
        evs = st.get('events', [])
        cells = [e['cell'] for e in evs]
        wed = [e['cell'] for e in evs if e.get('kind') == 'wedding']
        bir = [e['cell'] for e in evs if e.get('kind') == 'birth']
        job = [e['cell'] for e in evs if e.get('kind') == 'job']
        shop = [e['cell'] for e in evs if e.get('kind') == 'shop']
        nrm = [e for e in evs if e.get('kind', 'normal') == 'normal']
        def stat_sum(e):
            return e.get('manpuku', 0) + e.get('juujitsu', 0) + e.get('yuujou', 0)
        def is_bad(e):
            return stat_sum(e) < 0 or e.get('move', 0) < 0
        # もちもの（items.json の id）が実在するか
        for e in nrm:
            for iid, where in ([(e.get('item', ''), 'cell %d' % e['cell'])] +
                               [(c.get('item', ''), 'cell %d の「%s」'
                                 % (e['cell'], c.get('label', '?')))
                                for c in (e.get('choices') or [])]):
                if iid and iid not in item_ids:
                    err('%s の もちもの %s が items.json にありません' % (where, iid))
        cho_ev = [e for e in nrm if e.get('choices')]
        rest_ev = [e for e in nrm if not e.get('choices') and e.get('skip')]
        plain = [e for e in nrm if not e.get('choices') and not e.get('skip')]
        bad_ev = [e for e in plain if is_bad(e)]
        good_ev = [e for e in plain if not is_bad(e)]
        # 選択肢マスは どれを選ぶか分からないので、平均で見積もる
        def choice_avg(e):
            cs = e.get('choices') or []
            return sum(stat_sum(c) for c in cs) / len(cs) if cs else 0
        net = int(sum(stat_sum(e) for e in plain + rest_ev)
                  + sum(choice_avg(e) for e in cho_ev))
        if not bad_ev:
            warn('わるいイベントがありません（起伏が出ません）')
        if len(bad_ev) > len(good_ev):
            warn('わるいイベント(%d)がいいイベント(%d)より多い' % (len(bad_ev), len(good_ev)))
        if net <= 0:
            err('通常イベントの合計が %+d。ステータスが伸びずゲームが成立しません' % net)
        known = {'normal', 'wedding', 'birth', 'job', 'shop', 'exam', 'love'}
        bad_kind = [e.get('kind') for e in evs if e.get('kind', 'normal') not in known]
        if bad_kind:
            err('未知のkind: %s' % set(bad_kind))
        # 小学校版など、しごと・おみせを置かないモードもある
        if fname == 'stages.json' and SIDE == 'app':
            if not job:
                warn('しごとマスがありません')
            if not shop:
                warn('おみせマスがありません')
        mv = [e for e in nrm
              if e.get('move', 0) != 0
              or any(c.get('move', 0) != 0 for c in (e.get('choices') or []))]
        # 選択肢イベント（choices）
        for e in cho_ev:
            cs = e['choices']
            if not (2 <= len(cs) <= 4):
                err('cell %d の choices は 2〜4件（今 %d件）' % (e['cell'], len(cs)))
            if stat_sum(e) or e.get('move', 0):
                err('cell %d は choices があるので 本体に効果を書かない' % e['cell'])
            for c in cs:
                if not str(c.get('label', '')).strip():
                    err('cell %d の choices に label がありません' % e['cell'])
                if not str(c.get('message', '')).strip():
                    err('cell %d の「%s」に message がありません'
                        % (e['cell'], c.get('label', '?')))
                if 'bg' in c and c['bg'] not in files:
                    err('cell %d の「%s」の画像 %s がありません'
                        % (e['cell'], c.get('label', '?'), c['bg']))
                if not (-10 <= c.get('move', 0) <= 10):
                    err('cell %d の「%s」の move は -10〜10' % (e['cell'], c.get('label', '?')))
            # 合計が同じでも 内訳（どのステータスが伸びるか）が違えば えらぶ意味がある
            shapes = {(c.get('manpuku', 0), c.get('juujitsu', 0), c.get('yuujou', 0),
                       c.get('move', 0), bool(c.get('skip'))) for c in cs}
            if len(shapes) == 1:
                warn('cell %d の選択肢は どれも同じ結果です（えらぶ意味がありません）' % e['cell'])
        # 1回休み（skip）
        for e in rest_ev:
            if e.get('move', 0):
                warn('cell %d は おやすみ と 移動 が重なっています（きつすぎ）' % e['cell'])
        if len(rest_ev) > 3:
            warn('おやすみマスが %d 個は多すぎます（3個までを推奨）' % len(rest_ev))
        b = st.get('branchCell', -1)

        if len(cells) != len(set(cells)):
            dup = [c for c in set(cells) if cells.count(c) > 1]
            err('マスが重複: %s' % dup)
        bad = [c for c in cells if not (1 <= c <= n - 2)]
        if bad:
            err('範囲外のマス(1〜%d): %s' % (n - 2, bad))
        if b != -1:
            if b in cells:
                err('branchCell %d がイベントマスと重複' % b)
            if not (1 <= b <= 9):
                err('branchCell %d は 1〜9 にすること（+returnSkip が盤外になる）' % b)
        exam = [e['cell'] for e in evs if e.get('kind') == 'exam']
        love = [e['cell'] for e in evs if e.get('kind') == 'love']
        # ちょうせん系は最後のほうに置かないとステータスが育たない
        for c in exam:
            if c < n * 0.7:
                warn('じゅけんマス %d が早すぎます（%d以降を推奨）' % (c, int(n * 0.7)))
        if wed or bir:
            if len(wed) != 2:
                err('結婚マスが %d 個（2個にすること）' % len(wed))
            if len(bir) != 2:
                err('出産マスが %d 個（2個にすること）' % len(bir))
            if wed and bir and min(wed) >= max(bir):
                err('最初の結婚マスより後に出産マスがありません（出産が発生不能）')
        # 条件ボーナスの妥当性
        for e in nrm:
            cs = e.get('condStat', 0)
            if cs and cs not in (1, 2, 3):
                err('cell %d の condStat は 1〜3' % e['cell'])
            if cs and not (e.get('condBonus1') or e.get('condBonus2') or e.get('condBonus3')):
                warn('cell %d に条件はあるがボーナスが0' % e['cell'])
        if len(nrm) - len(mv) <= len(mv):
            warn('移動ありイベント(%d)が多すぎます。移動なしを多数派に' % len(mv))
        for e in nrm:
            if not e.get('message', '').strip():
                err('cell %d の message が空' % e['cell'])
            if e.get('bg') not in files:
                err('cell %d の画像 %s がありません' % (e['cell'], e.get('bg')))
            if not (1 <= e.get('group', 1) <= 4):
                err('cell %d の group は 1〜4' % e['cell'])
        if st.get('bg') not in files:
            err('盤面背景 %s がありません' % st.get('bg'))
        ok('イベント%d件 (🟢%d 🟣%d 🔵%d 💤%d 💒%d 👶%d 💼%d 🛒%d 🌸%d 💗%d) 移動あり%d 合計%+d あな%s'
           % (len(evs), len(good_ev), len(bad_ev), len(cho_ev), len(rest_ev),
              len(wed), len(bir), len(job), len(shop),
              len(exam), len(love), len(mv), net, 'なし' if b == -1 else str(b)))
    return d


def check_items():
    """もちもの（items.json）。自分に使うものは移動を持たない、
    相手に使うものは もどす か いれかえ のどちらかだけ、を守らせる。"""
    print('\n=== items.json ===')
    path = os.path.join(ASSETS, 'items.json')
    if not os.path.exists(path):
        warn('items.json がありません（もちもの無しで動きます）')
        return set()
    try:
        d = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        err('items.json がJSONとして読めません: %s' % e)
        return set()
    ids = set()
    for it in d.get('items', []):
        i = it.get('id', '')
        if not i:
            err('id のない もちものがあります')
            continue
        if i in ids:
            err('もちものの id が重複: %s' % i)
        ids.add(i)
        for k in ('name', 'icon', 'desc'):
            if not str(it.get(k, '')).strip():
                err('%s: %s が空' % (i, k))
        tgt = it.get('target', 'self')
        if tgt not in ('self', 'other'):
            err('%s: target は self か other' % i)
        if tgt == 'self' and (it.get('move', 0) or it.get('swap')):
            err('%s: 自分に使うものに 移動・いれかえ は書けません' % i)
        if tgt == 'other':
            if it.get('move', 0) > 0:
                err('%s: 相手を すすめる もちものは作れません' % i)
            if not it.get('move', 0) and not it.get('swap'):
                err('%s: 相手に使うのに なにも起きません' % i)
            for k in ('manpuku', 'juujitsu', 'yuujou', 'boost', 'guard'):
                if it.get(k):
                    warn('%s: 相手に使うものに 自分への効果が まざっています' % i)
        if it.get('move', 0) < -8:
            warn('%s: %dマスも もどすのは きつすぎます' % (i, -it.get('move', 0)))
        if it.get('boost', 0) > 6:
            warn('%s: ルーレット+%d は 大きすぎます' % (i, it.get('boost', 0)))
    if not ids:
        warn('もちものが1つもありません')
    else:
        ok('もちもの%d種 もてる数%d 未使用スコア%d'
           % (len(ids), d.get('maxHold', 3), d.get('unusedScore', 5)))
    return ids


def check_cave(files, stages_doc, item_ids=frozenset()):
    print('\n=== cave.json ===')
    path = os.path.join(ASSETS, 'cave.json')
    try:
        d = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        err('JSONとして読めません: %s' % e)
        return

    if d.get('schemaVersion') != 1:
        err('schemaVersion が 1 ではありません')
    n = d.get('cellCount', 20)
    skip = d.get('returnSkip', 20)
    evs = d.get('events', [])
    cells = [e['cell'] for e in evs]

    if len(cells) != len(set(cells)):
        err('マスが重複')
    bad = [c for c in cells if not (1 <= c <= n - 2)]
    if bad:
        err('範囲外のマス(1〜%d): %s' % (n - 2, bad))
    if d.get('bg') not in files:
        err('洞窟背景 %s がありません' % d.get('bg'))
    for e in evs:
        if e.get('bg') not in files:
            err('cell %d の画像 %s がありません' % (e['cell'], e.get('bg')))
        # 洞窟でも 選択肢・もちもの が使えるので 同じ約束を守らせる
        cs = e.get('choices') or []
        if cs:
            if not (2 <= len(cs) <= 4):
                err('cell %d の choices は 2〜4件（今 %d件）' % (e['cell'], len(cs)))
            if (e.get('manpuku', 0) or e.get('juujitsu', 0)
                    or e.get('yuujou', 0) or e.get('move', 0)):
                err('cell %d は choices があるので 本体に効果を書かない' % e['cell'])
            for c in cs:
                if not str(c.get('label', '')).strip():
                    err('cell %d の choices に label がありません' % e['cell'])
                if 'bg' in c and c['bg'] not in files:
                    err('cell %d の「%s」の画像 %s がありません'
                        % (e['cell'], c.get('label', '?'), c['bg']))
        for iid in [e.get('item', '')] + [c.get('item', '') for c in cs]:
            if iid and iid not in item_ids:
                err('cell %d の もちもの %s が items.json にありません' % (e['cell'], iid))

    # 復帰先が盤内に収まるか（全ステージ分）
    if stages_doc:
        main_n = stages_doc.get('mainCellCount', 30)
        for st in stages_doc.get('stages', []):
            ret = st.get('branchCell', 0) + skip
            # ゴール(main_n-1)ちょうどに復帰すると洞窟に入った瞬間クリアになるので不可
            if ret > main_n - 2:
                err('%s: 復帰先 %d が ゴール(%d)に近すぎます（最大 %d）'
                    % (st['name'], ret, main_n - 1, main_n - 2))

    # 洞窟内でスタート(0)に戻る経路がないか総当たり（後退の下限は1）
    ev_map = {e['cell']: e for e in evs}
    goal = n - 1

    def land(pos, from_event=False):
        if pos >= goal:
            return pos, True
        e = ev_map.get(pos)
        if e and not from_event and e.get('move', 0) != 0:
            step = 1 if e['move'] > 0 else -1
            rem = abs(e['move'])
            q = pos
            while rem > 0 and (q < goal if step > 0 else q > 1):
                q += step
                rem -= 1
            return land(q, True)
        return pos, False

    zeros = []
    for start in range(goal):
        for spin in range(1, 11):
            final, out = land(min(start + spin, goal))
            if not out and final == 0:
                zeros.append((start, spin))
    if zeros:
        err('洞窟内でスタート(0)に戻る経路が %d 件' % len(zeros))
    else:
        ok('スタートに戻る経路なし（全開始位置×出目1〜10を総当たり）')
    if not evs:
        warn('洞窟のイベントが0件です（洞窟を使わない構成なら問題なし）')
    ok('イベント%d件 マス%d 復帰+%d' % (len(evs), n, skip))


def check_jobs(files):
    print('\n=== jobs.json ===')
    path = os.path.join(ASSETS, 'jobs.json')
    try:
        d = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        err('JSONとして読めません: %s' % e)
        return
    if d.get('schemaVersion') != 1:
        err('schemaVersion が 1 ではありません')

    skills = d.get('skills', [])
    jobs = d.get('jobs', [])
    sid = [s['id'] for s in skills]
    jid = [j['id'] for j in jobs]
    if len(sid) != len(set(sid)):
        err('スキルidが重複')
    if len(jid) != len(set(jid)):
        err('職業idが重複')
    if not jobs:
        err('職業が1つもありません')

    # 必要スキルが実在するか（存在しないと永久に就けない職業になる）
    for j in jobs:
        miss = set(j.get('requires', [])) - set(sid)
        if miss:
            err('%s: 実在しないスキルを要求 %s' % (j['id'], miss))

    # スキル無しで就ける職業が最低1つないと、就職マスが機能しない
    free = [j for j in jobs if not j.get('requires')]
    if not free:
        err('必要スキルなしで就ける職業がありません')

    # 全スキルを買える現実性: 一番安いスキル <= 初期資金 + 給料1回分
    start = d.get('startMoney', 0)
    base = max((j['salary'] for j in free), default=0)
    cheapest = min((s['cost'] for s in skills), default=0)
    if skills and cheapest > start + base:
        warn('一番安いスキル(%d)が 初期資金+給料(%d) を超えています'
             % (cheapest, start + base))

    for s in skills:
        if not (0 <= s.get('dice', 0) <= 3):
            err('%s: dice は 0〜3' % s['id'])
    ok('スキル%d件 職業%d件 初期資金%d（スキル不要の職業%d件）'
       % (len(skills), len(jobs), start, len(free)))


def check_charas(files):
    print('\n=== charas.json ===')
    path = os.path.join(ASSETS, 'charas.json')
    try:
        d = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        err('JSONとして読めません: %s' % e)
        return {}
    sets = d.get('sets', {})
    if not sets:
        err('sets が空です')
    for key, st in sets.items():
        chars = st.get('charas', [])
        names = [c.get('name') for c in chars]
        if len(names) != len(set(names)):
            err('%s: 名前が重複' % key)
        for c in chars:
            for field in ('img', 'partner', 'child'):
                v = c.get(field)
                if v and v not in files:
                    err('%s の %s に指定された画像 %s がありません' % (c.get('name'), field, v))
        # 結婚・出産のあるモードで使うなら partner/child が要る
        full = [c for c in chars if c.get('partner') and c.get('child')]
        ok('%s: %d体（partner/child あり %d体）' % (key, len(chars), len(full)))
    return sets


def check_charaset_link(sets):
    print('\n=== モードとキャラセットの対応 ===')
    for fname in ('stages.json', 'school_stages.json'):
        path = os.path.join(ASSETS, fname)
        try:
            d = json.load(open(path, encoding='utf-8'))
        except Exception:
            continue
        key = d.get('charaSet', 'animal')
        if key not in sets:
            err('%s の charaSet=%s が charas.json にありません' % (fname, key))
            continue
        chars = sets[key].get('charas', [])
        # 結婚・出産マスを置くモードは partner/child が必須
        kinds = set()
        for st in d.get('stages', []):
            for e in st.get('events', []):
                kinds.add(e.get('kind', 'normal'))
        need_family = bool(kinds & {'wedding', 'birth'})
        missing = [c['name'] for c in chars
                   if need_family and not (c.get('partner') and c.get('child'))]
        if missing:
            err('%s は結婚/出産があるのに partner/child が無いキャラ: %s' % (fname, missing))
        ok('%s → %s（%d体・家族画像%s）'
           % (fname, key, len(chars), '必要' if need_family else '不要'))


KOTLIN_BUILTIN = set("""
if when for while return listOf mapOf setOf arrayListOf intArrayOf floatArrayOf hashMapOf
require check println maxOf minOf TODO apply let also run with it Pair Triple String Int Float
Boolean catch try synchronized lazy object super this HashSet HashMap ArrayList LinkedHashMap
StringBuilder coerceIn coerceAtLeast coerceAtMost emptyList emptySet emptyMap buildString
JSONObject JSONArray File Random Color Typeface Paint RectF Path Rect PointF Bitmap Canvas Toast
AlertDialog ValueAnimator LinearLayout TextView Button ImageView ImageButton ScrollView
FrameLayout GridLayout EditText GradientDrawable Handler Looper Log Math sin cos floor abs min
max sqrt round ceil toInt toFloat toString joinToString sortedBy sortedByDescending filter
filterValues map mapNotNull firstOrNull getOrNull withIndex groupBy distinct associateWith take
any none count sumOf isNotEmpty isNotBlank isBlank ifBlank contains containsAll add addAll put
optInt optString optJSONObject optJSONArray optBoolean has length clear remove delete exists
readText writeText open use readBytes trimEnd replace split lowercase indexOf substring
postDelayed removeCallbacksAndMessages invalidate start cancel addView setPadding setContentView
show create build first last indices until downTo step repeat arrayOf values keys entries forEach
sorted reversed toList toTypedArray copyOf toSet toMutableList toSortedMap append close lineTo
moveTo equals hashCode plus renameTo isEmpty drop dropLast plusAssign lowercaseChar
decodeResource getIdentifier getSharedPreferences edit putStringSet getStringSet
ofFloat ofInt toRadians floorMod nextInt nextFloat isInitialized
onCreate onDestroy onDraw onSizeChanged onTouchEvent onAttachedToWindow onDetachedFromWindow
onAnimationEnd save restore translate scale rotate drawText drawCircle drawLine drawArc drawOval
drawPath drawBitmap drawRoundRect addListener addUpdateListener setOnClickListener
""".split())


def check_missing_functions():
    """呼び出しはあるのに定義が無い関数を検出する。

    v5.4-A で、スコア計算を書き換えた際に隣接していた meetsCond() を巻き込んで
    削除してしまい、呼び出しだけが残ってビルドが壊れた。その再発防止。
    """
    print('\n=== 関数の定義もれ ===')
    files = ['MainActivity.kt', 'GameData.kt', 'EditorScreens.kt', 'Zukan.kt']
    all_defs = set()
    srcs = {}
    for fn in files:
        path = os.path.join(KOTLIN, fn)
        if not os.path.exists(path):
            continue
        srcs[fn] = open(path, encoding='utf-8').read()
        all_defs |= set(re.findall(r'\bfun\s+(\w+)\s*\(', srcs[fn]))

    def strip_strings(src):
        """文字列リテラルと注釈を空白に置き換える。
        "…が小さすぎます($it)" のような日本語が関数呼び出しに誤検出されるのを防ぐ。"""
        out = list(src)
        i = 0
        n = len(src)
        while i < n:
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
                for k in range(i, j):
                    if out[k] != '\n':
                        out[k] = ' '
                i = j
            elif src[i] == '"':
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == '\\':
                        j += 1
                    j += 1
                j = min(j + 1, n)
                for k in range(i, j):
                    if out[k] != '\n':
                        out[k] = ' '
                i = j
            elif src.startswith('//', i):
                j = src.find('\n', i)
                j = n if j < 0 else j
                for k in range(i, j):
                    out[k] = ' '
                i = j
            else:
                i += 1
        return ''.join(out)

    bad = False
    for fn, raw in srcs.items():
        s = strip_strings(raw)
        # ラムダ引数（onBack: () -> Unit など）は呼び出せるので定義扱いにする
        lambdas = set(re.findall(r'(\w+)\s*:\s*\([^)]*\)\s*->', s))
        local = set(re.findall(r'\b(?:val|var)\s+(\w+)\s*[:=]', s))
        # val (a, b, c) = ... の分割宣言は「val(」に見えるので除外する
        known = all_defs | lambdas | local | KOTLIN_BUILTIN | {'val', 'var'}
        for m in re.finditer(r'(?<![\w.])([a-z]\w*)\s*\(', s):
            name = m.group(1)
            before = s[max(0, m.start() - 5):m.start()]
            if before.endswith('fun ') or name in known:
                continue
            if name.startswith('set') or name.startswith('get'):
                continue
            line = s[:m.start()].count('\n') + 1
            err('%s:%d 呼び出している %s() の定義がありません' % (fn, line, name))
            bad = True
    if not bad:
        ok('未定義の関数呼び出しなし')


def check_kotlin():
    print('\n=== Kotlin ===')
    for fn in ('MainActivity.kt', 'GameData.kt', 'EditorScreens.kt', 'Zukan.kt'):
        path = os.path.join(KOTLIN, fn)
        if not os.path.exists(path):
            err('%s がありません' % fn)
            continue
        s = open(path, encoding='utf-8').read()
        for a, b, label in (('{', '}', '波かっこ'), ('(', ')', 'かっこ')):
            if s.count(a) != s.count(b):
                err('%s: %sの対応が合いません %d/%d' % (fn, label, s.count(a), s.count(b)))
        # v3.3で実際にビルドを壊した罠: "$変数" の直後に日本語
        hits = re.findall(r'\$([A-Za-z_][A-Za-z0-9_]*)([\u3040-\u30FF\u4E00-\u9FFF])', s)
        if hits:
            err('%s: 文字列テンプレートの直後に日本語 %s → ${...} で囲むこと' % (fn, hits))
        ok('%s 構文チェック通過' % fn)


def main():
    files = drawables()
    print('検査対象: %s面（%s）' % ('B' if SIDE == 'bside' else 'A', MODULE))
    print('drawable: %d件' % len(files))
    item_ids = check_items()
    doc = check_stages(files, 'stages.json', item_ids)
    if os.path.exists(os.path.join(ASSETS, 'school_stages.json')):
        check_stages(files, 'school_stages.json', item_ids)
    check_cave(files, doc, item_ids)
    check_jobs(files)
    sets = check_charas(files)
    check_charaset_link(sets)
    check_missing_functions()
    check_kotlin()

    print('\n' + '=' * 40)
    if errors:
        print('\033[91m❌ エラー %d件\033[0m' % len(errors))
        sys.exit(1)
    if warns:
        print('\033[93m⚠️  警告 %d件（ビルドは可能）\033[0m' % len(warns))
    print('\033[92m✅ すべて通過\033[0m')


if __name__ == '__main__':
    main()
