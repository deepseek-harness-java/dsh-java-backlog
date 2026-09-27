# dsh-java-backlog · Backlog Shaper（需求整理台）

> DSH 独立 AI 产品矩阵 #18 · 端口 **8109** · 形态参照 `dsh-java-mysql`：独立工具型产品，
> 本身是完整可用的需求整理后台 + DSH Java Native 插件（AI 需求分析师），而非"业务系统 + 右侧 AI 助手"。

## 产品定位

把群聊里、邮件里、客服工单里捞出来的**零散需求/用户反馈**变成结构化的 backlog 草稿：

- **条目解析**：支持 `1.` / `①` / `-` / 换行分隔，输出编号 + 类型（feature/bug/perf/security/ux）+ 置信度；
- **主题聚类**：8 类主题（账号登录/支付订单/性能稳定/移动端/报表数据/安全合规/搜索筛选/通知消息）；
- **用户故事**：`作为<角色>，我希望<能力>，以便<价值>` 草稿，角色与价值由文本信号推断；
- **优先级矩阵**：价值/成本双维打分（1~9）→ 四象限（速赢/战略投入/顺手做/暂缓）+ P0~P3 建议；
- **验收标准**：每条需求 Given/When/Then 草稿（主流程 + 异常流程）。

AI 的角色是"熟练的需求分析师"：一句话让它读完全部反馈、聚类、写故事、给排期建议。

## AI 工具（插件 `dsh-java-backlog-plugin`，前缀 `plugin__dsh-java-backlog-plugin__bl_*`）

| 工具 | 作用 |
|------|------|
| `bl_ingest` | 零散文本 → 结构化条目（编号/类型/置信度） |
| `bl_group_themes` | 8 类主题聚类 + 每簇样本 |
| `bl_to_user_story` | 条目 → 用户故事草稿（标注置信度） |
| `bl_priority_matrix` | 价值/成本打分 + 四象限 + P0~P3 建议 |
| `bl_acceptance_draft` | 条目 → Given/When/Then 验收草稿 |

## 安全红线

- **不代替拍板优先级**——所有打分与 P0~P3 均为规则建议，systemPrompt 强制注明「需团队拍板」；
- **不连接任何项目管理 SaaS**——不写 Jira/TAPD/飞书，只分析粘贴文本；
- **不外发数据**——纯本地分析，无上传通道；
- **不臆造条目**——AI 被要求只引用工具返回的编号与打分。

## 架构

```
backlog-app (Spring Boot :8109)                backlog-plugin (DSH Java Native Plugin)
├─ POST /api/backlog/ingest          ─┐        ├─ DshBacklogPlugin extends AbstractHarnessPlugin
├─ POST /api/backlog/themes           │ 同一   │   ├─ 5 × AbstractTool (bl_*)
├─ POST /api/backlog/user-stories     │ Backlog│   ├─ systemPrompt（需求分析师人设 + 红线）
├─ POST /api/backlog/priority-matrix  │ Service│   └─ PRE_TOOL_USE hook（审计上下文注入）
├─ POST /api/backlog/acceptance       └─       └─ SPI: META-INF/services/...JavaHarnessPlugin
└─ POST /api/assistant/stream ──→ harness :8090 /api/agent/stream（SSE 转发）
```

UI：`static/index.html` + `app.js`（会话管理、SSE 流式渲染、条目/主题/故事/验收四个工作台、优先级矩阵弹窗）。

## 运行

```bash
# 1. 启动 app（harness 8090 需已在运行）
SERVER__PORT= java -jar dsh-java-backlog-app/target/*.jar --server.port=8109

# 2. 安装并激活插件
curl --noproxy '*' -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId":"dsh-java-backlog-plugin","jarName":"dsh-java-backlog-plugin-0.1.0.jar",
  "displayName":"Backlog Shaper","pluginVersion":"0.1.0","runtimeType":"JAVA_NATIVE",
  "sourcePath":"<abs-path>/dsh-java-backlog-plugin-0.1.0.jar",
  "entrypoint":"cn.xiaofuge.dsh.bl.plugin.DshBacklogPlugin","installMode":"COPY"}'
curl --noproxy '*' -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' \
  -d '{"pluginId":"dsh-java-backlog-plugin"}'

# 3. 打开 http://127.0.0.1:8109
```

## E2E

`backlog_shaper_e2e.py` — 8 用例 / 19 断言，全部通过：

| 用例 | 验证点 |
|------|--------|
| T0 | ingest 解析 6 条，类型识别含 bug/perf |
| T1 | 聚类含「账号与登录」「性能与稳定」，6 条全覆盖 |
| T2 | 用户故事格式（作为/希望/以便），角色识别「管理员/财务」 |
| T3 | 优先级矩阵打分排序，验证码 bug 得 P0，注明团队拍板 |
| T4 | 验收草稿 Given/When/Then 结构完整 |
| T5 | 空输入 → 400 |
| T6 | AI 对话调用 bl_ingest / bl_priority_matrix（SSE step_break 断言） |
| T7 | AI 回复注明「建议需团队拍板」（红线断言） |

## 截图

- `screenshots/01-home.png` — 需求整理台首页
- `screenshots/02-ai-drawer.png` — AI 对话整理（调用 bl_* 工具）
- `screenshots/03-safety.png` — 优先级矩阵（建议仅供参考，需团队拍板）
