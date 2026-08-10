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
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
DRAWABLE = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable')
KOTLIN = os.path.join(ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'appathy', 'sugoroku')

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


def check_stages(files, fname='stages.json'):
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
    for k in ('wedding', 'birth', 'job', 'shop', 'exam'):
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
        def is_bad(e):
            return (e.get('manpuku', 0) + e.get('juujitsu', 0) + e.get('yuujou', 0) < 0
                    or e.get('move', 0) < 0)
        bad_ev = [e for e in nrm if is_bad(e)]
        good_ev = [e for e in nrm if not is_bad(e)]
        net = sum(e.get('manpuku', 0) + e.get('juujitsu', 0) + e.get('yuujou', 0) for e in nrm)
        if not bad_ev:
            warn('わるいイベントがありません（起伏が出ません）')
        if len(bad_ev) > len(good_ev):
            warn('わるいイベント(%d)がいいイベント(%d)より多い' % (len(bad_ev), len(good_ev)))
        if net <= 0:
            err('通常イベントの合計が %+d。ステータスが伸びずゲームが成立しません' % net)
        known = {'normal', 'wedding', 'birth', 'job', 'shop', 'exam'}
        bad_kind = [e.get('kind') for e in evs if e.get('kind', 'normal') not in known]
        if bad_kind:
            err('未知のkind: %s' % set(bad_kind))
        # 小学校版など、しごと・おみせを置かないモードもある
        if fname == 'stages.json':
            if not job:
                warn('しごとマスがありません')
            if not shop:
                warn('おみせマスがありません')
        mv = [e for e in nrm if e.get('move', 0) != 0]
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
        ok('イベント%d件 (🟢%d 🟣%d 💒%d 👶%d 💼%d 🛒%d 🌸%d) 移動あり%d 合計%+d あな%s'
           % (len(evs), len(good_ev), len(bad_ev), len(wed), len(bir), len(job), len(shop),
              len(exam), len(mv), net, 'なし' if b == -1 else str(b)))
    return d


def check_cave(files, stages_doc):
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

    # 復帰先が盤内に収まるか（全ステージ分）
    if stages_doc:
        main_n = stages_doc.get('mainCellCount', 30)
        for st in stages_doc.get('stages', []):
            ret = st.get('branchCell', 0) + skip
            if ret > main_n - 1:
                err('%s: 復帰先 %d が盤外(最大%d)' % (st['name'], ret, main_n - 1))

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
    print('drawable: %d件' % len(files))
    doc = check_stages(files, 'stages.json')
    check_stages(files, 'school_stages.json')
    check_cave(files, doc)
    check_jobs(files)
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
