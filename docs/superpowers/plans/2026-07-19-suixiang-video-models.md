# 随想视频协议修复与紧凑模型选择 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Grok、即梦错误调用 `/chat/completions` 的问题，并在原 AI 模型位置实现供应商下拉框加短模型滑条。

**Architecture:** `VideoModelCatalog` 输出稳定的供应商分组和模型等级；`OpenAiCompatibleVideoService` 内部按 Grok 与即梦拆分创建协议，共享轮询、取消和下载基础能力。前端复用现有 `agentSelectWrapper`，视频模式下把下拉内容切换为供应商，并用离散短滑条同步最终模型 ID。

**Tech Stack:** Java 17、Spring Boot 3.3、OkHttp 4、Jackson、JUnit 5、JDK HttpServer、原生 HTML/CSS/JavaScript、Playwright。

---

### Task 1: 扩展视频模型目录契约

**Files:**
- Modify: `src/main/java/com/elebusiness/service/video/VideoModelCatalog.java`
- Modify: `src/test/java/com/elebusiness/service/video/VideoModelCatalogTest.java`
- Modify: `src/main/java/com/elebusiness/controller/VideoController.java`
- Modify: `src/test/java/com/elebusiness/controller/VideoControllerProviderTest.java`

- [ ] **Step 1: 写模型分组失败测试**

在 `VideoModelCatalogTest` 断言每个模型具有 `providerId`、`level`，并固定两个双模型供应商的顺序：

```java
assertEquals(List.of("grok-imagine-video", "grok-imagine-video-1.5"),
        models.stream()
                .filter(model -> model.providerId().equals("suixiang-grok"))
                .map(VideoModelCatalog.ModelView::id)
                .toList());
assertEquals(List.of(0, 1), models.stream()
        .filter(model -> model.providerId().equals("suixiang-grok"))
        .map(VideoModelCatalog.ModelView::level)
        .toList());
assertEquals("suixiang-jimeng", catalog.require("video-ds-2.0").providerId());
```

在 `VideoControllerProviderTest` 断言 `/api/video/models` 返回 `providerId` 和 `level`，且不包含凭据。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
$env:JAVA_HOME='E:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$mvn='E:\IDEA\IntelliJ IDEA Community Edition 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd'
& $mvn -Dtest=VideoModelCatalogTest,VideoControllerProviderTest test
```

Expected: 编译失败或断言失败，原因是 `ModelView` 尚无 `providerId`、`level`。

- [ ] **Step 3: 最小实现目录字段**

将目录定义扩展为：

```java
private record ModelDefinition(
        String id,
        String name,
        String providerId,
        String providerLabel,
        Provider provider,
        int level) {
}

public record ModelView(
        String id,
        String name,
        String providerId,
        String providerLabel,
        Provider provider,
        int level,
        boolean configured) {
}
```

模型顺序固定为 Google、火山、Grok 基础、Grok 1.5、即梦基础、即梦高级；Controller 映射增加：

```java
"providerId", model.providerId(),
"provider", model.providerLabel(),
"level", model.level(),
```

- [ ] **Step 4: 重跑目录与 Controller 测试**

运行 Step 2 命令。Expected: `BUILD SUCCESS`，相关测试 `Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交目录契约**

```powershell
git add src/main/java/com/elebusiness/service/video/VideoModelCatalog.java src/main/java/com/elebusiness/controller/VideoController.java src/test/java/com/elebusiness/service/video/VideoModelCatalogTest.java src/test/java/com/elebusiness/controller/VideoControllerProviderTest.java
git commit -m "feat: group video models by provider level"
```

### Task 2: 用供应商独立协议替换聊天补全

**Files:**
- Modify: `src/main/java/com/elebusiness/service/video/OpenAiCompatibleVideoService.java`
- Modify: `src/test/java/com/elebusiness/service/video/OpenAiCompatibleVideoServiceTest.java`

- [ ] **Step 1: 写 Grok 路由与轮询失败测试**

使用测试 `HttpServer` 只注册 `/v1/videos/generations`、`/v1/videos/grok-task-1` 和视频下载路由：

