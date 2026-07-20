# 视频供应商网络与尺寸一致性实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复生产服务器 Grok 媒体下载链路，并保证即梦请求尺寸与最终视频尺寸一致。

**Architecture:** 使用共享视频尺寸规格消除即梦请求和 FFmpeg 输出的重复映射；使用仅针对媒体域名的 SOCKS5 代理策略和同 URL 下载重试修复 Grok，不改变随想主 API 与服务器默认路由。生产服务器以 Cloudflare WARP 本地代理模式提供受 systemd 管理的回环 SOCKS5 出口。

**Tech Stack:** Java 17、Spring Boot 3.3、OkHttp、JUnit 5、Cloudflare WARP、FFmpeg/FFprobe、systemd。

---

### Task 1: 建立唯一视频尺寸规格

**Files:**
- Create: `src/main/java/com/elebusiness/service/video/VideoAspectSpec.java`
- Create: `src/test/java/com/elebusiness/service/video/VideoAspectSpecTest.java`
- Modify: `src/main/java/com/elebusiness/service/video/VideoOutputNormalizer.java`
- Modify: `src/main/java/com/elebusiness/service/video/OpenAiCompatibleVideoService.java`
- Modify: `src/test/java/com/elebusiness/service/video/VideoOutputNormalizerTest.java`
- Modify: `src/test/java/com/elebusiness/service/video/OpenAiCompatibleVideoServiceTest.java`

- [ ] 写测试断言七种比例以及非法比例回退 `16:9`。
- [ ] 运行目标测试，确认共享规格尚不存在而失败。
- [ ] 实现 `VideoAspectSpec.resolve(String)`、`apiSize()`、`width()` 和 `height()`。
- [ ] 让即梦 multipart `size` 和 `VideoOutputNormalizer` 都使用共享规格。
- [ ] 运行尺寸、供应商请求和规范化测试，确认通过。

### Task 2: 增加媒体专用代理策略

**Files:**
- Modify: `src/main/java/com/elebusiness/config/AppProperties.java`
- Create: `src/main/java/com/elebusiness/service/video/VideoMediaProxyPolicy.java`
- Create: `src/test/java/com/elebusiness/service/video/VideoMediaProxyPolicyTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-prod.yml`

- [ ] 写测试覆盖禁用、SOCKS5、HTTP、白名单子域和非白名单直连。
- [ ] 运行测试，确认代理策略尚不存在而失败。
- [ ] 在 `app.suixiang-video.media-proxy` 增加 enabled/type/host/port/hosts 配置。
- [ ] 实现仅按媒体 URL 主机返回 Java `Proxy` 的策略。
- [ ] 运行代理策略和生产配置安全测试。

### Task 3: 实现 Grok 媒体下载重试与隔离

**Files:**
- Modify: `src/main/java/com/elebusiness/service/video/OpenAiCompatibleVideoService.java`
- Modify: `src/test/java/com/elebusiness/service/video/OpenAiCompatibleVideoServiceTest.java`

- [ ] 写测试证明媒体下载前两次失败、第三次成功时只提交一次生成请求。
- [ ] 写测试证明媒体代理配置不会应用到随想主 API 请求。
- [ ] 写测试证明取消任务会终止下载重试并删除 `.part` 文件。
- [ ] 运行目标测试，确认缺少行为而失败。
- [ ] 将 API 客户端和媒体客户端分离，媒体客户端按策略选择代理。
- [ ] 实现同 URL 最多三次、取消感知、部分文件清理和脱敏日志。
- [ ] 运行供应商服务测试并确认通过。

### Task 4: 完成本地回归门禁

**Files:**
- Modify only if verification reveals a reproducible defect.

- [ ] 运行视频服务、控制器、并发、取消和配置安全目标测试。
- [ ] 运行前端静态检查和视频模型浏览器检查。
- [ ] 运行完整 Maven 测试并确认零失败、零错误。
- [ ] 构建 JAR 并记录 JAR、前端文件 SHA-256。

### Task 5: 部署并维护 WARP 媒体代理

**Files:**
- Server: Cloudflare WARP package/repository and `warp-svc` state.
- Server: ai-studio environment override or existing environment file.

- [ ] 记录 `ai-studio` 状态、重启次数、网络和 systemd 配置备份。
- [ ] 安装 Cloudflare WARP 官方 Linux 客户端，不启用全局隧道路由。
- [ ] 注册客户端，设置 proxy 模式和回环 SOCKS5 端口 `40000`。
- [ ] 启用并启动 `warp-svc`，验证重启后可恢复。
- [ ] 用 `curl --socks5-hostname 127.0.0.1:40000` 验证 `vidgen.x.ai` HTTPS 可达。
- [ ] 将媒体代理环境变量写入生产服务安全配置，确认不输出任何 Key。

### Task 6: 备份并部署应用

**Files:**
- Server: `/www/wwwroot/ai-studio/app.jar`
- Server: `/www/wwwroot/ai-studio/frontend/index.html`

- [ ] 创建带时间戳的生产备份目录并备份 JAR、前端和服务配置。
- [ ] 上传到临时路径，比较本地与服务器 SHA-256。
- [ ] 原子替换发布文件并重启 `ai-studio`。
- [ ] 验证 active、NRestarts、HTTP 200、认证、模型目录和启动日志。

### Task 7: 真实供应商验收

**Files:**
- Output: `C:/Users/19144/Desktop/grok-3x4-4s-verified.mp4`
- Output: `C:/Users/19144/Desktop/jimeng-3x4-4s-verified.mp4`

- [ ] 只提交一个 Grok 图生视频任务，一张参考图、`3:4`、`4s`。
- [ ] 下载并用 FFprobe 验证 MP4、H.264、yuv420p、720x960、约 4 秒。
- [ ] 只提交一个即梦任务，`3:4`、`4s`。
- [ ] 下载并用 FFprobe 验证 MP4、H.264、yuv420p、720x960、约 4 秒。
- [ ] 检查两个任务的计费状态、文件 SHA-256、无 `.part` 残留。
- [ ] 检查 `ai-studio`、`warp-svc`、NRestarts 和发布后错误日志。

