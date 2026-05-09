# AI Studio — 项目文件说明

## 项目简介

AI Studio 是一个 AI 驱动的电商产品图片批量生成工具。通过对接钉钉多维表格获取产品数据，调用多家 AI 图像生成服务（Google Gemini、阿里万相、阿里通义、腾讯混元），自动生成主图、SKU 图、详情图。前端以 Electron 封装为 Windows 桌面应用。

---

## 目录结构

```
ele-business-java/
├── src/                        # Java 后端源码
│   ├── main/java/com/elebusiness/
│   │   ├── config/             # 配置类
│   │   ├── controller/         # REST 接口
│   │   ├── model/              # 数据模型
│   │   └── service/            # 业务逻辑
│   │       └── agent/          # AI 图像生成智能体
│   └── resources/
│       └── application.yml     # Spring Boot 配置
├── frontend/                   # 前端页面
├── electron/                   # Electron 桌面封装
├── pom.xml                     # Maven 构建配置
├── config.json                 # 运行时配置（钉钉表格 ID）
└── build-dist.bat              # 一键构建打包脚本
```

---

## 文件说明

### 入口

| 文件 | 作用 |
|------|------|
| `EleBusinessApplication.java` | Spring Boot 启动入口，启动后自动打开浏览器 |
| `electron/main.js` | Electron 主进程：启动 JVM、等待 Spring Boot 就绪后显示主窗口 |

### 配置

| 文件 | 作用 |
|------|------|
| `AppProperties.java` | 统一读取 `application.yml` 所有配置（钉钉、Gemini、路径、代理等） |
| `DashScopeConfig.java` | 持有阿里云 DashScope API Key（万相 / 通义共用） |
| `SiliconFlowConfig.java` | 持有腾讯混元 API Key |
| `ConfigService.java` | 读写 `config.json`（运行时切换钉钉表格 ID） |
| `application.yml` | 端口、API Key、路径、代理、超时等全局参数 |
| `config.json` | 当前使用的钉钉多维表格 Sheet ID |
| `pom.xml` | Maven 依赖与构建配置（Java 17，Spring Boot 3.2） |

### 接口层

| 文件 | 作用 |
|------|------|
| `ApiController.java` | 主接口：产品列表、企业模式、自定义模式模式、任务查询/停止、图片画廊 |
| `VideoController.java` | 视频接口：调用 Gemini Veo 生成视频（`POST /api/video/generate`）、返回 MP4 |

### 业务逻辑

| 文件 | 作用 |
|------|------|
| `DingTalkService.java` | 对接钉钉 API，分页拉取产品记录并解析，带 AccessToken / 记录双层缓存 |
| `ImageGenerationService.java` | 生图流程调度：按产品依次生成 SKU 图、主图、详情图，管理各 AI 智能体 |
| `TaskService.java` | 异步任务管理：创建、提交、查询进度、取消 |
| `VideoGenerationService.java` | 调用 Gemini Veo 3.1 生成视频（提交任务 → 轮询完成 → 保存 MP4） |

### AI 智能体

| 文件 | 作用 |
|------|------|
| `ImageGeneratorAgent.java` | 智能体接口定义（`getId` / `getDisplayName` / `generate`） |
| `GeminiImageAgent.java` | Google Gemini 图像生成，支持 HTTP 代理，含 503/429 重试逻辑 |
| `WanImageAgent.java` | 阿里万相 2.7 图像生成（异步任务模式） |
| `QwenImageAgent.java` | 阿里通义 Qwen-Image 2.0 图像生成（异步任务模式） |
| `HunyuanImageAgent.java` | 腾讯混元图像生成（异步任务模式） |

### 数据模型

| 文件 | 作用 |
|------|------|
| `GenerationTask.java` | 异步任务状态（进度、结果列表、取消标志，线程安全） |
| `ProductInfo.java` | 从钉钉记录解析的产品信息（名称、类别、主图列表、SKU 列表） |
| `DingTalkRecord.java` | 钉钉原始记录结构，提供字段读取辅助方法 |
| `GenerateRequest.java` | 企业模式请求体（产品 ID 列表、模型 ID、提示词） |

### 前端

| 文件 | 作用                                                     |
|------|--------------------------------------------------------|
| `frontend/index.html` | 主界面：三种生图模式（企业 / 自定义模式 / 视频）、产品选择、实时进度轮询                  |
| `frontend/index.css` | 样式：深色 / 浅色双主题、动画、响应式布局                                 |
| `electron/loading.html` | 启动时的加载页（等待 Spring Boot 就绪期间显示）                         |
| `electron/package.json` | Electron 依赖及打包配置（electron-builder，输出 Windows NSIS 安装包） |

### 构建

| 文件 | 作用 |
|------|------|
| `build-dist.bat` | 一键构建脚本：Maven 打包 → 复制 JAR 和前端文件 → Electron 打包为安装包 |

---

## 运行时目录（不纳入版本控制）

| 目录 | 说明 |
|------|------|
| `dist/` | 构建产物：`app.jar`、前端文件、JDK 运行时 |
| `dist-electron/` | Electron 打包输出（安装包、解压目录） |
| `target/` | Maven 编译输出 |
| `electron/node_modules/` | npm 依赖 |
| `大参考/` | 各品类参考图（按类别/参考图N/主图、SKU 组织） |
| `生成结果/` | AI 生成的图片和视频输出目录 |
