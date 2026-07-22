# AutoDL 作为私有 API 服务方案

## 💡 核心思路

把 AutoDL GPU 服务器当作**你的私有 AI API 服务**：
- 部署 SD WebUI + 你的 LoRA
- 暴露 API 接口
- Java 项目通过 HTTP 调用
- 效果等同于"国内 LoRA API 服务"

---

## 🏗️ 架构

```
你的 Java 服务器（生产环境）
    ↓ HTTP API 调用
AutoDL GPU 服务器（你的私有 API）
    ├─ SD WebUI + LoRA
    ├─ 固定公网 IP
    └─ 24 小时运行（或按需启动）
    ↓
返回生成的图片
```

---

## 📦 完整部署方案

### 步骤 1: 创建 AutoDL 实例

```
GPU: RTX 3060 (12GB) - ¥1.2/小时
镜像: PyTorch + SD WebUI
硬盘: 50GB
开机方式: 按需开机
```

### 步骤 2: 部署 SD WebUI API

```bash
# SSH 连接
ssh root@xxx.xxx.xxx.xxx -p 12345

# 启动 SD WebUI（启用 API + 远程访问）
cd /root/stable-diffusion-webui
./webui.sh --api --listen 0.0.0.0 --port 7860 --xformers --no-half-vae
```

### 步骤 3: 上传 LoRA

```bash
# 本地上传
scp -P 12345 dianshangzhantaiXL.safetensors \
  root@xxx.xxx.xxx.xxx:/root/stable-diffusion-webui/models/Lora/
```

### 步骤 4: 配置安全访问

#### 方法 A: IP 白名单（推荐）

```bash
# 安装 Nginx
apt update && apt install nginx -y

# 配置反向代理 + IP 限制
nano /etc/nginx/sites-available/sd-api
```

```nginx
server {
    listen 80;
    server_name _;

    # 只允许你的 Java 服务器 IP 访问
    allow 你的服务器IP;
    deny all;

    location / {
        proxy_pass http://127.0.0.1:7860;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        
        # 超时设置（生成图片可能需要 1 分钟）
        proxy_read_timeout 300s;
        proxy_connect_timeout 300s;
    }
}
```

```bash
# 启用配置
ln -s /etc/nginx/sites-available/sd-api /etc/nginx/sites-enabled/
nginx -t
systemctl restart nginx
```

#### 方法 B: API Key 认证

```bash
# 创建认证脚本
nano /root/sd-api-auth.py
```

```python
from flask import Flask, request, jsonify
import requests
import os

app = Flask(__name__)

# 你的 API Key（随机生成一个）
API_KEY = "sk-your-secret-key-12345"

# SD WebUI 地址
SD_API = "http://127.0.0.1:7860"

@app.before_request
def check_auth():
    auth_header = request.headers.get('Authorization')
    if not auth_header or auth_header != f"Bearer {API_KEY}":
        return jsonify({"error": "Unauthorized"}), 401

@app.route('/sdapi/v1/<path:path>', methods=['GET', 'POST'])
def proxy(path):
    url = f"{SD_API}/sdapi/v1/{path}"
    
    if request.method == 'POST':
        resp = requests.post(url, json=request.json, timeout=300)
    else:
        resp = requests.get(url, timeout=300)
    
    return resp.content, resp.status_code

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
```

```bash
# 安装依赖
pip install flask requests

# 启动认证服务
nohup python /root/sd-api-auth.py > /root/api-auth.log 2>&1 &
```

### 步骤 5: 在 Java 项目中配置

#### 修改 application-prod.yml

```yaml
app:
  local-sd:
    # AutoDL 实例的公网 IP
    api-url: "http://xxx.xxx.xxx.xxx:7860"
    # 或使用 API Key 认证
    # api-url: "http://xxx.xxx.xxx.xxx:8080"
    # api-key: "sk-your-secret-key-12345"
    lora-name: "dianshangzhantaiXL"
    lora-weight: 0.9
```