```java
server.createContext("/v1/videos/generations", exchange -> {
    assertEquals("POST", exchange.getRequestMethod());
    JsonNode request = objectMapper.readTree(exchange.getRequestBody());
    assertEquals("grok-imagine-video-1.5", request.path("model").asText());
    assertEquals(4, request.path("duration").asInt());
    assertEquals("16:9", request.path("aspect_ratio").asText());
    assertFalse(request.has("messages"));
    respondJson(exchange, "{\"request_id\":\"grok-task-1\"}");
});
server.createContext("/v1/videos/grok-task-1", exchange ->
        respondJson(exchange, "{\"status\":\"done\",\"video\":{\"url\":\"" + videoUrl + "\"}}"));
```

断言输出文件内容正确，并记录任何 `/chat/completions` 请求都会让测试失败。

- [ ] **Step 2: 写即梦创建、轮询与 content 下载失败测试**

注册 `/v1/videos`、`/v1/videos/jimeng-task-1`、`/v1/videos/jimeng-task-1/content`：

```java
server.createContext("/v1/videos", exchange -> {
    assertEquals("POST", exchange.getRequestMethod());
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    assertTrue(contentType.startsWith("multipart/form-data; boundary="));
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
    assertTrue(body.contains("name=\"model\""));
    assertTrue(body.contains("video-ds-2.0"));
    assertTrue(body.contains("name=\"seconds\""));
    assertTrue(body.contains("name=\"size\""));
    assertTrue(body.contains("name=\"input_reference\""));
    respondJson(exchange, "{\"id\":\"jimeng-task-1\",\"status\":\"queued\"}");
});
```

状态端点先返回 `processing`，再返回 `completed`；成功状态没有 URL 时，断言 Service 使用鉴权请求 `/content` 并原子保存 MP4。

- [ ] **Step 3: 写失败状态、取消和超时测试**

覆盖：

```java
assertThrows(IllegalStateException.class, () -> service.generateVideo(...)); // status=failed
GenerationCancellationContext.cancelTask("video-cancel-task");
assertFalse(Files.exists(output));
```

通过包可见构造器注入短轮询间隔与总超时，测试无需真实等待数分钟：

```java
new OpenAiCompatibleVideoService(properties, catalog, 10L, Duration.ofMillis(80));
```

- [ ] **Step 4: 运行 Service 测试并确认旧实现失败**

```powershell
& $mvn -Dtest=OpenAiCompatibleVideoServiceTest test
```

Expected: FAIL，旧实现请求 `/v1/chat/completions` 或无法轮询任务。

- [ ] **Step 5: 实现 Grok 请求策略**

在 Service 中按供应商分发：

```java
return switch (model.provider()) {
    case SUIXIANG_GROK -> submitGrok(model, apiKey, prompt, imageDataUris, aspectRatio, durationSeconds);
    case SUIXIANG_JIMENG -> submitJimeng(model, apiKey, prompt, imageDataUris, aspectRatio, durationSeconds);
    default -> throw new IllegalArgumentException("该服务不处理模型: " + model.id());
};
```

Grok 请求体使用：

```java
body.put("model", model.id());
body.put("prompt", prompt.trim());
body.put("duration", clamp(durationSeconds, 1, 15));
body.put("aspect_ratio", normalizeGrokAspectRatio(aspectRatio));
body.put("resolution", "720p");
if (!imageDataUris.isEmpty()) body.put("image_url", imageDataUris.get(0));
```

- [ ] **Step 6: 实现即梦请求策略**

用 OkHttp `MultipartBody` 发送：

```java
MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("model", model.id())
        .addFormDataPart("prompt", prompt.trim())
        .addFormDataPart("seconds", String.valueOf(normalizeJimengSeconds(durationSeconds)))
        .addFormDataPart("size", toVideoSize(aspectRatio));
```

参考图 data URI 解码后添加 `input_reference` 文件字段；只取第一张。

- [ ] **Step 7: 实现共享轮询、鉴权下载与错误处理**

