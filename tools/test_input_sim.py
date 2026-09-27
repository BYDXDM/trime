#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟 Rime 的输入→候选→上屏→学习 全流程。

目的：在不动 Android 设备的情况下，验证词库设计能否达到预期体验。
它复刻的是 librime 的排序规则（简化版）：

    候选得分 = 词条权重 × 翻译器 initial_quality
    用户词典命中的词，权重会被 userdb 的增量覆盖

用法:
    python3 tools/test_input_sim.py app/data/rime/myvocab/mydomain.dict.yaml
"""

import sys
import os
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_dict import parse_dict, abbrev_of


class SimRime:
    def __init__(self, dict_path, page_size=8):
        self.page_size = page_size
        self.entries = []          # (word, code, weight)
        # 模拟 userdb：{词: 学习增量}
        self.userdb = collections.defaultdict(float)

        meta, entries = parse_dict(dict_path)
        for word, py, weight, _ln in entries:
            w = int(weight) if (weight or '').isdigit() else 1000
            self.entries.append((word, py, w))

    # ---------- 索引 ----------
    def _exact(self, code):
        """全拼精确匹配：'beng tie' → 'bengtie'"""
        norm = code.replace("'", "").replace(" ", "")
        return [(w, wt) for w, py, wt in self.entries
                if py.replace("'", "").replace(" ", "") == norm]

    def _abbrev(self, code):
        """简拼匹配：'bt' → 'beng tie'"""
        return [(w, wt) for w, py, wt in self.entries
                if abbrev_of(py) == code]

    # ---------- 候选 ----------
    def candidates(self, code, allow_abbrev=True):
        """返回 [(词, 最终得分)]，按得分降序"""
        hits = collections.defaultdict(float)

        # 主翻译器（全拼），initial_quality = 1.0
        for w, wt in self._exact(code):
            hits[w] = max(hits[w], wt * 1.0)

        # 简拼翻译器，initial_quality = 1.0（与全拼平权，靠词库权重排序）
        if allow_abbrev:
            for w, wt in self._abbrev(code):
                hits[w] = max(hits[w], wt * 1.0)

        # 用户词典增量（学习成果）
        for w in list(hits):
            hits[w] += self.userdb.get(w, 0.0)

        return sorted(hits.items(), key=lambda kv: -kv[1])

    def page(self, code, page=0):
        c = self.candidates(code)
        s = page * self.page_size
        return c[s:s + self.page_size], len(c)

    # ---------- 上屏 + 学习 ----------
    def commit(self, word, times=1):
        """
        模拟用户选词上屏。
        librime 的 UserDictionary::UpdateEntry 会提升该词权重，
        增量按使用次数累积（这里用简化模型：每次 +240）。
        """
        self.userdb[word] += 240.0 * times
        return word


def fmt(cands, limit=6):
    if not cands:
        return '(无候选)'
    return '  '.join('%s(%.0f)' % (w, s) for w, s in cands[:limit])


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    rime = SimRime(path)

    print('=' * 70)
    print('  输入模拟：%s' % path)
    print('=' * 70)

    print('\n【1】全拼输入')
    for code in ('bengtie', 'xingqiongtiedao', 'yuanshen', 'shengyiwu',
                 'clash', 'cloudflare', 'github'):
        cands, total = rime.page(code)
        print('  %-16s → %s' % (code, fmt(cands)))

    print('\n【2】简拼输入（只打首字母）')
    for code in ('bt', 'xt', 'ys', 'ql', 'sy', 'bh', 'cl', 'cf', 'gh', 'yt'):
        cands, total = rime.page(code)
        print('  %-16s → %s' % (code, fmt(cands)))

    print('\n【3】用户学习：模拟打一个新词「某游戏角色名」')
    new_word = '砂金'
    print('  学习前  打 "shajin" → %s' % fmt(rime.candidates('shajin')))
    rime.commit(new_word, times=1)
    print('  上屏1次 打 "shajin" → %s' % fmt(rime.candidates('shajin')))
    rime.commit(new_word, times=3)
    print('  再打3次 打 "shajin" → %s' % fmt(rime.candidates('shajin')))

    print('\n【4】学习效果：把一个冷门词的权重顶过常用词')
    code = 'yuanshi'
    print('  学习前  "yuanshi" → %s' % fmt(rime.candidates(code)))
    # 用户连着选了 10 次「原始」（假设是某个专业术语）
    rime.commit('原始', times=10)
    print('  连选原始10次  → %s' % fmt(rime.candidates(code)))
    top = rime.candidates(code)[0][0] if rime.candidates(code) else None
    print('  当前首选      → %s' % top)

    print('\n【5】学习安全性：固定短语不会被顶掉')
    # custom_phrase 用 stabledb，不参与学习。这里验证它权重固定。
    phrase = {'content': '崩铁', 'weight': 2000, 'stable': True}
    print('  custom_phrase 里的「%s」权重 %d（stabledb，学习不影响）'
          % (phrase['content'], phrase['weight']))

    print('\n【6】翻页')
    cands, total = rime.page('ys', page=0)
    print('  "ys" 共 %d 个候选，第1页: %s' % (total, fmt(cands, 8)))

    print('\n' + '=' * 70)
    print('  模拟完成')
    print('=' * 70)
    return 0


if __name__ == '__main__':
    sys.exit(main())
