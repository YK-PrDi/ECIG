# Video Concurrent Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让同一账号可以同时运行多个视频任务，且任务状态、输出文件和前端脚本缓存互不冲突。

**Architecture:** 继续复用现有 `TaskService` 多任务注册表和前端 `generationControls`。后端以 `taskId` 作为视频输出文件名的一部分，前端通过静态资源版本参数强制刷新多任务脚本。

**Tech Stack:** Spring Boot 3、Java 17、原生 JavaScript、Node.js、Playwright、JUnit 5。

---

### Task 1: 视频输出路径唯一化

**Files:**
- Modify: `src/main/java/com/elebusiness/controller/VideoController.java`
- Test: `src/test/java/com/elebusiness/controller/VideoControllerConcurrencyTest.java`

- [ ] 先写测试，连续调用视频提交接口并断言两个任务的输出路径包含不同 `taskId`。
- [ ] 运行 `mvn -Dtest=VideoControllerConcurrencyTest test`，确认测试因文件名仍只包含秒级时间而失败。
- [ ] 将任务创建移动到输出路径构造之前，文件名改为 `video_<timestamp>_<taskId>.mp4`。
- [ ] 再次运行测试并确认通过。

### Task 2: 前端缓存与双任务回归

**Files:**
- Modify: `frontend/index.html`
- Modify: `tools/test-frontend-session-modules.js`
- Modify: `tools/check-multi-task-controls.js`

- [ ] 在静态测试中断言 `generation-controls.js` 和 `task-polling.js` 带版本参数。
- [ ] 运行 `node tools/test-frontend-session-modules.js`，确认旧引用导致测试失败。
- [ ] 给任务脚本增加同一版本参数。
- [ ] 扩展 Playwright 检查，以视频任务语义创建两个任务并验证独立完成。
- [ ] 运行前端模块测试和多任务浏览器测试。

### Task 3: 完整验证与部署

**Files:**
- Deploy: `target/ele-business-java-1.0.0.jar`
- Deploy: `frontend/index.html`

- [ ] 运行 `mvn test`、前端模块测试和静态检查。
- [ ] 执行 `mvn clean package -DskipTests`。
- [ ] 备份生产 JAR 与 `index.html`，上传并校验 SHA-256。
- [ ] 重启 `ai-studio`，执行认证烟测和双任务浏览器测试。
- [ ] 恢复本地 `http://127.0.0.1:5021` 预览。
