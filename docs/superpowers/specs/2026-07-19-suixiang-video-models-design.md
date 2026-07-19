# 随想视频协议修复与紧凑模型选择设计

## 背景

视频模式当前已接入 Google Veo、火山方舟 Seedance，以及随想网关中的 Grok、即梦模型。现有实现把两个随想供应商都发送到 `POST /v1/chat/completions`，因此生产环境出现两类确定性错误：

- Grok 返回 HTTP 400，因为视频模型没有使用 Grok 视频生成协议。
- 即梦返回 `Cannot POST /v1/chat/completions`，因为该供应商不提供此路由。

前端同时增加了六模型横向卡片栏，挤占操作区。最终确认的交互是复用图片生成界面原有的“AI 模型”位置，只增加供应商下拉框和一个很短的模型等级滑条。

## 目标

1. 按供应商使用真实视频端点，停止调用 `/chat/completions`。
2. 保留现有异步任务、账号并发、单任务取消、计费、历史、播放和下载链路。
3. 视频模式仍在原“AI 模型”位置选择，不新增卡片栏、状态区或独立配置面板。
4. 下拉框选择供应商，约 `82px` 的短滑条选择该供应商内部模型。
5. 只有拖动滑条时才显示当前模型名称，松手立即隐藏。
6. API Key 只保存在服务器环境变量中，不进入源码、前端、日志或 Git。

## 非目标

- 不改动图片生成模式的模型选择样式和行为。
- 不改变账号并发额度、视频计费公式或任务取消规则。
- 不增加模型说明卡、能力状态面板、价格标签和常驻模型文案。
- 不通过前端直接调用随想，也不向浏览器暴露供应商 Key。

## 模型分组与顺序

后端模型目录继续作为唯一白名单，并增加稳定的供应商 ID、供应商内等级和排序：

| 供应商 ID | 下拉显示 | 等级 | 模型 ID |
|---|---|---:|---|
| `google` | Google | 0 | `veo-3.1-generate-preview` |
| `seedance` | 火山方舟 | 0 | `doubao-seedance-2-0-260128` |
| `suixiang-grok` | Grok | 0 | `grok-imagine-video` |
| `suixiang-grok` | Grok | 1 | `grok-imagine-video-1.5` |
| `suixiang-jimeng` | 即梦 | 0 | `as-sd2.0-fast` |
| `suixiang-jimeng` | 即梦 | 1 | `video-ds-2.0` |

等级只表示同一供应商内从基础到高级的顺序，不跨供应商比较。Google 和火山当前各只有一个模型，滑条保持可见但禁用，避免切换供应商时布局跳动。

## 后端架构

### 模型目录

`VideoModelCatalog` 负责：

- 模型白名单和供应商路由。
- 供应商展示名、稳定 ID、等级和顺序。
- 配置状态，只返回 `configured: true/false`。
- 根据模型读取对应服务器凭据。

`VideoController` 不根据模型字符串前缀猜测供应商。

### 供应商协议

`OpenAiCompatibleVideoService` 保留为随想视频入口，但内部必须拆成两种明确策略，不能共享聊天补全请求体：

**Grok**

- 创建：`POST {baseUrl}/videos/generations`
- JSON 字段：`model`、`prompt`、`duration`、`aspect_ratio`、`resolution`
- 有参考图时使用 `image_url`；当前多图输入只取第一张作为首帧，避免发送网关不支持的未知字段。
- 创建响应如果直接含视频 URL，立即下载；如果返回 `request_id` 或 `id`，轮询 `GET {baseUrl}/videos/{id}`。

**即梦**

- 创建：`POST {baseUrl}/videos`
- 请求字段按 OpenAI Videos 语义发送：`model`、`prompt`、`seconds`、`size`。
- 有参考图时使用 multipart 的 `input_reference` 文件字段。
- 创建响应如果直接含视频 URL，立即下载；如果返回 `id` 或任务 ID，轮询 `GET {baseUrl}/videos/{id}`。
- 成功状态没有媒体 URL 时，从 `GET {baseUrl}/videos/{id}/content` 下载结果。

两个策略共享鉴权、代理、超时、取消注册、响应脱敏、临时文件和原子落盘能力，但不共享创建路径和请求字段。

### 异步状态

轮询统一识别以下状态语义：

- 等待：`queued`、`pending`、`processing`、`in_progress`、`running`
- 成功：`completed`、`succeeded`、`success`、`done`
- 失败：`failed`、`error`、`cancelled`、`canceled`、`expired`

