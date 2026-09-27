package cn.xiaofuge.dsh.bl.service;

import cn.xiaofuge.dsh.bl.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backlog Shaper 需求整理台（纯文本输入，不连任何项目管理 SaaS）：
 * - bl_ingest：把零散需求/反馈文本解析成结构化条目（编号/类型/置信度）
 * - bl_group_themes：按主题关键词聚类（账号/支付/性能/移动端/报表/安全/搜索/通知…）
 * - bl_to_user_story：条目 → 用户故事草稿「作为…我希望…以便…」
 * - bl_priority_matrix：价值/成本打分 → 价值-成本四象限 + P0~P3 建议
 * - bl_acceptance_draft：条目 → Given/When/Then 验收标准草稿
 * 红线：不代替拍板优先级（只给建议）、不外发数据。
 */
@Service
public class BacklogService {

    /** 主题关键词规则 */
    private record Theme(String name, String[] keywords) {}

    private static final List<Theme> THEMES = List.of(
            new Theme("账号与登录", new String[]{"登录", "注册", "账号", "密码", "验证码", "sso", "oauth", "权限"}),
            new Theme("支付与订单", new String[]{"支付", "下单", "订单", "退款", "优惠券", "发票", "结账", "微信", "支付宝"}),
            new Theme("性能与稳定", new String[]{"慢", "卡顿", "超时", "崩溃", "性能", "并发", "卡死", "加载", "白屏"}),
            new Theme("移动端体验", new String[]{"app", "ios", "android", "手机", "移动端", "小程序", "推送", "闪退"}),
            new Theme("报表与数据", new String[]{"报表", "导出", "统计", "图表", "看板", "数据", "excel", "csv"}),
            new Theme("安全合规", new String[]{"安全", "加密", "脱敏", "审计", "合规", "隐私", "泄露", "gdpr"}),
            new Theme("搜索与筛选", new String[]{"搜索", "筛选", "过滤", "排序", "关键词", "检索"}),
            new Theme("通知与消息", new String[]{"通知", "提醒", "消息", "邮件", "短信", "站内信", "订阅"})
    );

    /** 类型信号 */
    private static final String[] BUG_WORDS = {"报错", "bug", "崩溃", "闪退", "白屏", "异常", "失败", "错误", "修复", "502", "500"};
    private static final String[] UX_WORDS = {"难用", "体验", "不方便", "麻烦", "希望", "建议", "最好", "优化", "改版"};
    private static final String[] PERF_WORDS = {"慢", "卡", "超时", "耗时", "性能", "并发", "量大"};
    private static final String[] SECURITY_WORDS = {"安全", "泄露", "加密", "越权", "隐私", "脱敏", "攻击"};

    /** 价值信号（用户面广/高频/付费相关/强烈情绪） */
    private static final String[] VALUE_WORDS = {"所有用户", "大量", "经常", "总是", "无法", "必须", "投诉", "流失", "付费", "vip", "核心", "紧急", "尽快", "阻塞", "blocked"};
    /** 成本信号（涉及多系统/迁移/重构/新集成） */
    private static final String[] COST_WORDS = {"重构", "迁移", "对接", "集成", "改造", "架构", "第三方", "多系统", "全量", "新建", "从零", "底层"};

    private static final String[] ROLE_HINTS = {"用户", "管理员", "运营", "客服", "开发", "销售", "财务", "老板", "会员", "新用户", "买家", "卖家", "审核员"};

    public Map<String, Object> ingest(String text) {
        List<Map<String, Object>> items = parseItems(text);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("itemCount", items.size());
        r.put("items", items.stream().limit(60).toList());
        if (items.size() > 60) r.put("truncated", true);
        return r;
    }

