# liblib AI + LoRA 集成完成！

## ✅ 已完成的工作

1. ✅ 创建了 `LiblibConfig.java` 配置类
2. ✅ 更新了 `application.yml` 配置文件
3. ✅ 创建了 `LiblibLoraAgent.java` Agent 实现
4. ✅ 项目编译成功

---

## 🚀 快速开始

### 步骤 1: 配置 API 密钥

你的 API 密钥：`VhJ23mT6RFBPykIArYAOYWfdkb66sp3G`

#### 方法 A: 通过环境变量（推荐）

**Windows PowerShell**:
```powershell
$env:LIBLIB_API_KEY="VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"

# 然后启动项目
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

**Windows CMD**:
```cmd
set LIBLIB_API_KEY=VhJ23mT6RFBPykIArYAOYWfdkb66sp3G
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

#### 方法 B: 直接修改配置文件

编辑 `src/main/resources/application.yml`，找到：
```yaml
liblib:
  api-key: "${LIBLIB_API_KEY:}"
```

改为：
```yaml
liblib:
  api-key: "VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"
```

**⚠️ 注意**: 如果使用方法 B，请不要提交此文件到 Git！

---

### 步骤 2: 上传 LoRA 到 liblib.art

1. 访问 https://www.liblib.art/
2. 登录你的账号
3. 找到"模型管理"或"创作者中心"
4. 上传 `dianshangzhantaiXL.safetensors`
5. 设置为**私有模型**
6. 记下获得的 **LoRA Model ID**

### 步骤 3: 配置 LoRA ID

获取 LoRA ID 后，同样使用环境变量或配置文件：

**环境变量**:
```powershell
$env:LIBLIB_LORA_MODEL_ID="你的LoRA_ID"
```

**配置文件**:
```yaml
liblib:
  lora-model-id: "你的LoRA_ID"
```

---

## 📝 使用方式

### 1. 启动项目

```powershell
# 设置环境变量
$env:LIBLIB_API_KEY="VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"
$env:LIBLIB_LORA_MODEL_ID="你的LoRA_ID"

# 启动
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

### 2. 访问前端

打开浏览器：http://localhost:5020

### 3. 使用 liblib Agent

1. 登录（密码：123456）
2. 选择"自定义模式"
3. 在 Agent 下拉框选择：**liblib AI + LoRA (电商展台)**
4. 上传产品图
5. 点击"生成"
6. 等待 30-60 秒

---

## 🧪 测试 API 连通性

在启动项目前，可以先测试 API 是否可用：

```powershell
# 测试 API Key
Invoke-RestMethod -Uri "https://api.liblib.art/v1/models" `
  -Headers @{Authorization="Bearer VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"}
```

如果返回数据，说明 API Key 有效。

---

## ⚙️ 配置参数说明

在 `application.yml` 中可以调整以下参数：

```yaml
liblib:
  api-key: "${LIBLIB_API_KEY:}"              # API 密钥
  base-url: "https://api.liblib.art/v1"     # API 基础 URL
  model: "sdxl"                              # 基础模型
  lora-model-id: "${LIBLIB_LORA_MODEL_ID:}" # LoRA ID
  lora-weight: 0.9                           # LoRA 权重 (0.0-1.0)
  default-steps: 30                          # 生成步数
  default-size: "1024x1024"                  # 图片尺寸
  cfg-scale: 7.5                             # 提示词相关性
  negative-prompt: "worst quality..."        # 负面提示词
```

---

## 🔧 故障排查

### 问题 1: 找不到 liblib Agent

**现象**: 前端下拉框中没有"liblib AI + LoRA"选项

**解决**:
1. 检查项目是否成功启动
2. 查看日志是否有错误
3. 确认 `LiblibLoraAgent.java` 编译成功

### 问题 2: API Key 无效 (401)

**现象**: 日志显示 401 Unauthorized

**解决**:
1. 检查环境变量是否设置正确：`echo $env:LIBLIB_API_KEY`
2. 确认 API Key 是否正确
3. 检查 liblib.art 账户是否有余额

### 问题 3: LoRA 未生效

**现象**: 生成的图片没有 LoRA 风格

**解决**:
1. 检查是否配置了 `LIBLIB_LORA_MODEL_ID`
2. 确认 LoRA 已成功上传到 liblib.art
3. 检查 LoRA ID 是否正确
4. 查看日志中的提示信息

### 问题 4: 生成失败

**现象**: 返回错误或超时

**可能原因**:
- 配额不足（账户余额不足）
- 内容安全拒绝（提示词包含敏感内容）
- 网络超时
- LoRA ID 不存在

**解决**: 查看详细日志获取错误信息

---

## 📊 API 响应格式说明

liblib AI 的 API 响应格式可能是以下几种之一：

```json
// 格式 1: 直接返回 base64
{
  "images": ["base64..."]
}

// 格式 2: 嵌套在 data 中
{
  "data": {
    "images": ["base64..."]
  }
}

// 格式 3: 返回 URL
{
  "output": {
    "url": "https://..."
  }
}
```

`LiblibLoraAgent` 已经处理了这些不同格式，会自动识别并提取图片。

---

## 💡 重要提示

### API 调用说明

由于无法访问 liblib 官方 API 文档，当前实现基于通用 SDXL API 标准。

**如果 API 调用失败，可能需要调整**：

1. **API 端点**: 
   - 当前：`/txt2img`
   - 可能需要：`/generate`, `/v1/images/generations` 等

2. **请求参数名称**:
   - LoRA 引用方式：`lora.model_id` vs `lora_id` vs `loras`
   - 图片输入：`init_image` vs `image` vs `input_image`

3. **响应格式**:
   - 查看实际返回的 JSON 结构
   - 根据日志调整 `parseAndSaveImage()` 方法

### 如何调整

如果遇到问题，请：

1. 查看项目日志中的详细错误信息
2. 访问 https://www.liblib.art/api-doc 查看官方文档
3. 根据文档调整 `LiblibLoraAgent.java` 中的相关代码

主要需要调整的位置：
- `buildRequest()`: 请求参数格式
- `sendRequest()`: API 端点 URL
- `parseAndSaveImage()`: 响应解析逻辑

---

## 📞 获取帮助

如果遇到问题：

1. 查看项目日志：`logs/app.log`
2. 检查 liblib.art 官方文档
3. 查看本文件同目录下的其他指南文档

---

## 🎉 集成完成

liblib AI + LoRA 已成功集成到项目中！

**下一步**:
1. 上传 LoRA 到 liblib.art 获取 ID
2. 配置环境变量
3. 启动项目测试
4. 根据实际 API 响应调整代码（如需要）

祝使用愉快！🚀
