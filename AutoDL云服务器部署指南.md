# AutoDL 云服务器部署完整指南

## 🚀 架构说明

```
AutoDL 云服务器（有公网 IP）:
├─ Java 项目 (端口 5020) → 用户通过公网访问
│
├─ SD WebUI (端口 7860) → Java 内部调用 localhost:7860
│
└─ GPU (RTX 3060 / 4090) → SD WebUI 使用
```

**关键**: Java 和 SD WebUI 在同一台服务器，仍通过 localhost 通信

---

## 📦 完整部署步骤

### 步骤 1: 注册 AutoDL

```
1. 访问: https://www.autodl.com/
2. 手机号注册
3. 充值（推荐 ¥50 起）
```

### 步骤 2: 创建 GPU 实例

#### 选择配置
```
GPU: RTX 3060 (12GB) - ¥1.2/小时
或:  RTX 4090 (24GB) - ¥3.5/小时

地区: 北京/上海/广州（就近选择）
镜像: Ubuntu 20.04
硬盘: 100GB（系统盘 + 数据盘）
```

#### 选择镜像

**推荐**: 选择预装 SD WebUI 的镜像

```
镜像市场 → 搜索 "stable diffusion webui"
选择: "PyTorch 2.0 + CUDA 11.8 + SD WebUI"
```

或选择基础镜像自己安装：
```
镜像: PyTorch 2.0
CUDA: 11.8
Python: 3.10
```

### 步骤 3: 连接服务器

```bash
# AutoDL 控制台会显示:
# IP: xxx.xxx.xxx.xxx
# SSH 端口: 12345
# 密码: xxxxxxxx

# SSH 连接
ssh root@xxx.xxx.xxx.xxx -p 12345
# 输入密码
```

### 步骤 4: 部署 SD WebUI

#### 方法 A: 使用预装镜像

```bash
# 如果选择了预装镜像，SD WebUI 已经在:
cd /root/stable-diffusion-webui

# 启动 WebUI（启用 API）
./webui.sh --api --listen --port 7860 --xformers --no-half-vae
```

#### 方法 B: 手动安装

```bash
# 克隆 SD WebUI
cd /root
git clone https://github.com/AUTOMATIC1111/stable-diffusion-webui.git
cd stable-diffusion-webui

# 首次启动（会自动安装依赖和下载模型）
./webui.sh --api --listen --port 7860 --xformers --no-half-vae

# 等待 10-20 分钟（下载模型 + 安装依赖）
```

### 步骤 5: 上传你的 LoRA

#### 方法 A: scp 上传（推荐）

```powershell
# 在你的本地 Windows PowerShell 执行
scp -P 12345 dianshangzhantaiXL.safetensors root@xxx.xxx.xxx.xxx:/root/stable-diffusion-webui/models/Lora/
```

#### 方法 B: JupyterLab 上传

```
1. AutoDL 控制台点击 "JupyterLab"
2. 进入文件管理器
3. 导航到 /root/stable-diffusion-webui/models/Lora/
4. 点击上传按钮，选择 dianshangzhantaiXL.safetensors
```

### 步骤 6: 配置开机自启动

```bash
# 创建 systemd 服务
sudo nano /etc/systemd/system/sd-webui.service
```

```ini
[Unit]
Description=Stable Diffusion WebUI
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/root/stable-diffusion-webui
ExecStart=/bin/bash -c 'cd /root/stable-diffusion-webui && ./webui.sh --api --listen --port 7860 --xformers --no-half-vae'
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
# 启用服务
sudo systemctl daemon-reload
sudo systemctl enable sd-webui
sudo systemctl start sd-webui

# 查看状态
sudo systemctl status sd-webui
```

### 步骤 7: 部署 Java 项目

#### 上传项目

```bash
# 在本地打包
cd F:\java\ele-business-java
C:\Users\19144\maven-portable\apache-maven-3.9.9\bin\mvn.cmd clean package

# 上传到服务器
scp -P 12345 target/ele-business-java-0.0.1-SNAPSHOT.jar root@xxx.xxx.xxx.xxx:/root/
```

#### 配置 application-prod.yml

```bash
# 服务器上编辑配置
nano /root/application-prod.yml
```

```yaml
server:
  port: 5020

app:
  local-sd:
    api-url: "http://localhost:7860"  # 关键！服务器内部通信
    lora-name: "dianshangzhantaiXL"
    lora-weight: 0.9

  # 其他 API 配置（Gemini、万相等）
  gemini:
    api-key: "你的密钥"
  # ... 其他配置
```

