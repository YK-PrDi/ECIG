# 国内 AI 图片生成完整方案对比

## 📋 服务对比表

| 服务 | 厂商 | 支持自定义 LoRA | 价格 | 速度 | 质量 | 推荐度 |
|------|------|-----------------|------|------|------|--------|
| **阿里万相 2.7** | 阿里云 | ❌ 不支持 | ¥0.08/张 | 5-10秒 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **通义千问 VL** | 阿里云 | ❌ 不支持 | ¥0.02/张 | 5-10秒 | ⭐⭐⭐ | ⭐⭐⭐ |
| **腾讯混元** | 腾讯云 | ❌ 不支持 | ¥0.15/张 | 10-15秒 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **豆包 Seedance** | 字节 | ❌ 不支持 | ¥0.12/张 | 8-12秒 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **百度文心** | 百度 | ❌ 不支持 | ¥0.10/张 | 10-15秒 | ⭐⭐⭐ | ⭐⭐⭐ |
| **liblib AI** | 第三方 | ✅ **支持** | ¥0.20/张 | 15-30秒 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Tusi Art** | 第三方 | ✅ **支持** | ¥0.25/张 | 20-40秒 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **AutoDL 自建** | 自建 | ✅ **完全掌控** | ¥1.2/小时 | 30-60秒 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

---

## 🎯 各大厂的图片生成能力

### 1. 阿里云 - 通义万相 2.7（你已集成）

**特点**：
```
✅ 速度快（5-10秒）
✅ 价格便宜（¥0.08/张）
✅ 质量不错
✅ 有"产品摄影"等预设风格
❌ 不支持自定义 LoRA
```

**API 文档**：
```
https://help.aliyun.com/document_detail/2712568.html
```

**使用示例**：
```bash
curl -X POST "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "wanx-v2.7",
    "input": {
      "prompt": "产品摄影，白色背景，专业灯光，商业级"
    },
    "parameters": {
      "size": "1024*1024",
      "style": "<product>"  # 产品摄影风格
    }
  }'
```

**你的项目中**：
```java
// src/main/java/com/elebusiness/service/agent/WanxiangAgent.java
// 已经实现，直接可用！
```

---

### 2. 阿里云 - 通义千问 VL（你已集成）

**特点**：
```
✅ 超便宜（¥0.02/张）
✅ 速度快
⚠️ 主要用于图片理解，生成质量一般
❌ 不支持自定义 LoRA
```

**适用场景**：
- 商品图分析（已在你项目中用于降级）
- 图片标注
- 不适合专业生图

---

### 3. 腾讯云 - 混元 3.0（你已集成）

**特点**：
```
✅ 质量好
✅ 有"商业摄影"风格
✅ 支持图生图
❌ 价格稍贵（¥0.15/张）
❌ 不支持自定义 LoRA
```

**API 文档**：
```
https://cloud.tencent.com/document/product/1729
```

**你的项目中**：
```java
// src/main/java/com/elebusiness/service/agent/HunyuanAgent.java
// 已经实现，直接可用！
```

---

### 4. 字节跳动 - 豆包 Seedance 2.0（你已集成）

**特点**：
```
✅ 新模型，质量高
✅ 速度快
✅ 支持多种风格
❌ 不支持自定义 LoRA
```

**API 文档**：
```
https://www.volcengine.com/docs/82379
```

**你的项目中**：
```java
// src/main/java/com/elebusiness/service/agent/VolcengineAgent.java
// 已经实现，直接可用！
```

---

### 5. 百度 - 文心一格

**特点**：
```
✅ 国内老牌
✅ 稳定
⚠️ 质量中等
❌ 不支持自定义 LoRA
```

**API 文档**：
```
https://cloud.baidu.com/doc/WENXINWORKSHOP/s/Klkqubb9w
```

**集成示例**（如果你想加）：
```java
@Component
public class WenxinAgent implements ImageGeneratorAgent {
    @Override
    public String getId() {
        return "wenxin";
    }
    
    @Override
    public String getDisplayName() {
        return "百度文心一格";
    }
    
    // ... 实现细节
}
```

---