创建响应先递归提取媒体；没有媒体时提取 `request_id` 或 `id`。轮询地址为 `/videos/{id}`，按设计文档识别等待、成功和失败状态。即梦成功但无 URL 时返回需要鉴权下载的媒体结果：

```java
if (provider == Provider.SUIXIANG_JIMENG && isSuccess(status)) {
    return MediaResult.authenticatedUrl(baseUrl + "/videos/" + encode(id) + "/content", apiKey);
}
```

下载请求只对同一受信任 `baseUrl` 的 content 端点携带 Authorization；供应商返回的任意外部媒体 URL不附带 Key。

- [ ] **Step 8: 重跑 Service 测试**

运行 Step 4 命令。Expected: `BUILD SUCCESS`，且测试服务器未收到 `/chat/completions`。

- [ ] **Step 9: 提交协议修复**

```powershell
git add src/main/java/com/elebusiness/service/video/OpenAiCompatibleVideoService.java src/test/java/com/elebusiness/service/video/OpenAiCompatibleVideoServiceTest.java
git commit -m "fix: use provider video generation protocols"
```

### Task 3: 用原模型控件实现供应商下拉和短滑条

**Files:**
- Modify: `frontend/index.html`
- Modify: `frontend/index.css`
- Modify: `tools/test-frontend-session-modules.js`
- Modify: `tools/check-ui-regressions.js`
- Modify: `tools/check-video-model-rail.js`

- [ ] **Step 1: 将静态契约改成最终 UI并确认失败**

断言旧卡片栏消失且新控件存在：

```javascript
assert.doesNotMatch(html, /id="videoModelRail"/);
assert.match(html, /id="videoModelLevel"/);
assert.match(html, /id="videoModelHint"/);
assert.match(html, /function renderVideoProviderOptions\(\)/);
assert.match(html, /function selectVideoProvider\(providerId\)/);
assert.match(html, /function setVideoModelLevel\(level/);
assert.doesNotMatch(css, /\.video-model-card/);
assert.match(css, /width:\s*82px/);
```

运行：

```powershell
node tools/test-frontend-session-modules.js
node tools/check-ui-regressions.js
```

Expected: FAIL，当前仍存在六模型卡片栏并隐藏原模型控件。

- [ ] **Step 2: 修改原模型控件结构**

保留 `agentSelectWrapper` 和 `agentSelect`，在其后增加只在视频模式显示的短滑条：

```html
<div class="video-model-level-control" id="videoModelLevelControl" hidden>
    <output id="videoModelHint" class="video-model-hint"></output>
    <input id="videoModelLevel" type="range" min="0" max="0" step="1" value="0"
           aria-label="视频模型等级">
</div>
```

删除 `videoConfig` 内的 `videoModelRail` 和加载状态，不增加替代面板。

- [ ] **Step 3: 实现供应商分组和模式切换**

前端目录回退项增加 `providerId` 与 `level`。视频模式加载目录后执行：

```javascript
function groupVideoModels(models) {
    return models.reduce((groups, model) => {
        (groups[model.providerId] ||= []).push(model);
        groups[model.providerId].sort((a, b) => a.level - b.level);
        return groups;
    }, {});
}
```

`renderVideoProviderOptions()` 复用 `agentSelectOptions` 渲染 Google、火山、Grok、即梦；`selectVideoProvider(providerId)` 设置滑条 `max`、禁用状态和默认等级，再调用 `setVideoModelLevel` 同步 `agentSelect.value`。

离开视频模式时调用现有 `loadAgents()` 恢复图片模型下拉，不再切换 `.video-mode-hidden`。

- [ ] **Step 4: 实现拖动期间模型浮层**

```javascript
levelInput.addEventListener('pointerdown', showVideoModelHint);
levelInput.addEventListener('input', event => setVideoModelLevel(Number(event.target.value), true));
levelInput.addEventListener('pointerup', hideVideoModelHint);
levelInput.addEventListener('pointercancel', hideVideoModelHint);
levelInput.addEventListener('blur', hideVideoModelHint);
levelInput.addEventListener('change', hideVideoModelHint);
```

