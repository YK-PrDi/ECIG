# 本地 LoRA 部署指南（无需翻墙）

## 🎯 三种方案对比

### 方案 1: AutoDL 云GPU（推荐）⭐⭐⭐⭐⭐

**优点**:
- ✅ 国内服务，无需翻墙
- ✅ 按小时计费，用多少算多少
- ✅ 预装 Stable Diffusion WebUI
- ✅ 完全支持自定义 LoRA
- ✅ 提供公网 IP 和 API

**成本**:
```
RTX 3060 (12GB): ¥1.2/小时
RTX 4090 (24GB): ¥3.5/小时

预估月成本:
- 测试阶段: ¥10-30
- 正式使用: ¥100-200/月（每天3-5小时）
```

### 方案 2: 本地部署（一次性投入）⭐⭐⭐⭐

**优点**:
- ✅ 一次性投入，长期免费
- ✅ 完全掌控，无限次数
- ✅ 数据不出本地，更安全

**硬件要求**:
```
最低: RTX 3060 (12GB) - 约 ¥2000-2500（二手）
推荐: RTX 4060 Ti (16GB) - 约 ¥3500-4000（全新）
```

### 方案 3: 国内商业 API（有限支持）⭐⭐⭐

**优点**:
- ✅ 开箱即用
- ✅ 按量计费

**限制**:
- ⚠️ 不支持自定义 LoRA
- ⚠️ 风格固定，可控性差

---

## 📦 方案 1: AutoDL 云GPU（详细教程）

### 步骤 1: 注册 AutoDL

1. 访问 https://www.autodl.com/
2. 手机号注册
3. 充值最低 ¥10（支持微信/支付宝）

### 步骤 2: 创建 GPU 实例

1. **选择算力**
   - 点击 "算力市场"
   - 选择 "RTX 3060 (12GB)" - ¥1.2/小时
   - 地区: 北京/上海/广州（就近选择）

2. **选择镜像**
   ```
   镜像市场 → 搜索 "Stable Diffusion"
   选择: "Stable Diffusion WebUI + LoRA"
   或: "AUTOMATIC1111/stable-diffusion-webui"
   ```

3. **配置实例**
   ```
   硬盘: 50GB（免费）
   开机方式: 按需开机（省钱）
   ```

4. **创建实例**
   - 点击 "立即创建"
   - 等待 1-2 分钟启动

### 步骤 3: 上传你的 LoRA

#### 方法 A: Web 界面上传（简单）

1. 点击实例的 "JupyterLab" 按钮
2. 进入文件管理器
3. 导航到: `/root/stable-diffusion-webui/models/Lora/`
4. 上传 `dianshangzhantaiXL.safetensors`

#### 方法 B: SSH 上传（快速）

```bash
# 获取实例 IP 和密码（在控制台显示）
# 示例 IP: 123.45.67.89

# 使用 scp 上传
scp dianshangzhantaiXL.safetensors root@123.45.67.89:/root/stable-diffusion-webui/models/Lora/

# 输入密码（控制台显示的随机密码）
```

### 步骤 4: 启动 WebUI

```bash
# SSH 登录
ssh root@123.45.67.89

# 启动 WebUI（启用 API）
cd /root/stable-diffusion-webui
./webui.sh --api --listen --port 6006 --xformers

# 等待启动完成（约 30 秒）
# 看到 "Running on http://0.0.0.0:6006" 表示成功
```

### 步骤 5: 配置到你的项目

#### 更新配置文件

```yaml
# application-prod.yml
app:
  local-sd:
    api-url: "http://123.45.67.89:6006"  # 你的 AutoDL 实例 IP
    lora-name: "dianshangzhantaiXL"
    lora-weight: 0.9
```

#### 重启项目

```powershell
# Ctrl+C 停止项目
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

### 步骤 6: 测试生成

```bash
# 访问前端
http://localhost:5020

# 选择 Agent: "本地 SD + LoRA (电商展台)"
# 上传产品图
# 点击生成
```

---

## 💻 方案 2: 本地部署（详细教程）

### 硬件配置推荐

| 配置级别 | GPU | 内存 | 硬盘 | 成本 | 性能 |
|---------|-----|------|------|------|------|
| **入门** | RTX 3060 (12GB) | 16GB | 50GB | ¥2000 | 1张/min |
| **推荐** | RTX 4060 Ti (16GB) | 32GB | 100GB | ¥3500 | 2张/min |
| **高端** | RTX 4070 / 4080 | 32GB | 200GB | ¥5000+ | 3-5张/min |

### 步骤 1: 下载 Stable Diffusion WebUI

#### Windows 整合包（推荐）

1. **下载整合包**
   ```
   秋葉aaaki 整合包:
   https://www.bilibili.com/video/BV1iM4y1y7oA
   
   或 ModelScope 镜像:
   https://modelscope.cn/models/AI-ModelScope/stable-diffusion-webui
   ```

2. **解压到本地**
   ```
   解压到: D:\stable-diffusion-webui
   大小: 约 20GB
   ```

3. **首次启动**
   ```powershell
   # 双击运行
   D:\stable-diffusion-webui\webui-user.bat
   
   # 等待自动下载模型（约 20 分钟）
   # 会自动下载 SDXL 基础模型（约 6.5GB）
   ```

### 步骤 2: 放置 LoRA 和模型

```
D:\stable-diffusion-webui\
├── models\
│   ├── Stable-diffusion\
│   │   └── sd_xl_base_1.0.safetensors  (自动下载)
│   └── Lora\
│       └── dianshangzhantaiXL.safetensors  ← 放这里
```

### 步骤 3: 启用 API

```powershell
# 编辑启动脚本
notepad D:\stable-diffusion-webui\webui-user.bat