## 🔍 大厂 API 能否实现 LoRA 效果？

### 方案 A: 用提示词模拟

**原理**：通过详细的提示词描述你的 LoRA 风格

```
你的 LoRA: "电商展台 XL"

模拟提示词:
"professional product photography, white background, 
studio lighting, clean composition, commercial photography style,
high-end product display, minimalist aesthetic"

效果: 可以达到 70-80% 的相似度
```

**优势**：
- ✅ 不需要 GPU
- ✅ 速度快
- ✅ 成本低

**劣势**：
- ❌ 无法完全复现 LoRA 的特定风格
- ❌ 一致性较差

---

### 方案 B: 使用大厂的内置风格

**阿里万相内置风格**：
```yaml
style: "<product>"       # 产品摄影
style: "<3d cartoon>"    # 3D 卡通
style: "<anime>"         # 动漫
style: "<oil painting>"  # 油画
style: "<watercolor>"    # 水彩
style: "<sketch>"        # 素描
```

**腾讯混元内置风格**：
```
101: 自动（推荐）
102: 产品摄影
103: 商业摄影
201: 写实风格
```

---

## 💡 实际建议

### 场景 1: 不需要特定 LoRA 风格

**推荐**：直接用大厂 API

```
你已经集成的:
✅ 阿里万相 (¥0.08/张，速度快)
✅ 腾讯混元 (¥0.15/张，质量高)
✅ 字节豆包 (¥0.12/张，新模型)

操作:
1. 在 application-prod.yml 填写 API Key
2. 前端选择对应的 Agent
3. 立即可用

成本: 每天生成 100 张 = ¥8-15/天
```

---

### 场景 2: 必须要你的自定义 LoRA

**推荐**：liblib AI 或 AutoDL 自建

```
选项 A: liblib AI
- 上传你的 LoRA
- 通过 API 调用
- ¥0.20/张

选项 B: AutoDL 自建
- 完全掌控
- ¥1.2/小时（按需开机）
- 更灵活
```

---

## 🚀 立即可用方案（不需要 LoRA）

### 测试你已有的大厂 API

#### 1. 配置 API Key

```yaml
# F:\java\ele-business-java\src\main\resources\application-prod.yml

app:
  # 阿里万相（推荐）
  dashscope:
    api-key: "sk-xxxxxx"  # 去阿里云控制台获取
    model: "wanx-v2.7"
  
  # 腾讯混元
  siliconflow:
    api-key: "sk-xxxxxx"  # 去硅基流动获取
    model: "hy-image-v3.0"
  
  # 字节豆包
  volcengine:
    api-key: "sk-xxxxxx"  # 去火山引擎获取
```

#### 2. 获取 API Key

**阿里云万相**：
```
1. 访问 https://dashscope.console.aliyun.com/
2. 开通"通义万相"
3. 创建 API Key
4. 充值（¥50 测试）
```

**腾讯混元**（通过硅基流动）：
```
1. 访问 https://cloud.siliconflow.cn/
2. 注册账号
3. 创建 API Key
4. 充值（¥50 测试）
```

**字节豆包**：
```
1. 访问 https://console.volcengine.com/ark
2. 开通"火山方舟"
3. 创建 API Key
4. 充值（¥50 测试）
```

#### 3. 启动项目测试

```powershell
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

#### 4. 前端选择 Agent

```
访问: http://localhost:5020
选择: 
- "万相 2.7 产品图生成"
- "混元 3.0 图片生成"
- "豆包 Seedance 2.0"

上传图片 → 生成
```

---

## 📊 最终推荐

### 推荐组合方案

```
日常使用: 大厂 API（万相/混元/豆包）
├─ 成本低: ¥0.08-0.15/张
├─ 速度快: 5-15秒
├─ 质量好: 满足大部分需求
└─ 已集成: 立即可用

特殊需求: liblib AI + LoRA
├─ 自定义风格: 你的 LoRA
├─ 成本: ¥0.20/张
└─ API 调用: 和大厂一样简单

极致需求: AutoDL 自建
├─ 完全掌控
├─ 任意调参
└─ 数据安全
```

---

你想先测试哪个？我帮你配置 API Key。
