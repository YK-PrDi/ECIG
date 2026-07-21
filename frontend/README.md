# 羽刃 · 新前端（v2，已替换线上 frontend/）

按参考图实现的企业风多视图 SPA，**全部接口与后端真实契约对齐**。老版本备份在 `../frontend-backup-v1-20260721/`。

## 结构

```
frontend/
├── index.html            # SPA 骨架：侧边导航 + 顶栏 + 各视图容器
├── css/app.css           # 浅色企业风 + 动效系统
├── js/
│   ├── api.js            # API 客户端（全部后端端点，按真实契约）
│   ├── app.js            # SPA 壳：登录、导航切换、通用任务轮询 App.pollTask
│   └── views/
│       ├── kaipin.js     # 开品模式（外观创新设计工作台）
│       ├── ecommerce.js  # 电商模式（钉钉产品批量生图）
│       ├── custom.js     # 自定义模式（参考图迁移 + 蒙版局部重绘）
│       ├── video.js      # 视频模式（Veo/Seedance/Grok/即梦）
│       └── misc.js       # 工作台/素材与资产/模型中心/任务中心/企业设置
├── data/                 # 品类知识库（老前端沿用，后端 /data/categories/** 映射依赖）
└── icon.png
```

## 视图 ↔ 接口对照

| 视图 | 接口 |
|---|---|
| 开品模式 | `POST /api/kaipin_analyze`（multipart → `{fields:[{key,value}]}`） |
| 电商模式 | `GET /api/products`、`POST /api/generate`(JSON) → `{taskId}` |
| 自定义模式 | `POST /api/custom_analyze`（可选）、`POST /api/custom_generate`(FormData)、`POST /api/inpaint`(image+mask PNG) |
| 视频模式 | `GET /api/video/models`、`POST /api/video/generate`(FormData)、`GET /api/video/file?filename=` |
| 任务轮询（共用） | `GET /api/task/{id}`（800ms，`status` 脱离 running/pending/stopping 即终态）、`POST /api/task/{id}/stop` |
| 素材与资产 | `GET/POST/DELETE /api/kaipin_materials`、`GET /api/gallery?path=` |
| 模型中心 | `GET /api/agents`、`GET /api/video/models` |
| 工作台最近生成 | `GET /api/history/generations`、`GET /api/history/thumbnail?id=` |
| 企业设置 | `GET/POST /api/settings`（dingtalk/proxy/customOutputDir 三段独立保存） |
| 登录 | `POST /api/auth/login`、`GET /api/auth/check` |

图片 URL 规则：`http(s)` 直用，本地路径拼 `/api/image?path=`（`App.toImgUrl`）。
视频结果：`results[].type==='video' && filename` → `/api/video/file?filename=`。

## 待建设（后端无接口，UI 标注占位）

- 项目中心 / 商品中心 / 审核中心 / 数据看板 → wip 占位页
- 任务中心：后端任务为内存态无列表接口，仅支持按 ID 查询
- 开品模式的多角度渲染图、独立校验/风险模型 → 当前由分析字段驱动

## 预览

- 直接打开 `index.html` → 后端不可达自动进入**演示模式**（内置示例数据，可完整体验交互与动效）
- 由 Spring Boot 伺服（`http://localhost:5020`）→ 自动使用真实接口
