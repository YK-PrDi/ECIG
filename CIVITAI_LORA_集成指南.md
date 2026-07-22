# Civitai LoRA 完整集成指南

## ✅ 已完成的配置

### 1. API Key 已验证
```
API Key: b57551b9b2690f073d4661ea74caed6e
状态: ✅ 验证成功
配置位置: application-prod.yml
```

### 2. 项目配置已更新
```yaml
# application-prod.yml
civitai:
  api-key: "${CIVITAI_API_KEY:b57551b9b2690f073d4661ea74caed6e}"
  model: "sdxl"
  max-poll-seconds: 300
  lora-presets:
    studio:
      - id: 987654  # ← 需要替换为实际 ID
        name: "电商展台 XL"
        weight: 0.9
```

---

## 📋 接下来的步骤

### 步骤 1: 充值 Credits（必需）

**重要**：没有 Credits 无法生成图片！

1. 访问 https://civitai.com/user/account
2. 点击左侧菜单 **Buzz** 或 **Billing**
3. 选择充值金额：
   ```
   ┌─────────────────────────────────┐
   │ $10  = 1,000 Credits (推荐)    │ ← 约 50-70 张图
   │ $25  = 2,500 Credits            │
   │ $50  = 5,000 Credits            │
   └─────────────────────────────────┘
   ```
4. 支付方式：信用卡 / PayPal / 加密货币

**费用估算**：
- 单张图片 (1024×1024 + LoRA): 15-20 Credits
- $10 可以生成约 50-70 张图
- 测试建议先充 $10

---

### 步骤 2: 上传你的 LoRA 文件

你有本地文件：`dianshangzhantaiXL.safetensors`

#### 2.1 上传到 Civitai

1. **访问上传页面**
   ```
   https://civitai.com/models/create
   ```

2. **填写基本信息**
   ```
   Model Type: LORA ✓
   
   Name (名称):
   电商展台 XL
   或 E-commerce Display SDXL
   
   Base Model (基础模型):
   ☑ Stable Diffusion XL 1.0
   
   Description (描述，可选):
   专业电商产品展示台风格 LoRA，
   适用于产品摄影、商业展示、白底图生成
   
   Visibility (可见性):
   ○ Public (公开 - 其他人可以看到和使用)
   ● Private (私有 - 只有你能用) ← 推荐选这个
   ```

3. **上传文件**
   - 点击 **Upload Files**
   - 选择 `dianshangzhantaiXL.safetensors`
   - 等待上传完成（文件大小约 50-200MB）

4. **添加示例图片（可选但推荐）**
   - 上传几张用这个 LoRA 生成的效果图
   - 帮助你自己记住这个 LoRA 的风格

5. **点击 Publish**

#### 2.2 获取 LoRA ID

上传成功后，Civitai 会跳转到你的模型页面：

```
URL 示例: https://civitai.com/models/987654/dianshangzhantai-xl
                                      ^^^^^^
                                   这就是 LoRA ID
```

**复制这个 6 位数字**（比如 `987654`），这是你的 LoRA ID。

---

### 步骤 3: 更新配置文件

把实际的 LoRA ID 填入配置：

```yaml
# 编辑 application-prod.yml
civitai:
  lora-presets:
    studio:
      - id: 123456  # ← 替换为你的实际 ID
        name: "电商展台 XL"
        weight: 0.9
```

**weight 参数说明**：
- `0.9` - 风格强烈（推荐电商摄影）
- `0.7` - 风格适中
- `0.5` - 风格轻微

---

### 步骤 4: 重启项目

```powershell
# 进入项目目录
cd F:\java\ele-business-java

# 重启项目（Ctrl+C 停止 → 重新启动）
.\mvnw spring-boot:run
```

---

### 步骤 5: 测试 LoRA 功能

#### 5.1 启动项目后的检查

查看启动日志，确认：
```
✅ CivitaiConfig 加载成功
✅ CivitaiLoraService 初始化成功
✅ API Key 已配置
```

#### 5.2 前端测试

1. **访问** http://localhost:5020

2. **选择"自定义模式"**

3. **上传产品图片**
   - 准备一张白底产品图
   - 或任意产品图片

4. **填写提示词**
   ```
   产品摄影，专业三点布光，白色背景，高清晰度，
   商业摄影风格，柔和阴影，细节清晰
   ```

5. **勾选"启用 LoRA"**
   - ☑ 启用 LoRA 增强
   - 选择预设：`studio`（电商展台风格）

6. **其他参数**
   ```
   宽高比: 1:1 (1024×1024)
   生成数量: 1
   ```

7. **点击"生成"**

8. **等待 30-60 秒**
   - Civitai 会轮询任务状态
   - 完成后自动下载图片

#### 5.3 后端 API 测试

使用 Postman 或 PowerShell 测试：

```powershell
# 创建 multipart form-data 请求
$form = @{
    images = Get-Item "C:\path\to\product.jpg"
    prompt = "产品摄影，专业灯光，白底"
    useLora = "true"
    loraPreset = "studio"
    aspect = "1:1"
    count = "1"
    agentId = "gpt-image"
    sessionId = "test"
}

Invoke-RestMethod -Uri "http://localhost:5020/api/custom_generate" `
    -Method Post -Form $form