# 修改这一行:
set COMMANDLINE_ARGS=--api --listen --port 7860 --xformers

# 保存并重新运行
```

### 步骤 4: 配置项目

```yaml
# application-prod.yml
app:
  local-sd:
    api-url: "http://localhost:7860"
    lora-name: "dianshangzhantaiXL"
    lora-weight: 0.9
```

### 步骤 5: 测试

```bash
# 访问 WebUI（检查是否正常）
http://localhost:7860

# 测试 API
curl -X POST http://localhost:7860/sdapi/v1/txt2img \
  -H "Content-Type: application/json" \
  -d '{"prompt":"<lora:dianshangzhantaiXL:0.9> product photography","steps":20}'
```

---

## 🔧 已创建的集成代码

我已经为你创建了 `LocalSdLoraAgent.java`：

**位置**: `src/main/java/com/elebusiness/service/agent/LocalSdLoraAgent.java`

**功能**:
- ✅ 自动添加 LoRA 触发词
- ✅ 支持文生图 (txt2img)
- ✅ 支持图生图 (img2img)
- ✅ 自动连接本地/AutoDL API
- ✅ 完整错误处理

**配置项**:
```yaml
app:
  local-sd:
    api-url: "http://localhost:7860"  # WebUI API 地址
    lora-name: "dianshangzhantaiXL"   # LoRA 文件名（不含后缀）
    lora-weight: 0.9                  # LoRA 权重
```

---

## 💰 成本对比

### AutoDL 云GPU

```
场景 1: 测试阶段（10 小时）
RTX 3060: ¥1.2/小时 × 10 = ¥12

场景 2: 轻度使用（每天 2 小时）
RTX 3060: ¥1.2/小时 × 60 = ¥72/月

场景 3: 中度使用（每天 5 小时）
RTX 3060: ¥1.2/小时 × 150 = ¥180/月
```

### 本地部署

```
一次性投入:
RTX 3060 (12GB): ¥2000-2500（二手）
RTX 4060 Ti (16GB): ¥3500-4000（全新）

电费:
GPU 功耗: 170W
每天 5 小时: 170W × 5h × 30天 = 25.5度
电费: 25.5 × ¥0.6 = ¥15.3/月
```

**回本时间**:
- AutoDL ¥180/月 vs 本地 ¥15.3/月
- 本地投入 ¥3500 ÷ (¥180 - ¥15.3) = 21个月回本

---

## 🚀 快速测试流程

### AutoDL 方案（5 分钟）

```bash
1. 注册 AutoDL + 充值 ¥10           (2分钟)
2. 创建 RTX 3060 实例               (1分钟)
3. 上传 LoRA 文件                   (1分钟)
4. 启动 WebUI                       (30秒)
5. 配置项目 + 测试生成               (30秒)
```

### 本地部署方案（2 小时）

```bash
1. 下载 SD WebUI 整合包            (30分钟)
2. 解压 + 首次启动                  (30分钟)
3. 放置 LoRA 文件                   (1分钟)
4. 启用 API + 重启                  (5分钟)
5. 配置项目 + 测试生成               (5分钟)
```

---

## ✅ 推荐决策

### 选择 AutoDL 如果：
- ✅ 想快速测试（5 分钟上手）
- ✅ 短期/临时使用
- ✅ 没有高性能 GPU
- ✅ 不想折腾硬件

### 选择本地部署如果：
- ✅ 长期使用（超过 1 年）
- ✅ 已有 NVIDIA GPU
- ✅ 需要完全离线
- ✅ 数据安全要求高

---

## 📞 技术支持

- AutoDL 官方文档: https://www.autodl.com/docs/
- SD WebUI GitHub: https://github.com/AUTOMATIC1111/stable-diffusion-webui
- 秋葉aaaki 教程: https://space.bilibili.com/12566101

---

## 🎉 总结

**推荐方案**: 
1. **先用 AutoDL 测试**（¥10 充值，5 分钟上手）
2. **确认效果后决定是否本地部署**（长期使用更省钱）

**关键优势**:
- ✅ 完全国内服务，无需翻墙
- ✅ 完全支持自定义 LoRA
- ✅ 成本可控（AutoDL 按需计费）
- ✅ 代码已集成（LocalSdLoraAgent.java）

你想先试哪个方案？
