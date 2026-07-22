# liblib AI 快速启动命令

## 🚀 一键启动（复制粘贴即可）

### Windows PowerShell

```powershell
# 设置 API 密钥（必须）
$env:LIBLIB_API_KEY="VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"

# 设置 LoRA ID（可选，如果已上传）
# $env:LIBLIB_LORA_MODEL_ID="你的LoRA_ID"

# 启动项目
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

### Windows CMD

```cmd
set LIBLIB_API_KEY=VhJ23mT6RFBPykIArYAOYWfdkb66sp3G
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

---

## 📋 启动步骤

1. **复制上面的命令**
2. **粘贴到 PowerShell 或 CMD**
3. **按回车执行**
4. **等待项目启动**（约 30-60 秒）
5. **访问**: http://localhost:5020

---

## ✅ 验证是否成功

启动后，在日志中查找：

```
✓ LiblibConfig loaded successfully
✓ LiblibLoraAgent registered: liblib AI + LoRA (电商展台)
✓ 产品图片生成系统已启动，访问: http://localhost:5020
```

---

## 🎯 使用流程

1. 打开浏览器：http://localhost:5020
2. 登录（密码：123456）
3. 选择"自定义模式"
4. Agent 选择："liblib AI + LoRA (电商展台)"
5. 上传产品图
6. 点击生成

---

## ⚠️ 注意事项

### 第一次使用

如果还没有上传 LoRA：

1. 先不设置 `LIBLIB_LORA_MODEL_ID`
2. 启动后使用基础 SDXL 模型测试
3. 确认 API 可用后，再上传 LoRA

### 上传 LoRA 后

1. 访问 https://www.liblib.art/
2. 上传 `dianshangzhantaiXL.safetensors`
3. 获取 LoRA ID
4. 设置环境变量：`$env:LIBLIB_LORA_MODEL_ID="你的ID"`
5. 重启项目

---

## 🔍 测试 API（可选）

启动前测试 API 是否可用：

```powershell
Invoke-RestMethod -Uri "https://api.liblib.art/v1/models" `
  -Headers @{Authorization="Bearer VhJ23mT6RFBPykIArYAOYWfdkb66sp3G"}
```

如果返回数据（不是错误），说明 API 密钥有效。

---

## 📚 更多文档

- **完整说明**: `liblib_AI_集成完成.md`
- **配置说明**: `liblib_API_配置说明.md`
- **其他指南**: 查看项目根目录下的 `.md` 文件