    public Map<String, Object> groupThemes(String text) {
        List<Map<String, Object>> items = parseItems(text);
        Map<String, List<Map<String, Object>>> clusters = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String haystack = String.valueOf(item.get("raw")).toLowerCase();
            String best = "其他";
            int bestScore = 0;
            for (Theme t : THEMES) {
                int score = 0;
                for (String k : t.keywords()) {
                    if (haystack.contains(k.toLowerCase())) score += k.length();
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = t.name();
                }
            }
            clusters.computeIfAbsent(best, k -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : clusters.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("theme", e.getKey());
            s.put("count", e.getValue().size());
            s.put("itemIds", e.getValue().stream().map(m -> m.get("id")).toList());
            s.put("samples", e.getValue().stream().map(m -> abbreviate(String.valueOf(m.get("raw")), 50)).limit(3).toList());
            summary.add(s);
        }
        summary.sort((a, b) -> (int) b.get("count") - (int) a.get("count"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("itemCount", items.size());
        r.put("themeCount", summary.size());
        r.put("themes", summary);
        return r;
    }

    public Map<String, Object> toUserStory(String text) {
        List<Map<String, Object>> items = parseItems(text);
        List<Map<String, Object>> stories = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String raw = String.valueOf(item.get("raw"));
            String role = detectRole(raw);
            String want = extractWant(raw);
            String benefit = extractBenefit(raw);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", item.get("id"));
            s.put("raw", abbreviate(raw, 80));
            s.put("story", "作为" + role + "，我希望" + want + "，以便" + benefit + "。");
            s.put("type", item.get("type"));
            s.put("confidence", item.get("confidence"));
            stories.add(s);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("storyCount", stories.size());
        r.put("stories", stories.stream().limit(40).toList());
        r.put("note", "用户故事为规则草稿，角色与价值由信号推断，请人工确认后再入池");
        return r;
    }

    public Map<String, Object> priorityMatrix(String text) {
        List<Map<String, Object>> items = parseItems(text);
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String raw = String.valueOf(item.get("raw")).toLowerCase();
            int value = 3;
            List<String> signals = new ArrayList<>();
            for (String w : VALUE_WORDS) {
                if (raw.contains(w)) {
                    value += 2;
                    signals.add("价值信号:" + w);
                    break;
                }
            }
            String type = String.valueOf(item.get("type"));
            if ("bug".equals(type)) {
                value += 3;
                signals.add("缺陷类默认高价值");
            }
            int cost = 3;
            for (String w : COST_WORDS) {
                if (raw.contains(w)) {
                    cost += 2;
                    signals.add("成本信号:" + w);
                    break;
                }
            }
            value = Math.min(value, 9);
            cost = Math.min(cost, 9);
            String quadrant;
            if (value >= 6 && cost <= 5) quadrant = "速赢 quick-win";
            else if (value >= 6) quadrant = "战略投入 big-bet";
            else if (cost <= 5) quadrant = "顺手做 fill-in";
            else quadrant = "暂缓 deprioritize";
            String priority = value >= 6 && cost <= 5 ? "P0 建议优先" : value >= 6 ? "P1 排期" : cost <= 5 ? "P2 有余力再做" : "P3 暂缓";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.get("id"));
            m.put("raw", abbreviate(String.valueOf(item.get("raw")), 80));
            m.put("value", value);
            m.put("cost", cost);
            m.put("quadrant", quadrant);
            m.put("suggestedPriority", priority);
            m.put("signals", signals);
            scored.add(m);
        }
        scored.sort((a, b) -> {
            int va = (int) a.get("value"), vb = (int) b.get("value");
            if (va != vb) return vb - va;
            int ca = (int) a.get("cost"), cb = (int) b.get("cost");
            return ca - cb;
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("itemCount", items.size());
        r.put("matrix", scored.stream().limit(40).toList());
        r.put("note", "打分仅为规则建议，最终优先级由团队拍板");
        return r;
    }

    public Map<String, Object> acceptanceDraft(String text) {
        List<Map<String, Object>> items = parseItems(text);
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String raw = String.valueOf(item.get("raw"));
            String want = extractWant(raw);
            String role = detectRole(raw);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", item.get("id"));
            c.put("raw", abbreviate(raw, 80));
            List<Map<String, String>> steps = new ArrayList<>();
            Map<String, String> s1 = new LinkedHashMap<>();
            s1.put("given", role + "已登录且具备必要权限");
            s1.put("when", role + "使用「" + abbreviate(want, 40) + "」功能");
            s1.put("then", "操作成功且结果符合预期，无报错");
            steps.add(s1);
            Map<String, String> s2 = new LinkedHashMap<>();
            s2.put("given", "输入异常或网络中断");
            s2.put("when", role + "重复提交或输入非法内容");
            s2.put("then", "给出明确错误提示，不产生脏数据");
            steps.add(s2);
            c.put("acceptance", steps);
            cases.add(c);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("caseCount", cases.size());
        r.put("cases", cases.stream().limit(40).toList());
        r.put("note", "验收标准为 Given/When/Then 草稿，请评审后补充边界条件");
        return r;
    }

    // ---------- helpers ----------

    /** 把零散文本切成条目：支持 1. / ① / - / 换行分隔 */
    private List<Map<String, Object>> parseItems(String text) {
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "text 不能为空");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        String[] lines = text.trim().split("\\n+");
        int idx = 0;
        for (String line : lines) {
            String cleaned = line.replaceFirst("^\\s*((\\d+[.、)．])|([①②③④⑤⑥⑦⑧⑨⑩])|([-*•]))\\s*", "").trim();
            if (cleaned.length() < 4) continue;
            idx++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", idx);
            m.put("raw", abbreviate(cleaned, 300));
            m.put("type", classify(cleaned));
            m.put("confidence", confidence(cleaned));
            items.add(m);
        }
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "未能从文本中解析出需求条目（每行至少 4 个字符）");
        }
        return items;
    }

    private String classify(String raw) {
        String low = raw.toLowerCase();
        for (String w : BUG_WORDS) if (low.contains(w)) return "bug";
        for (String w : SECURITY_WORDS) if (low.contains(w)) return "security";
        for (String w : PERF_WORDS) if (low.contains(w)) return "perf";
        for (String w : UX_WORDS) if (low.contains(w)) return "ux";
        return "feature";
    }

    private String confidence(String raw) {
        boolean hasRole = false;
        for (String r : ROLE_HINTS) if (raw.contains(r)) { hasRole = true; break; }
        boolean hasVerb = Pattern.compile("(希望|需要|想要|支持|增加|优化|修复|新增|提供|可以|能)").matcher(raw).find();
        if (hasRole && hasVerb) return "high";
        if (hasRole || hasVerb) return "medium";
        return "low";
    }

    private String detectRole(String raw) {
        for (String r : ROLE_HINTS) {
            if (raw.contains(r)) return r;
        }
        return "用户";
    }

    /** 提取「想要什么」：优先取希望/需要/支持/优化后的子句，否则取主干 */
    private String extractWant(String raw) {
        Matcher m = Pattern.compile("(?:希望|需要|想要|建议|最好能?|支持|新增|增加|优化|修复)(能)?(.{2,40})").matcher(raw);
        if (m.find()) {
            String want = (m.group(1) == null ? "" : "能") + m.group(2).trim();
            return trimWant(want);
        }
        return trimWant(raw);
    }

    private String trimWant(String want) {
        want = want.replaceFirst("[。；;，,！！？?].*$", "").trim();
        if (want.isEmpty()) return "相关能力";
        return want;
    }

    /** 提取「以便什么」：优先取以便/从而/方便之后的子句，否则按类型给默认价值 */
    private String extractBenefit(String raw) {
        Matcher m = Pattern.compile("(?:以便|从而|方便|这样)(.{2,40})").matcher(raw);
        if (m.find()) {
            String b = m.group(1).trim();
            b = b.replaceFirst("[。；;！！？?].*$", "").trim();
            if (!b.isEmpty()) return b;
        }
        return switch (classify(raw)) {
            case "bug" -> "避免问题再次影响使用";
            case "perf" -> "减少等待时间提升效率";
            case "security" -> "保障数据与隐私安全";
            case "ux" -> "获得更顺畅的使用体验";
            default -> "更好地完成目标任务";
        };
    }

    private String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