浮层仅通过 `.is-visible` 显示；单模型供应商设置 `disabled=true`。

- [ ] **Step 5: 增加紧凑 CSS**

```css
.video-model-level-control {
    position: relative;
    width: 82px;
    height: 12px;
    margin: 4px auto 0;
}

.video-model-level-control input[type="range"] {
    display: block;
    width: 82px;
    margin: 0;
}

.video-model-hint {
    position: absolute;
    left: 50%;
    bottom: 16px;
    transform: translateX(-50%);
    visibility: hidden;
    white-space: nowrap;
}

.video-model-hint.is-visible { visibility: visible; }
```

使用现有颜色变量和 `4px` 圆角，不改变其他工具栏尺寸。

- [ ] **Step 6: 重写 Playwright 视频模型检查**

`tools/check-video-model-rail.js` 保留文件名以兼容门禁，但检查内容改为：

```javascript
assert.strictEqual(await page.locator('#videoModelRail').count(), 0);
assert.strictEqual(await page.locator('.agent-select-control.video-mode-hidden').count(), 0);
await page.evaluate(() => selectVideoProvider('suixiang-grok'));
await page.locator('#videoModelLevel').evaluate(el => {
  el.value = '1';
  el.dispatchEvent(new Event('input', { bubbles: true }));
});
assert.strictEqual(await page.locator('#agentSelect').inputValue(), 'grok-imagine-video-1.5');
```

再检查 Google 与火山滑条禁用、提示浮层松手隐藏、提交 FormData 模型正确、移动端无横向溢出。

- [ ] **Step 7: 运行前端专项检查**

```powershell
node tools/test-frontend-session-modules.js
node tools/check-ui-regressions.js
node tools/check-video-model-rail.js
```

Expected: 三项退出码均为 0，Playwright 输出紧凑视频模型选择检查通过。

- [ ] **Step 8: 提交前端修改**

```powershell
git add frontend/index.html frontend/index.css tools/test-frontend-session-modules.js tools/check-ui-regressions.js tools/check-video-model-rail.js
git commit -m "feat: add compact video provider selector"
```

### Task 4: 专项回归与全量发布门禁

**Files:**
- Verify: `docs/运行与发布验收门禁.md`
- Build: `target/ele-business-java-1.0.0.jar`

- [ ] **Step 1: 运行视频与取消专项测试**

```powershell
& $mvn -Dtest=VideoModelCatalogTest,OpenAiCompatibleVideoServiceTest,VideoControllerProviderTest,VideoControllerConcurrencyTest,GenerationCancellationContextTest test
```

Expected: `BUILD SUCCESS`，`Failures: 0, Errors: 0`。

- [ ] **Step 2: 运行全部前端静态与契约检查**

执行 `docs/运行与发布验收门禁.md` 第 4.1 节全部命令，并额外运行：

```powershell
node tools/check-video-model-rail.js
```

Expected: 所有命令退出码为 0。

- [ ] **Step 3: 运行 Maven 全量测试**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`，`Failures: 0, Errors: 0`。

- [ ] **Step 4: 启动本地服务并运行浏览器关键流程**

在 `5021` 启动本地服务，执行认证、画布拖拽、多任务取消、跳过分析和视频选择检查。Expected: HTTP 200，无 `pageerror`，多个视频任务互不停止。

- [ ] **Step 5: 构建并记录哈希**

```powershell
& $mvn clean package -DskipTests
Get-FileHash target\ele-business-java-1.0.0.jar -Algorithm SHA256
Get-FileHash frontend\index.html,frontend\index.css -Algorithm SHA256
```

Expected: JAR 构建成功并获得三个 SHA-256。

- [ ] **Step 6: 部署前等待明确指令**

本地修改、测试和打包完成后汇报结果。只有用户明确要求上传服务器时，才按门禁备份、校验、原子替换、重启和生产烟测。真实 Grok、即梦生成可能产生供应商费用，未得到明确计费授权时标记为 `BLOCKED`。
