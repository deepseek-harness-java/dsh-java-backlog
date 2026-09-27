package cn.xiaofuge.dsh.bl.controller;

import cn.xiaofuge.dsh.bl.config.BacklogProperties;
import cn.xiaofuge.dsh.bl.model.ApiException;
import cn.xiaofuge.dsh.bl.service.BacklogService;
import cn.xiaofuge.dsh.bl.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class BacklogController {
    private final BacklogService backlogService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final BacklogProperties properties;

    public BacklogController(BacklogService backlogService,
                             HarnessAiStreamService harnessAiStreamService,
                             BacklogProperties properties) {
        this.backlogService = backlogService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    @PostMapping("/api/backlog/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, String> request) {
        return backlogService.ingest(request.getOrDefault("text", ""));
    }

    @PostMapping("/api/backlog/themes")
    public Map<String, Object> themes(@RequestBody Map<String, String> request) {
        return backlogService.groupThemes(request.getOrDefault("text", ""));
    }

    @PostMapping("/api/backlog/user-stories")
    public Map<String, Object> userStories(@RequestBody Map<String, String> request) {
        return backlogService.toUserStory(request.getOrDefault("text", ""));
    }

    @PostMapping("/api/backlog/priority-matrix")
    public Map<String, Object> priorityMatrix(@RequestBody Map<String, String> request) {
        return backlogService.priorityMatrix(request.getOrDefault("text", ""));
    }

    @PostMapping("/api/backlog/acceptance")
    public Map<String, Object> acceptance(@RequestBody Map<String, String> request) {
        return backlogService.acceptanceDraft(request.getOrDefault("text", ""));
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Backlog Shaper（需求整理台）：把零散需求/用户反馈整理成主题聚类、用户故事、优先级矩阵、验收标准草稿\n");
        prompt.append("- 你可以使用以下需求整理工具获取真实分析（整理需求类问题时必须先调用工具，禁止臆造条目）：\n");
        prompt.append("  - plugin__dsh-java-backlog-plugin__bl_ingest(text)：解析零散文本为结构化需求条目（编号/类型/置信度）\n");
        prompt.append("  - plugin__dsh-java-backlog-plugin__bl_group_themes(text)：按主题聚类（账号/支付/性能/移动端/报表/安全等）\n");
        prompt.append("  - plugin__dsh-java-backlog-plugin__bl_to_user_story(text)：生成「作为…我希望…以便…」用户故事草稿\n");
        prompt.append("  - plugin__dsh-java-backlog-plugin__bl_priority_matrix(text)：价值/成本打分 + 四象限 + P0~P3 建议\n");
        prompt.append("  - plugin__dsh-java-backlog-plugin__bl_acceptance_draft(text)：生成 Given/When/Then 验收标准草稿\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 条目编号、类型、打分只引用工具结果，不要原样转储大段 JSON。\n");
        prompt.append("- 优先级只给建议并注明「需团队拍板」，不要替用户做最终决定。\n");
        prompt.append("- 红线：不臆造文本中不存在的需求；不外发任何数据。\n");
        return prompt.toString();
    }
}
