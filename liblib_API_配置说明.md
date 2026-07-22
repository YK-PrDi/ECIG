# liblib AI 配置说明

## API 密钥配置

你的 liblib AI API 密钥：`VhJ23mT6RFBPykIArYAOYWfdkb66sp3G`

### 配置方式

#### 方法 1: 环境变量（推荐生产环境）

```bash
# Windows PowerShell
$env:LIBLIB_API_KEY="VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"

# Linux/Mac
export LIBLIB_API_KEY="VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"
```

#### 方法 2: 直接在 application.yml 填写（仅开发测试）

```yaml
app:
  liblib:
    api-key: "VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"
```

**注意**: 不要提交包含真实 API 密钥的配置文件到 Git！

## LoRA 配置步骤

### 1. 上传 LoRA 到 liblib.art

1. 访问 https://www.liblib.art/
2. 登录你的账号
3. 进入"创作者中心"或"模型管理"
4. 上传 `dianshangzhantaiXL.safetensors`
5. 设置为**私有模型**（仅自己可用）
6. 获取 LoRA Model ID（通常是数字 ID 或字符串）

### 2. 配置 LoRA Model ID

获取 LoRA ID 后，通过以下方式之一配置：

#### 环境变量
```bash
$env:LIBLIB_LORA_MODEL_ID="你的LoRA_ID"
```

#### 配置文件
```yaml
app:
  liblib:
    lora-model-id: "你的LoRA_ID"
```

## 使用方式

配置完成后：

1. 启动项目
```bash
cd F:\java\ele-business-java
mvn spring-boot:run
```

2. 访问前端：http://localhost:5020

3. 选择 Agent：`liblib AI + LoRA (电商展台)`

4. 上传产品图，点击生成

## API 测试

测试 API 连通性：

```powershell
# 测试 API Key
Invoke-RestMethod -Uri "https://api.liblib.art/v1/models" `
  -Headers @{Authorization="Bearer VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"}
```

## 故障排查

### 问题 1: API Key 无效

**现象**: 返回 401 Unauthorized

**解决**: 检查 API Key 是否正确配置

### 问题 2: LoRA Model ID 未配置

**现象**: 日志显示 "LoRA Model ID 未配置"

**解决**: 按照上述步骤上传 LoRA 并配置 ID

### 问题 3: 生成失败

**现象**: 返回错误信息

**可能原因**:
- 配额不足
- 内容安全拒绝
- 网络超时
- LoRA ID 不存在

**解决**: 查看日志获取详细错误信息

## 成本估算

liblib AI 按次计费（假设 ¥0.20/张）：

```
每天生成 50 张:  ¥10/天 = ¥300/月
每天生成 100 张: ¥20/天 = ¥600/月
每天生成 200 张: ¥40/天 = ¥1200/月
```

## 注意事项

1. **不要泄露 API 密钥**: 不要将密钥提交到 Git 仓库
2. **LoRA 必须上传**: liblib API 需要先上传 LoRA 到平台
3. **配额管理**: 注意账户余额，避免生成失败
4. **私有模型**: 上传 LoRA 时选择私有，避免被他人使用
