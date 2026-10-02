#!/bin/sh
# myime 一键检查脚本
# 用法: sh tools/all_checks.sh
#
# 在改完 trime.yaml 或词库后跑一遍，防止把布局/词库改坏。

set -e
cd "$(dirname "$0")/.."

# 脚本会输出 ✓ / ✗ / → 等非 ASCII 字符。Windows 默认控制台编码是 GBK，
# 不强制 UTF-8 时 Python 会抛 UnicodeEncodeError，导致第 1 步就中断。
# 同时设置 PYTHONUTF8 覆盖 Python 3.7+ 的 UTF-8 模式。
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

TRIME=app/src/main/assets/shared/trime.yaml
DICT=app/data/rime/myvocab/mydomain.dict.yaml

echo "############################################################"
echo "#  1/10  面板同步 + 关键词合并检查"
echo "############################################################"
python3 tools/sync_panels.py --check
python3 tools/gen_keywords.py --check

echo ""
echo "############################################################"
echo "#  2/10  键盘布局校验（每行权重必须 = 100）"
echo "############################################################"
python3 tools/check_layout.py "$TRIME"

echo ""
echo "############################################################"
echo "#  3/10  键盘引用完整性（键名能否解析，防哑键）"
echo "############################################################"
python3 tools/check_key_refs.py "$TRIME"

echo ""
echo "############################################################"
echo "#  4/10  词库格式校验"
echo "############################################################"
python3 tools/check_dict.py "$DICT"

echo ""
echo "############################################################"
echo "#  5/10  输入模拟（全拼 / 简拼 / 用户学习）"
echo "############################################################"
python3 tools/test_input_sim.py "$DICT"

echo ""
echo "############################################################"
echo "#  6/10  关键词联想检查"
echo "############################################################"
python3 tools/check_keywords.py "$DICT"

echo ""
echo "############################################################"
echo "#  7/10  英文纠错：编辑距离算法"
echo "############################################################"
python3 tools/test_edit_distance.py

echo ""
echo "############################################################"
echo "#  8/10  英文纠错：词表判定（召回 / 误纠）"
echo "############################################################"
python3 tools/test_corrector_words.py

echo ""
echo "############################################################"
echo "#  9/10  英文纠错：输入缓冲状态机"
echo "############################################################"
python3 tools/test_english_buffer.py

echo ""
echo "############################################################"
echo "#  10/10  英文纠错：核心算法（Kotlin 逻辑的 Python 镜像）"
echo "############################################################"
python3 tools/test_english_corrector.py

echo ""
echo "全部检查通过 ✓"