#### 修改 LocalSdLoraAgent.java（添加认证）

```java
@Value("${app.local-sd.api-key:}")
private String apiKey;

private OkHttpClient buildClient() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS);
    
    // 如果配置了 API Key，添加认证
    if (apiKey != null && !apiKey.isEmpty()) {
        builder.addInterceptor(chain -> {
            Request original = chain.request();
            Request request = original.newBuilder()
                .header("Authorization", "Bearer " + apiKey)
                .build();
            return chain.proceed(request);
        });
    }
    
    return builder.build();
}
```

### 步骤 6: 测试连接

```bash
# 测试 SD WebUI API
curl -X GET http://xxx.xxx.xxx.xxx:7860/sdapi/v1/sd-models

# 测试生成
curl -X POST http://xxx.xxx.xxx.xxx:7860/sdapi/v1/txt2img \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "<lora:dianshangzhantaiXL:0.9> product photography",
    "steps": 30,
    "width": 1024,
    "height": 1024
  }'
```

---

## 💰 成本分析

### 按需开机（推荐测试）

```
使用方式: 需要时开机，不用就关机

RTX 3060:
- 每天生成 2 小时: ¥1.2 × 2 = ¥2.4/天
- 每月（按 20 天计算）: ¥48/月

RTX 4090:
- 每天生成 2 小时: ¥3.5 × 2 = ¥7/天
- 每月（按 20 天计算）: ¥140/月
```

### 24 小时运行（包月优惠）

```
AutoDL 包月价格（24小时运行）:
RTX 3060: ¥600-800/月（优惠 30%）
RTX 4090: ¥1500-2000/月（优惠 30%）
```

### 对比其他 API

```
生成 1000 张图:

liblib AI:    ¥150-300
Tusi Art:     ¥200-400
AutoDL 按需:  ¥48（按每天 2 小时）
AutoDL 包月:  ¥600（24小时可用）
```

---

## 🎯 优势对比

| 特性 | liblib AI | AutoDL 自建 |
|------|-----------|-------------|
| **国内访问** | ✅ 快 | ✅ 快 |
| **自定义 LoRA** | ✅ 支持 | ✅ 完全掌控 |
| **价格** | ¥0.15-0.3/张 | ¥1.2/小时 |
| **灵活性** | ⚠️ 受限 | ✅ 完全自由 |
| **模型版本** | ⚠️ 固定 | ✅ 任意版本 |
| **参数调整** | ⚠️ 有限 | ✅ 完全控制 |
| **数据安全** | ⚠️ 上传到平台 | ✅ 私有部署 |

---

## 🚀 快速启动脚本

### AutoDL 服务器启动脚本

```bash
#!/bin/bash
# 保存为 start-api.sh

# 启动 SD WebUI
cd /root/stable-diffusion-webui
nohup ./webui.sh --api --listen 0.0.0.0 --port 7860 --xformers --no-half-vae \
  > /root/sd-webui.log 2>&1 &

echo "SD WebUI API 已启动"
echo "日志: tail -f /root/sd-webui.log"
echo "API: http://$(curl -s ifconfig.me):7860/sdapi/v1/"
```

### 开机自动启动

```bash
# 添加到 crontab
crontab -e

# 添加这行
@reboot sleep 30 && /bin/bash /root/start-api.sh
```

---

## 📊 选择建议

### 选择 liblib AI 如果：
- ✅ 不想管理服务器
- ✅ 只需要基础的 LoRA 功能
- ✅ 按次付费更省心

### 选择 AutoDL 自建如果：
- ✅ 需要完全掌控
- ✅ 需要调整各种参数
- ✅ 数据安全要求高
- ✅ 长期大量使用（更省钱）

---

## ✅ 总结

AutoDL 自建方案实际上就是**你的私有 LoRA API 服务**：
1. 完全国内服务
2. 支持任意 LoRA
3. 通过 API 调用
4. 成本可控
5. 数据安全

需要我帮你配置哪一步？