#### 启动 Java 项目

```bash
# 安装 Java 17
apt update
apt install openjdk-17-jdk -y

# 启动项目
nohup java -jar ele-business-java-0.0.1-SNAPSHOT.jar --spring.config.location=file:./application-prod.yml > app.log 2>&1 &

# 查看日志
tail -f app.log
```

#### 配置开机自启动

```bash
sudo nano /etc/systemd/system/ele-business.service
```

```ini
[Unit]
Description=Ele Business Java Application
After=network.target sd-webui.service

[Service]
Type=simple
User=root
WorkingDirectory=/root
ExecStart=/usr/bin/java -jar /root/ele-business-java-0.0.1-SNAPSHOT.jar --spring.config.location=file:/root/application-prod.yml
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable ele-business
sudo systemctl start ele-business
sudo systemctl status ele-business
```

### 步骤 8: 开放端口

```bash
# AutoDL 自带防火墙，需要在控制台开放端口

在 AutoDL 控制台:
1. 点击实例 → 端口映射
2. 添加映射:
   - 内部端口: 5020
   - 外部端口: 自动分配（如 34567）
   - 协议: TCP
```

### 步骤 9: 访问测试

```
公网访问地址:
http://xxx.xxx.xxx.xxx:34567

或 AutoDL 提供的域名:
http://xxxxx.autodl.com
```

---

## 🔒 安全配置

### 1. 配置 Nginx 反向代理（可选）

```bash
# 安装 Nginx
apt install nginx -y

# 配置
nano /etc/nginx/sites-available/ele-business
```

```nginx
server {
    listen 80;
    server_name xxx.xxx.xxx.xxx;

    location / {
        proxy_pass http://localhost:5020;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

```bash
ln -s /etc/nginx/sites-available/ele-business /etc/nginx/sites-enabled/
nginx -t
systemctl restart nginx
```

### 2. 配置 HTTPS（可选）

```bash
# 使用 Let's Encrypt
apt install certbot python3-certbot-nginx -y
certbot --nginx -d yourdomain.com
```

---

## 💰 成本计算

### 按需计费（推荐测试）

```
RTX 3060 (12GB): ¥1.2/小时
- 测试 10 小时: ¥12
- 每天 8 小时: ¥288/月

RTX 4090 (24GB): ¥3.5/小时
- 测试 10 小时: ¥35
- 每天 8 小时: ¥840/月
```

### 包月（推荐生产）

```
RTX 3060: ¥600-800/月（优惠价）
RTX 4090: ¥1500-2000/月（优惠价）
```

---

## 📊 监控和维护

### 查看 GPU 使用

```bash
# 安装 nvidia-smi
nvidia-smi

# 实时监控
watch -n 1 nvidia-smi
```

### 查看服务状态

```bash
# SD WebUI
systemctl status sd-webui
journalctl -u sd-webui -f

# Java 项目
systemctl status ele-business
journalctl -u ele-business -f
```

### 自动关机节省成本

```bash
# 设置空闲 2 小时后自动关机（节省成本）
# AutoDL 控制台 → 实例设置 → 自动关机
```

---

## ✅ 验证清单

- [ ] SD WebUI 启动成功 (localhost:7860)
- [ ] LoRA 文件已上传
- [ ] Java 项目启动成功 (localhost:5020)
- [ ] 配置文件中 api-url 为 localhost:7860
- [ ] 端口映射配置正确
- [ ] 可以通过公网 IP 访问前端
- [ ] 测试生成一张图成功

---

## 🚀 一键部署脚本

```bash
#!/bin/bash
# AutoDL 一键部署脚本

# 1. 部署 SD WebUI
cd /root
if [ ! -d "stable-diffusion-webui" ]; then
    git clone https://github.com/AUTOMATIC1111/stable-diffusion-webui.git
fi

cd stable-diffusion-webui
./webui.sh --api --listen --port 7860 --xformers --no-half-vae --exit &

# 2. 等待 SD WebUI 启动
sleep 60

# 3. 部署 Java 项目
cd /root
java -jar ele-business-java-0.0.1-SNAPSHOT.jar --spring.config.location=file:./application-prod.yml &

echo "部署完成！"
echo "SD WebUI: http://localhost:7860"
echo "Java 项目: http://localhost:5020"
```

保存为 `deploy.sh`，执行 `bash deploy.sh`

---

需要帮你配置哪一步？
