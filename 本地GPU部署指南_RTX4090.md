# 本地 GPU 部署完整方案（RTX 4090）

## 🎉 恭喜！你的配置非常强大

**RTX 4090 24GB** 是顶配，可以：
- ✅ 流畅运行 SDXL + 多个 LoRA
- ✅ 生成速度：20-30秒/张（比云GPU快3倍）
- ✅ 支持高分辨率（2048×2048）
- ✅ 可以同时加载多个模型

---

## 🎯 推荐方案：Stable Diffusion WebUI

**为什么推荐 SD WebUI（而不是 ComfyUI）？**

| 特性 | SD WebUI | ComfyUI |
|------|----------|---------|
| **API 集成** | ⭐⭐⭐⭐⭐ 开箱即用 | ⭐⭐⭐ 需要自己写 |
| **上手难度** | ⭐ 简单 | ⭐⭐⭐ 节点式，复杂 |
| **社区资源** | ⭐⭐⭐⭐⭐ 最多 | ⭐⭐⭐⭐ 较多 |
| **你的需求** | ✅ 完美匹配 | ⚠️ 过于复杂 |

**结论**: 用 SD WebUI，5分钟对接完成！

---

## 📦 完整部署步骤

### 步骤 1: 下载 Stable Diffusion WebUI 整合包

#### Windows 一键整合包（推荐）

```
下载地址（选一个）:

1. 秋葉aaaki 整合包（推荐）:
   https://www.bilibili.com/video/BV1iM4y1y7oA
   百度网盘: https://pan.baidu.com/s/1xzIF0yF1wr28F8gZeJOCWw
   提取码: 5555

2. ModelScope 镜像:
   https://modelscope.cn/models/AI-ModelScope/stable-diffusion-webui

3. GitHub 官方（需要手动配置）:
   https://github.com/AUTOMATIC1111/stable-diffusion-webui

文件大小: 约 8-15GB（包含基础模型）
```

#### 下载后解压

```powershell
# 推荐解压位置
D:\stable-diffusion-webui\

# 或者（根据你的硬盘）
E:\AI\stable-diffusion-webui\
F:\stable-diffusion-webui\
```

---

### 步骤 2: 放置文件

#### 2.1 放置 SDXL 主模型

```
模型目录:
D:\stable-diffusion-webui\models\Stable-diffusion\

需要下载（如果整合包没有）:
- sd_xl_base_1.0.safetensors (6.5GB)

下载地址:
https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0
或国内镜像:
https://modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0
```

#### 2.2 放置你的 LoRA

```
LoRA 目录:
D:\stable-diffusion-webui\models\Lora\

复制你的文件:
dianshangzhantaiXL.safetensors → D:\stable-diffusion-webui\models\Lora\
```

**目录结构**:
```
D:\stable-diffusion-webui\
├── models\
│   ├── Stable-diffusion\
│   │   └── sd_xl_base_1.0.safetensors  (主模型)
│   └── Lora\
│       └── dianshangzhantaiXL.safetensors  (你的LoRA)
├── webui-user.bat  (启动脚本)
├── webui.py
└── ...
```

---

### 步骤 3: 启用 API

#### 编辑启动脚本

```powershell
# 打开启动脚本
notepad D:\stable-diffusion-webui\webui-user.bat
```

#### 修改配置

找到这一行（大约在第 8 行）:
```batch
set COMMANDLINE_ARGS=
```

修改为:
```batch
set COMMANDLINE_ARGS=--api --listen --port 7860 --xformers --no-half-vae
```

**参数说明**:
- `--api`: 启用 API（重要！）
- `--listen`: 允许局域网访问
- `--port 7860`: API 端口
- `--xformers`: 加速（4090 必开）
- `--no-half-vae`: 避免黑图（SDXL 推荐）

**完整示例**:
```batch
@echo off

set PYTHON=
set GIT=
set VENV_DIR=
set COMMANDLINE_ARGS=--api --listen --port 7860 --xformers --no-half-vae

call webui.bat
```

保存并关闭。

---

### 步骤 4: 首次启动

```powershell
# 双击运行
D:\stable-diffusion-webui\webui-user.bat

# 首次启动会自动:
# 1. 检查 Python 环境
# 2. 安装依赖包（约 5-10 分钟）
# 3. 加载模型
# 4. 启动 WebUI

# 看到这行表示成功:
Running on local URL:  http://127.0.0.1:7860
```

#### 验证 WebUI

```
浏览器打开:
http://localhost:7860

# 应该看到 SD WebUI 界面
```

#### 验证 API

```powershell
# PowerShell 测试
Invoke-RestMethod -Uri "http://localhost:7860/sdapi/v1/sd-models"

# 应该返回模型列表
```

---

### 步骤 5: 配置 Java 项目

#### 5.1 更新配置文件

```yaml
# 编辑 F:\java\ele-business-java\src\main\resources\application-prod.yml

app:
  local-sd:
    api-url: "http://localhost:7860"
    lora-name: "dianshangzhantaiXL"
    lora-weight: 0.9
```

#### 5.2 重启 Java 项目

```powershell
# Ctrl+C 停止当前项目
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

---

### 步骤 6: 测试连接

#### 6.1 测试 API 连接

```powershell
# 测试文生图
curl -X POST http://localhost:7860/sdapi/v1/txt2img `
  -H "Content-Type: application/json" `
  -d '{
    "prompt": "<lora:dianshangzhantaiXL:0.9> product photography, white background",
    "negative_prompt": "worst quality, low quality, blurry",
    "steps": 30,
    "width": 1024,
    "height": 1024,
    "cfg_scale": 7.5,
    "sampler_name": "DPM++ 2M Karras"
  }'
```

