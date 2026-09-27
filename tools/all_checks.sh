#!/bin/sh
# myime 一键检查脚本
# 用法: sh tools/all_checks.sh
#
# 在改完 trime.yaml 或词库后跑一遍，防止把布局/词库改坏。

set -e
cd "$(dirname "$0")/.."

TRIME=app/src/main/assets/shared/trime.yaml
DICT=app/data/rime/myvocab/mydomain.dict.yaml

echo "############################################################"
echo "#  1/5  面板同步 + 关键词合并检查"
echo "############################################################"
python3 tools/sync_panels.py --check
python3 tools/gen_keywords.py --check

echo ""
echo "############################################################"
echo "#  2/5  键盘布局校验（每行权重必须 = 100）"
echo "############################################################"
python3 tools/check_layout.py "$TRIME"

echo ""
echo "############################################################"
echo "#  3/5  词库格式校验"
echo "############################################################"
python3 tools/check_dict.py "$DICT"

echo ""
echo "############################################################"
echo "#  4/5  输入模拟（全拼 / 简拼 / 用户学习）"
echo "############################################################"
python3 tools/test_input_sim.py "$DICT"

echo ""
echo "############################################################"
echo "#  5/5  关键词联想检查"
echo "############################################################"
python3 tools/check_keywords.py "$DICT"

echo ""
echo "全部检查通过 ✓"