每次轮询前检查本地任务取消状态。取消时中断当前 OkHttp `Call`，停止后续轮询和下载，并删除 `.part` 文件。轮询达到超时上限后返回明确超时错误，不无限占用账号并发。

## 数据流

1. 前端进入视频模式，请求 `GET /api/video/models`。
2. 前端按 `providerId` 分组，原位置下拉框展示四个供应商。
3. 选择供应商后，短滑条的离散端点映射到该供应商内部模型。
4. `agentSelect` 隐藏值继续保存最终模型 ID，提交接口不增加重复参数。
5. `POST /api/video/generate` 使用模型目录校验模型和配置状态。
6. 未配置 Key 时在创建内部任务和计费记录前返回 HTTP 400。
7. Google、火山继续走现有服务；Grok、即梦分别走对应视频协议。
8. 供应商结果下载到用户独立临时目录，再由现有任务结果、历史和下载接口展示。

## 前端交互

- 删除视频配置区顶部的六模型横向卡片栏及其加载状态。
- 视频模式不隐藏原有 `.agent-select-control`，而是把原下拉框内容切换为供应商选项。
- 下拉框沿用现有 `gf-select` 结构、尺寸、弹出方向和选中样式。
- 在下拉框正下方增加约 `82px` 的原生离散滑条，两个模型时只有左右两个落点。
- 滑条不会按视口宽度拉伸；移动端同样保持短尺寸。
- `pointerdown` 或键盘调整期间显示模型名称浮层，`pointerup`、`pointercancel`、`blur` 后隐藏。
- 单模型供应商滑条禁用，但控件高度和位置不变。
- 离开视频模式后恢复图片模型列表和原来的图片模型选中逻辑。
- 未配置供应商可以被选中；提交时由后端返回明确配置错误，前端必须释放提交锁。

## 错误处理

- 未知模型：HTTP 400，返回“不支持的视频模型”。
- Key 未配置：HTTP 400，并指出对应环境变量名。
- 401/403：提示检查供应商 Key，不输出 Key。
- 429：提示供应商限流，任务失败且不按成功计费。
- 4xx 参数错误：保留供应商状态码和经过截断、脱敏的错误摘要。
- 网络或轮询超时：明确提示供应商连接或生成超时。
- 失败状态：优先提取供应商 `error.message` 或 `message`。
- 响应中没有任务 ID、媒体 URL或可下载内容：返回响应结构摘要，禁止把 JSON、HTML 或聊天文本保存成 MP4。
- 下载失败或任务取消：删除不完整文件，不写成功历史。

## 测试

### 后端

- `VideoModelCatalogTest`：供应商分组、模型等级、顺序、配置状态和凭据隔离。
- `OpenAiCompatibleVideoServiceTest`：
  - Grok 必须调用 `/videos/generations`，并发送 `duration`、`aspect_ratio`。
  - 即梦必须调用 `/videos`，并发送 `seconds`、`size`。
  - 两种异步响应的轮询、成功下载、失败状态、超时和取消。
  - 任何请求都不能再出现 `/chat/completions`。
- `VideoControllerProviderTest`：目录字段不泄密、四个随想模型路由正确、未配置时不创建计费记录。

### 前端

- 静态契约检查：旧 `videoModelRail` 不存在，原模型控件保持显示，存在供应商下拉状态和短滑条。
- Playwright：
  - 视频模式只增加下拉框和短滑条。
  - Grok、即梦各有两个离散端点，模型 ID与提交表单同步。
  - Google、火山滑条禁用。
  - 浮层只在拖动或键盘调整期间显示。
  - 退出视频模式后图片模型选择恢复。
  - 桌面与移动端无挤压、遮挡或页面横向溢出。

### 发布验收

完整执行 `docs/运行与发布验收门禁.md`。真实供应商生成属于可能计费验证，只有在明确授权后执行最低规格调用；未执行时必须标记为 `BLOCKED`，不能声称真实生成已通过。

## 部署

1. 本地全量测试通过后打包 JAR，并记录 JAR 与外置前端文件 SHA-256。
2. 备份生产 JAR、受影响前端文件和环境文件。
3. 上传临时文件并校验哈希，随后原子替换。
4. 重启 `ai-studio`，检查服务状态、HTTP、认证、模型目录和日志。
5. 运行生产浏览器回归，确认模型选择、并发和单任务取消互不影响。
6. 真实 Grok、即梦生成需单独获得计费授权后执行。