#### 6.2 通过 Java 项目测试

```
1. 访问: http://localhost:5020
2. 登录: 密码 123456
3. 选择: 自定义模式
4. Agent: 选择 "本地 SD + LoRA (电商展台)"
5. 上传: 产品图片
6. 点击: 生成

预期时间: 20-30秒（4090 速度）
```

---

## 🚀 一键启动脚本

### 创建自动启动脚本

我为你创建一个一键启动脚本：

```batch
@echo off
chcp 65001 > nul
title AI 产品图生成系统 - 本地启动

echo ============================================
echo  AI 产品图生成系统 - 本地 GPU 版
echo  GPU: RTX 4090 24GB
echo ============================================
echo.

REM 检查 SD WebUI 是否已启动
netstat -ano | findstr ":7860" > nul
if %errorlevel%==0 (
    echo [✓] Stable Diffusion WebUI 已在运行
    echo     访问: http://localhost:7860
) else (
    echo [×] Stable Diffusion WebUI 未运行
    echo [!] 请先启动 WebUI:
    echo     双击运行: D:\stable-diffusion-webui\webui-user.bat
    echo.
    pause
    exit /b 1
)

echo.
echo [→] 正在启动 Java 后端...
echo.

cd /d F:\java\ele-business-java
set "MAVEN_HOME=C:\Users\19144\maven-portable\apache-maven-3.9.9"
set "PATH=%MAVEN_HOME%\bin;%PATH%"

call mvn.cmd spring-boot:run

pause
```

保存为: `F:\java\ele-business-java\启动本地GPU版.bat`

---

## 📊 性能优化配置（4090专用）

### 优化参数

```batch
REM webui-user.bat 优化配置
set COMMANDLINE_ARGS=--api --listen --port 7860 --xformers --no-half-vae --opt-sdp-attention --medvram-sdxl

参数说明:
--xformers: 使用高效注意力机制（速度提升30%）
--no-half-vae: 避免 SDXL 黑图问题
--opt-sdp-attention: PyTorch 2.0 优化
--medvram-sdxl: SDXL 显存优化（可选，4090 通常不需要）
```

### 预期性能

```
RTX 4090 24GB 生成速度:

1024×1024 (30步):     20-25秒/张
1024×1024 (50步):     30-40秒/张
2048×2048 (30步):     40-60秒/张

并发能力:
可以同时运行 2-3 个生成任务
```

---

## 🔧 常见问题

### Q1: 启动后提示 "CUDA out of memory"

```
原因: 显存不足（但 4090 24GB 基本不会遇到）

解决:
1. 关闭其他占用 GPU 的程序
2. 降低生成分辨率（1024→768）
3. 减少批量数量
```

### Q2: 生成速度慢

```
检查:
1. 是否启用 --xformers 参数
2. NVIDIA 驱动是否最新（建议 ≥ 536.x）
3. CUDA 是否正确安装

预期速度（4090）:
- 正常: 20-30秒/张
- 异常: >60秒/张
```

### Q3: 生成黑图

```
原因: SDXL VAE 精度问题

解决:
在 webui-user.bat 添加: --no-half-vae
```

### Q4: Java 项目连接失败

```
检查:
1. SD WebUI 是否启动（访问 http://localhost:7860）
2. 是否启用了 --api 参数
3. 防火墙是否阻止 7860 端口

测试连接:
curl http://localhost:7860/sdapi/v1/sd-models
```

---

## 📝 完整工作流程

### 日常使用流程

```
1. 启动 SD WebUI
   双击: D:\stable-diffusion-webui\webui-user.bat
   等待: 看到 "Running on local URL"

2. 启动 Java 项目
   双击: F:\java\ele-business-java\启动本地GPU版.bat
   等待: 看到 "产品图片生成系统已启动"

3. 访问前端
   浏览器: http://localhost:5020

4. 生成图片
   - 选择 Agent: "本地 SD + LoRA (电商展台)"
   - 上传产品图
   - 点击生成
   - 等待 20-30 秒
```

---

## ✅ 验证清单

部署完成后，检查以下项目:

- [ ] SD WebUI 可以访问 (http://localhost:7860)
- [ ] API 测试成功 (curl 返回模型列表)
- [ ] LoRA 文件已放置在正确位置
- [ ] Java 项目启动成功 (端口 5020)
- [ ] application-prod.yml 配置正确
- [ ] 前端可以选择 "本地 SD + LoRA"
- [ ] 测试生成 1 张图成功
- [ ] 生成速度在 20-40 秒范围内

---

## 💰 成本分析

```
硬件投入: RTX 4090 (已有)
软件成本: ¥0 (全部开源)
电费成本: 
  - GPU 功耗: 450W (4090 满载)
  - 每小时: 0.45 度电
  - 每天 5 小时: 2.25 度 × ¥0.6 = ¥1.35/天
  - 每月: ¥40

对比云GPU:
  - AutoDL RTX 4090: ¥3.5/小时 × 150小时 = ¥525/月
  - 本地成本: ¥40/月
  - 节省: ¥485/月
```

---

## 🎉 优势总结

使用本地 4090 的优势:

1. **极快速度**: 20-30秒/张（比云GPU快2-3倍）
2. **无限次数**: 不限生成次数，想生成多少都行
3. **数据安全**: 数据不出本地
4. **成本低廉**: 只有电费（约¥40/月）
5. **完全掌控**: 可以随时调整参数、换模型
6. **24GB 显存**: 可以同时加载多个 LoRA

---

需要我帮你创建启动脚本或者指导具体安装步骤吗？
