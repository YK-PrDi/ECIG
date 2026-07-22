# Civitai LoRA 功能测试指南

## 📋 前置准备

### 1. 获取 Civitai API Key

1. 访问 https://civitai.com/
2. 注册并登录账号
3. 进入 Account Settings → API Keys
4. 创建 API Key 并复制
5. （可选）充值 Credits（最低 $10，约 ¥70）

### 2. 配置 API Key

```bash
# Windows PowerShell
$env:CIVITAI_API_KEY="你的API密钥"

# 或者直接修改 application-prod.yml
civitai:
  api-key: "你的API密钥"
```

### 3. 查找合适的 LoRA

访问 https://civitai.com/models 搜索以下关键词：

**推荐搜索（产品摄影）**：
- `product photography sdxl`
- `commercial photography`
- `studio lighting sdxl`
- `white background product`

**如何获取 LoRA ID**：
```
示例 URL: https://civitai.com/models/234567/product-studio-pro
提取 ID: 234567 ← 就是这个数字
```

**推荐的产品摄影 LoRA**（需要在 Civitai 搜索确认实际 ID）：
- Product Photography SDXL
- Studio Lighting Pro
- Commercial Product Shot
- Clean Background Product

### 4. 更新配置文件

找到 LoRA 后，更新 `application-prod.yml`:

```yaml
civitai:
  api-key: "你的密钥"
  model: "sdxl"
  lora-presets:
    studio:
      - id: 234567  # ← 替换为实际的 LoRA ID
        name: "Product Photography Pro"
        weight: 0.9
    lifestyle:
      - id: 345678  # ← 替换为实际的 LoRA ID
        name: "Lifestyle Product"
        weight: 0.85
    minimal:
      - id: 456789  # ← 替换为实际的 LoRA ID
        name: "Clean Minimal"
        weight: 0.8
```

---

## 🧪 测试步骤

### 方法 1: 使用前端界面测试

1. 启动项目：
```bash
cd F:\java\ele-business-java
mvnw spring-boot:run
```

2. 访问 http://localhost:5020

3. 进入**自定义模式**

4. 上传一张白底产品图

5. 填写提示词，例如：
```
产品摄影，专业三点布光，白色背景，高清晰度，商业摄影风格
```

6. 勾选 **启用 LoRA 增强**

7. 选择预设：`studio`（工作室风格）

8. 点击**生成**

9. 等待 30-60 秒查看结果

### 方法 2: 使用 API 测试

使用 Postman 或 curl 测试：

```bash
curl -X POST http://localhost:5020/api/custom_generate \
  -F "images=@产品图.jpg" \
  -F "prompt=产品摄影，专业灯光，白底" \
  -F "useLora=true" \
  -F "loraPreset=studio" \
  -F "aspect=1:1" \
  -F "count=1" \
  -F "agentId=gpt-image" \
  -F "sessionId=test"
```

---

## 📊 对比测试

建议同时生成两张图对比效果：

### 测试 A：不使用 LoRA
```
提示词: 产品摄影，专业灯光，白底
useLora: false
agentId: gpt-image
```

### 测试 B：使用 LoRA
```
提示词: 产品摄影，专业灯光，白底
useLora: true
loraPreset: studio
agentId: gpt-image （代码会自动切换到 civitai）
```

**预期差异**：
- ✅ 使用 LoRA：专业三点布光、阴影自然、质感真实、风格统一
- ❌ 不使用 LoRA：普通质量、光影平淡、风格不稳定

---

## 💰 费用估算

| 操作 | Credits 消耗 | 约合人民币 |
|------|-------------|-----------|
| 生成 1 张 (1024×1024) | 15-20 Credits | ¥1-1.5 |
| 生成 10 张 | 150-200 Credits | ¥10-15 |
| 生成 100 张 | 1500-2000 Credits | ¥100-150 |

**充值建议**：
- 测试阶段：$10 (1000 Credits，约 50-70 张图)
- 正式使用：$50-100 (5000-10000 Credits)

---

## 🐛 常见问题

### 1. API Key 无效
```
错误: "Civitai API 错误: 401 - Unauthorized"
解决: 检查 API Key 是否正确，是否已激活
```

### 2. Credits 不足
```
错误: "Civitai API 错误: 402 - Insufficient credits"
解决: 登录 Civitai 充值 Credits
```

### 3. LoRA ID 无效
```
错误: "Civitai API 错误: 404 - LoRA not found"
解决: 
1. 检查 LoRA ID 是否正确
2. 确认 LoRA 支持 SDXL 模型（不支持 SD 1.5 的 LoRA）
3. 暂时将 id 设为 0 禁用 LoRA
```

### 4. 生成超时
```
错误: "轮询超时（超过 300 秒）"
解决: 
1. 增加 max-poll-seconds 到 600
2. 检查网络连接
3. Civitai 服务器可能繁忙，稍后重试
```

### 5. 图片质量不理想
```
解决:
1. 调整 LoRA weight（0.6-1.0 之间）
2. 优化提示词，增加细节描述
3. 尝试不同的 LoRA 模型
4. 提高分辨率（但费用会增加）
```

---

## 🎯 优化建议

### 提示词模板（中文）

**工作室风格**：
```
产品摄影，专业三点布光，白色背景，高清晰度，商业摄影风格，
柔和阴影，细节清晰，8K 分辨率，专业相机拍摄
```

**生活场景风格**：
```
产品摆放在现代家居环境，自然光线，温馨氛围，生活化场景，
木质桌面，绿植点缀，Instagram 风格
```

**极简风格**：
```
纯白背景，产品居中，极简构图，干净利落，无杂物，
高级感，留白设计，专业商业摄影
```

### LoRA 权重调整

```yaml
# 风格强烈（推荐产品摄影）
weight: 0.9

# 风格适中（推荐生活场景）
weight: 0.7

# 风格轻微（推荐极简风格）
weight: 0.5
```

---

## 📝 测试检查清单

- [ ] Civitai 账号已注册
- [ ] API Key 已获取并配置
- [ ] Credits 已充值（至少 $10）
- [ ] 找到至少 1 个合适的产品摄影 LoRA
- [ ] LoRA ID 已填入 application-prod.yml
- [ ] 项目已重启（读取新配置）
- [ ] 准备好测试用的白底产品图
- [ ] 测试生成 1 张图成功
- [ ] 对比有/无 LoRA 的效果差异
- [ ] 记录 Credits 消耗和成本

---

## 🔗 相关资源

- **Civitai 官网**: https://civitai.com/
- **Civitai API 文档**: https://developer.civitai.com/docs/api/public-rest
- **SDXL LoRA 推荐**: https://civitai.com/models?modelType=LORA&baseModel=SDXL
- **产品摄影 LoRA 搜索**: https://civitai.com/search/models?query=product+photography+sdxl

---

## ✅ 测试成功标准

1. ✅ API 调用成功，返回图片 URL
2. ✅ 图片质量明显优于不使用 LoRA
3. ✅ 光影效果专业，符合产品摄影标准
4. ✅ 多张图片风格一致
5. ✅ Credits 消耗在预期范围内（15-20 Credits/张）

测试完成后，请记录：
- 使用的 LoRA ID 和名称
- 最佳 weight 参数
- 每张图片 Credits 消耗
- 生成时间（秒）
- 效果评分（1-10）

Good luck! 🎉