```

---

## 📊 效果对比测试

建议生成两张图对比效果：

### 测试 A：不使用 LoRA
```
提示词: 产品摄影，专业灯光，白底
☐ 不勾选"启用 LoRA"
模型: gpt-image
```

### 测试 B：使用电商展台 LoRA
```
提示词: 产品摄影，专业灯光，白底
☑ 勾选"启用 LoRA"
预设: studio
模型: civitai (自动切换)
```

**预期差异**：
- ✅ 使用 LoRA：专业展台布光、阴影自然、商业摄影质感
- ❌ 不使用 LoRA：普通质量、光影平淡

---

## 🐛 常见问题排查

### 问题 1: "Insufficient credits"
```
错误信息: Civitai API 错误: 402 - Insufficient credits
解决方案: 登录 Civitai 充值 Credits
```

### 问题 2: "LoRA not found"
```
错误信息: Civitai API 错误: 404 - LoRA not found
原因: LoRA ID 不正确或 LoRA 不支持 SDXL
解决方案: 
1. 检查配置文件中的 id 是否正确
2. 确认上传时选择的是 SDXL 1.0 基础模型
3. 暂时设置 id: 0 禁用 LoRA
```

### 问题 3: 轮询超时
```
错误信息: 轮询超时（超过 300 秒）
解决方案: 
1. 增加 max-poll-seconds 到 600
2. 检查网络连接
3. Civitai 服务器繁忙，稍后重试
```

### 问题 4: 前端没有"启用 LoRA"选项
```
原因: 前端可能还没有这个功能
解决方案: 
1. 检查前端代码是否有 useLora 参数
2. 或者直接用 API 测试（见上面 PowerShell 示例）
3. 需要前端开发添加 LoRA 开关
```

### 问题 5: 图片质量不理想
```
解决方案:
1. 调整 weight（0.6-1.0）
2. 优化提示词，增加细节描述
3. 尝试不同的提示词模板
```

---

## 🎯 提示词优化模板

### 电商展台风格（studio）
```
产品摄影，专业展台，三点布光，白色背景，
高清晰度，商业摄影，柔和阴影，细节清晰，
8K 分辨率，专业相机拍摄，产品居中摆放
```

### 生活场景风格（lifestyle）
```
产品摆放在现代家居，自然光线，温馨氛围，
木质桌面，绿植装饰，生活化场景，Instagram 风格，
柔和色调，舒适感
```

### 极简风格（minimal）
```
纯白背景，产品居中，极简构图，干净利落，
无杂物，高级感，留白设计，专业商业摄影，
简洁大方
```

---

## 📝 配置检查清单

完成集成前的检查：

- [ ] Civitai 账号已注册并验证邮箱
- [ ] API Key 已获取：`b575...ed6e`
- [ ] API Key 已配置到 `application-prod.yml`
- [ ] API Key 已验证（PowerShell 测试成功）
- [ ] Credits 已充值（至少 $10）
- [ ] LoRA 文件已上传到 Civitai
- [ ] LoRA ID 已获取并填入配置
- [ ] 配置文件中的 `id: 987654` 已替换为实际 ID
- [ ] 项目已重启
- [ ] 准备好测试用的产品图片
- [ ] 前端有"启用 LoRA"选项（或用 API 测试）

---

## 💰 费用跟踪

记录你的使用情况：

| 日期 | 操作 | Credits 消耗 | 剩余 Credits |
|------|------|-------------|-------------|
| 2026-07-08 | 充值 | +1000 | 1000 |
| 2026-07-08 | 测试生成 1 张 | -18 | 982 |
| ... | ... | ... | ... |

**查看余额**：
- 访问 https://civitai.com/user/account
- 查看 **Buzz Balance** 或 **Credits**

---

## 🔗 相关资源

- **Civitai 官网**: https://civitai.com/
- **API 文档**: https://developer.civitai.com/docs/api/public-rest
- **充值页面**: https://civitai.com/user/account
- **上传 LoRA**: https://civitai.com/models/create
- **我的 Models**: https://civitai.com/models?username=你的用户名

---

## ✅ 集成完成标准

当以下所有项都完成时，集成成功：

1. ✅ API 调用成功返回图片
2. ✅ 图片质量明显优于不使用 LoRA
3. ✅ 电商展台风格效果明显（专业布光、阴影自然）
4. ✅ 生成时间在 30-60 秒内
5. ✅ Credits 消耗符合预期（15-20 Credits/张）
6. ✅ 多张图片风格一致

---

## 🎉 下一步

集成完成后，你可以：

1. **添加更多 LoRA 预设**
   - 上传其他风格的 LoRA
   - 配置 `lifestyle` 和 `minimal` 预设

2. **优化提示词模板**
   - 根据实际效果调整提示词
   - 保存最佳提示词模板

3. **批量生成测试**
   - 测试批量生成性能
   - 验证风格一致性

4. **前端界面优化**
   - 添加 LoRA 预设选择器
   - 添加 weight 调节滑块
   - 显示 Credits 余额

Good luck! 🚀
