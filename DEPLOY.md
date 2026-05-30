# AI Studio 服务器部署说明

## 访问地址
部署完成后访问：**http://123.207.0.125:5020/**

---

## 第一步：打包（本地执行）

```bash
cd D:\code\ele-business-java
mvn clean package -DskipTests
```

---

## 第二步：上传文件到服务器

在腾讯云控制台 → 轻量应用服务器 → 选择 lhins-mu8totpr → 登录（免密连接 TAT）

在服务器终端执行，创建目录：

```bash
mkdir -p /www/wwwroot/ai-studio/data
mkdir -p /www/wwwroot/ai-studio/frontend
mkdir -p /www/wwwroot/ai-studio/prompts
```

然后用 1Panel 的文件管理器上传，或者用 SCP（如果有 SSH 密钥）：

**需要上传的文件：**
- `target/ele-business-java-1.0.0.jar` → 上传到 `/www/wwwroot/ai-studio/app.jar`
- `frontend/` 整个目录 → 上传到 `/www/wwwroot/ai-studio/frontend/`
- `prompts/` 整个目录 → 上传到 `/www/wwwroot/ai-studio/prompts/`

---

## 第三步：配置环境变量

在服务器终端执行（把下面的命令整段复制粘贴）：

```bash
cat > /www/wwwroot/ai-studio/.env << 'EOF'
GEMINI_API_KEY=AIzaSyAkjAU5HUrVBDZ7VGRi1vNqAFZrQGT6ecM
DASHSCOPE_API_KEY=sk-33bc9c1eb1c343fc9af12eb36e00481d
SILICONFLOW_API_KEY=sk-AzCJGOInyxZkDObimhqbU4SPQ5x4Q1aQNkU62EJGrgAnyT88
VOLCENGINE_API_KEY=34e179d9-eca6-4ba2-b05f-94a7ed1ca97b
GPT_IMAGE_KEY_1=sk-qiIGdpLGXotiLXDKBjmQjzZpgbB2klgKnHNW1aqzx7YyLbZS
GPT_IMAGE_KEY_2=sk-0tQ3BW9Lid0iq4cNGQIwk957N2wluOPeBsex4QgZgZ81ofSb
GPT_IMAGE_KEY_3=sk-sxxTPqyHP6eDrjWlTgz5kttgYUKUKop6DOmCnI15DpMXdjg4
GPT_IMAGE_KEY_4=sk-7Ei2pxbHQFZN5UweSsg2Ri6o1dnwe9qQT6qKbu61h2Hjqbhq
COS_SECRET_ID=AKIDHhy63vd9TakkX7ViiV6e6ZFlJk5JQVUW
COS_SECRET_KEY=1uhkhVBg5NafdT7ZpvrSr2BE6mLjvviI
COS_REGION=ap-guangzhou
COS_BUCKET=graduation-project-1416091844
APP_PASSWORD=Real1122
EOF
chmod 600 /www/wwwroot/ai-studio/.env
```

---

## 第四步：创建 systemd 服务（开机自启 + 崩溃重启）

```bash
cat > /etc/systemd/system/ai-studio.service << 'EOF'
[Unit]
Description=AI Studio
After=network.target

[Service]
EnvironmentFile=/www/wwwroot/ai-studio/.env
ExecStart=/usr/bin/java -Xmx1200m \
  -Dspring.web.resources.static-locations=file:/www/wwwroot/ai-studio/frontend/ \
  -Dapp.paths.config-file=/www/wwwroot/ai-studio/data/config.json \
  -Dapp.paths.output-dir=/www/wwwroot/ai-studio/data/生成结果 \
  -Dapp.paths.reference-dir=/www/wwwroot/ai-studio/data/大参考 \
  -Dapp.paths.prompts-dir=/www/wwwroot/ai-studio/prompts \
  -Dapp.paths.user-data-dir=/www/wwwroot/ai-studio/data \
  -jar /www/wwwroot/ai-studio/app.jar
WorkingDirectory=/www/wwwroot/ai-studio
Restart=always
RestartSec=5
User=root
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable ai-studio
systemctl start ai-studio
```

---

## 第五步：开放防火墙端口

**腾讯云控制台操作：**
1. 进入轻量应用服务器控制台
2. 选择 lhins-mu8totpr → 防火墙
3. 添加规则：协议 TCP，端口 5020，来源 0.0.0.0/0，备注"AI Studio"
4. 确认保存

---

## 验证是否启动成功

```bash
# 查看服务状态
systemctl status ai-studio

# 查看实时日志
journalctl -u ai-studio -f

# 测试本地是否响应
curl http://localhost:5020/
```

看到 HTML 内容说明启动成功，然后浏览器访问 http://123.207.0.125:5020/

---

## 后续更新流程

每次代码改动后：

**本地：**
```bash
mvn clean package -DskipTests
# 上传新 jar（用 1Panel 文件管理器或 SCP）
# 如果前端有改动，同时上传 frontend/ 目录
```

**服务器：**
```bash
systemctl restart ai-studio
```

---

## 常见问题

**启动失败看日志：**
```bash
journalctl -u ai-studio -n 50
```

**内存不足（2G 服务器）：**
- `-Xmx1200m` 限制 Java 最多用 1.2G
- 如果 OOM，改小到 `-Xmx900m`

**COS 上传失败：**
- 检查 SecretId/SecretKey 是否正确
- 检查 bucket 名称和 region 是否匹配
- 检查 bucket 是否设置了公开读权限
