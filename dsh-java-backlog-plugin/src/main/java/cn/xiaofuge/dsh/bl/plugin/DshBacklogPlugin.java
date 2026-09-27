package cn.xiaofuge.dsh.bl.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshBacklogPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-backlog-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshBacklogPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new IngestTool(),
                new GroupThemesTool(),
                new UserStoryTool(),
                new PriorityMatrixTool(),
                new AcceptanceTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("backlog-backlogshaper-capabilities", 20, """
                ## DSH Backlog Shaper Plugin
                - 全部工具只读分析粘贴进来的需求/反馈文本，不连接任何项目管理 SaaS，不写入任何外部系统。
                - 用户没贴需求文本时先索要，禁止臆造需求条目。
                - 优先级与打分只给建议，必须注明「需团队拍板」，不替用户做最终决定。
                - 用户故事/验收标准是规则草稿，提醒人工确认后再入池。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__bl_")) {
                return PluginHookResult.context("DSH Backlog Shaper audit: read-only shaping, priorities are suggestions only.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_BACKLOG_ADMIN_URL", "http://127.0.0.1:8109")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String textBody(Map<String, Object> args) {
        return "{\"text\":\"" + json(str(args, "text")) + "\"}";
    }

    private class IngestTool extends AbstractTool {
        @Override public String name() { return "bl_ingest"; }
        @Override public String description() { return "把零散需求/用户反馈文本解析成结构化条目（编号/类型 feature|bug|perf|security|ux/置信度），支持 1.、①、-、换行分隔。开始整理前先调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("零散需求/反馈文本，每行一条"))
                    .prop("adminBaseUrl", stringSchema("可选，Backlog Shaper 后台地址，默认 http://127.0.0.1:8109"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backlog/ingest", textBody(args), args));
        }
    }

    private class GroupThemesTool extends AbstractTool {
        @Override public String name() { return "bl_group_themes"; }
        @Override public String description() { return "把需求条目按主题聚类：账号与登录/支付与订单/性能与稳定/移动端体验/报表与数据/安全合规/搜索与筛选/通知与消息，返回每簇数量与样本。用户问「这些反馈分几类」时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("零散需求/反馈文本，每行一条"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backlog/themes", textBody(args), args));
        }
    }

    private class UserStoryTool extends AbstractTool {
        @Override public String name() { return "bl_to_user_story"; }
        @Override public String description() { return "把需求条目转成用户故事草稿：「作为<角色>，我希望<能力>，以便<价值>」。角色与价值由文本信号推断，结果标注置信度。用户要写用户故事时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("零散需求/反馈文本，每行一条"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backlog/user-stories", textBody(args), args));
        }
    }

    private class PriorityMatrixTool extends AbstractTool {
        @Override public String name() { return "bl_priority_matrix"; }
        @Override public String description() { return "对需求条目做价值/成本打分（1~9），输出价值-成本四象限（速赢/战略投入/顺手做/暂缓）与 P0~P3 建议，按价值降序排序。用户问「先做哪个/怎么排期」时调用。注意：结果只是建议，需团队拍板。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("零散需求/反馈文本，每行一条"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backlog/priority-matrix", textBody(args), args));
        }
    }

    private class AcceptanceTool extends AbstractTool {
        @Override public String name() { return "bl_acceptance_draft"; }
        @Override public String description() { return "为需求条目生成 Given/When/Then 验收标准草稿（主流程 + 异常流程各一条）。用户要写验收标准/测试要点时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("零散需求/反馈文本，每行一条"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backlog/acceptance", textBody(args), args));
        }
    }
}
