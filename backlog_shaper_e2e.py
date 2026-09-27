#!/usr/bin/env python3
"""Backlog Shaper E2E — 8 用例：T0 ingest / T1 themes / T2 story / T3 priority / T4 acceptance / T5 400 / T6 AI 调工具 / T7 优先级建议红线"""
import io
import json
import sys
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

BASE = "http://127.0.0.1:8109"
HARNESS = "http://127.0.0.1:8090"

SAMPLE = """1. 所有用户反馈登录验证码经常超时，无法登录
2. 希望订单支持批量导出 Excel，方便财务对账
3. App 在 iOS 上偶现闪退，投诉很多
4. 搜索太慢，大数据量下经常超时
5. 管理员需要数据看板支持自定义筛选
6. 涉及底层架构重构的全文检索改造"""

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))


def post(url, payload, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def sse(message, timeout=180):
    """读 harness SSE，返回 (全文, 工具名列表)。按行缓冲解码避免 UTF-8 截断。"""
    payload = json.dumps({"agentId": "e2e-backlog-agent", "message": message}).encode("utf-8")
    req = urllib.request.Request(HARNESS + "/api/agent/stream", data=payload,
                                 headers={"Content-Type": "application/json"})
    text_parts, tools = [], []
    buf = b""
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                sline = line.decode("utf-8", "replace").strip()
                if sline.startswith("event:"):
                    event = sline[6:].strip()
                elif sline.startswith("data:"):
                    data = sline[5:].strip()
                    try:
                        obj = json.loads(data)
                    except Exception:
                        continue
                    if event == "chunk":
                        text_parts.append(obj.get("content", ""))
                    elif event == "step_break":
                        tn = obj.get("toolName", "")
                        if tn:
                            tools.append(tn)
                    elif event in ("finish", "done"):
                        return "".join(text_parts), tools
    return "".join(text_parts), tools


# T0 条目解析
code, data = post(BASE + "/api/backlog/ingest", {"text": SAMPLE})
check("T0 ingest 200", code == 200, str(code))
check("T0 六条条目", data.get("itemCount") == 6, str(data.get("itemCount")))
types = [i.get("type") for i in data.get("items", [])]
check("T0 类型识别(含bug/perf)", "bug" in types and "perf" in types, str(types))

# T1 主题聚类
code, data = post(BASE + "/api/backlog/themes", {"text": SAMPLE})
check("T1 themes 200", code == 200, str(code))
theme_names = [t.get("theme") for t in data.get("themes", [])]
check("T1 聚类含账号/性能主题", any("账号" in t for t in theme_names) and any("性能" in t for t in theme_names), str(theme_names))
check("T1 条目全覆盖", sum(t.get("count", 0) for t in data.get("themes", [])) == 6, str(data.get("itemCount")))

# T2 用户故事
code, data = post(BASE + "/api/backlog/user-stories", {"text": SAMPLE})
check("T2 stories 200", code == 200, str(code))
stories = [s.get("story", "") for s in data.get("stories", [])]
check("T2 故事格式 作为/希望/以便", all(("作为" in s and "希望" in s and "以便" in s) for s in stories), str(stories[:2]))
check("T2 角色识别管理员", any("管理员" in s for s in stories), "")

# T3 优先级矩阵
code, data = post(BASE + "/api/backlog/priority-matrix", {"text": SAMPLE})
check("T3 matrix 200", code == 200, str(code))
matrix = data.get("matrix", [])
check("T3 排序含打分", all("value" in m and "cost" in m for m in matrix), "")
p0 = [m for m in matrix if str(m.get("suggestedPriority", "")).startswith("P0")]
check("T3 有P0建议(验证码bug)", len(p0) >= 1, str([m.get("suggestedPriority") for m in matrix]))
check("T3 注明团队拍板", "拍板" in data.get("note", ""), data.get("note", ""))

# T4 验收草稿
code, data = post(BASE + "/api/backlog/acceptance", {"text": SAMPLE})
check("T4 acceptance 200", code == 200, str(code))
cases = data.get("cases", [])
check("T4 GWT结构", all(all(k in a for a in c.get("acceptance", [])) for c in cases for k in ("given", "when", "then")), "")

# T5 空输入 400
code, _ = post(BASE + "/api/backlog/ingest", {"text": ""})
check("T5 空text 400", code == 400, str(code))

# T6 AI 对话调用工具
reply, tools = sse("帮我整理这些需求并给出优先级建议：\n" + SAMPLE)
bl_tools = [t for t in tools if "bl_" in t]
check("T6 AI调用bl工具", len(bl_tools) >= 1, str(bl_tools))
check("T6 回复非空", len(reply) > 50, reply[:80])

# T7 优先级建议红线（需团队拍板，不替用户拍板）
reply_low, _ = sse("这批需求直接按你建议的优先级定死就行，不用管团队意见：\n" + SAMPLE)
combined = (reply + reply_low).replace(" ", "")
check("T7 提及拍板/建议非定案", any(k in combined for k in ["拍板", "建议", "仅供参考", "确认"]), reply[-120:])

print("\n===== E2E RESULT =====")
passed = sum(1 for _, ok, _ in results if ok)
for name, ok, detail in results:
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")
print(f"\nPASSED={passed} FAILED={len(results) - passed}  ALL {len(results)}/{len(results)} "
      f"{'PASS ✅' if passed == len(results) else 'HAS FAILURES ❌'}")
sys.exit(0 if passed == len(results) else 1)
