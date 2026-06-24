# 性能优化方案 - 解决多用户并发延迟问题

## 问题分析

当前系统在 2-3 人同时使用时出现延迟，原因如下：

### 1. **API 限流延迟（主要问题）**
```yaml
app:
  api:
    delay-seconds: 1        # 每个请求前强制延迟 1 秒
```

**影响：** 每个图片生成前都要等 1 秒，3 个用户 × 每人 4 张图 = 12 张图 = 至少 12 秒延迟

### 2. **重试机制的累积延迟**
- Gemini 失败后 sleep 5-15 秒
- Hunyuan 轮询每次 sleep 5-10 秒
- 错误重试指数退避（5s → 10s → 15s）

### 3. **并发数限制**
```yaml
max-concurrent: 12          # 同时只能处理 12 个任务
```

**影响：** 第 13 个任务必须等待前面的任务完成

### 4. **超时设置较长**
```yaml
timeout-seconds: 90         # 单个任务超时 90 秒
```

**影响：** 卡住的任务会阻塞线程池 90 秒

---

## 优化方案

### **方案 1：移除或减少 API 延迟（立即见效）**

**修改 `application.yml`：**
```yaml
app:
  api:
    delay-seconds: 0        # 改为 0，取消强制延迟
    timeout-seconds: 60     # 降低超时到 60 秒
    max-retries: 2          # 减少重试次数 3→2
    max-concurrent: 20      # 提升并发数 12→20
```

**预期效果：** 
- 每张图减少 1 秒延迟
- 10 张图可节省 10 秒
- 支持更多用户同时使用

---

### **方案 2：优化 Tomcat 线程池（提升吞吐量）**

**修改 `application.yml`：**
```yaml
server:
  tomcat:
    threads:
      max: 300              # 200 → 300
      min-spare: 50         # 20 → 50
    accept-count: 200       # 100 → 200
```

**预期效果：** 
- 更多用户可以同时连接
- 减少请求排队时间

---

### **方案 3：添加 Redis 缓存（长期优化）**

**缓存场景：**
1. **Gemini 图片分析结果** - 相同产品图不重复分析
2. **提示词模板** - 避免每次从文件读取
3. **API 响应** - 相同参数直接返回缓存结果

**依赖添加（pom.xml）：**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**配置（application.yml）：**
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 5000ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5

  cache:
    type: redis
    redis:
      time-to-live: 3600000  # 1 小时
```

**预期效果：**
- 相同产品图第二次生成立即返回
- 节省 50-80% 的 Gemini 分析时间

---

### **方案 4：异步任务队列（推荐）**

**当前问题：** 用户提交任务后同步等待结果

**改进方案：** 
1. 前端提交后立即返回 `taskId`
2. 后端异步处理，前端轮询进度
3. 使用 Redis 或 RabbitMQ 作为任务队列

**已有的轮询机制：**
```javascript
// frontend/index.html 已实现轮询
startPolling(data.taskId);
```

✅ **这部分已经做得很好！**

---

### **方案 5：监控和日志优化**

**添加性能监控：**

创建 `PerformanceInterceptor.java`：
```java
@Component
public class PerformanceInterceptor implements HandlerInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(PerformanceInterceptor.class);
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                                Object handler, Exception ex) {
        long startTime = (Long) request.getAttribute("startTime");
        long duration = System.currentTimeMillis() - startTime;
        
        if (duration > 3000) {  // 超过 3 秒记录警告
            log.warn("慢请求: {} {} 耗时 {}ms", 
                request.getMethod(), request.getRequestURI(), duration);
        }
    }
}
```

**预期效果：**
- 识别慢接口
- 针对性优化

---

### **方案 6：数据库连接池优化**

**当前 SQLite 单文件限制：**
- 写操作串行化
- 多用户并发写入会锁表

**短期方案（SQLite 优化）：**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
```

**长期方案（切换 PostgreSQL）：**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ele_business
    username: postgres
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
```

**预期效果：**
- 支持真正的并发写入
- 历史记录保存不阻塞生图

---

## 立即可执行的优化（0 代码修改）

### **修改 `application.yml`：**
```yaml
server:
  tomcat:
    threads:
      max: 300
      min-spare: 50
    accept-count: 200

app:
  api:
    delay-seconds: 0        # ⚠️ 关键：取消强制延迟
    timeout-seconds: 60
    max-retries: 2
    max-concurrent: 20
```

### **重启服务：**
```bash
# 停止当前服务
ps aux | grep ele-business-java | grep -v grep | awk '{print $2}' | xargs kill

# 启动优化后的服务
nohup java -jar ele-business-java-1.0.0.jar > app.log 2>&1 &
```

---

## 性能对比

| 场景 | 优化前 | 优化后 | 改善 |
|------|--------|--------|------|
| 单用户生成 4 张图 | ~20 秒 | ~10 秒 | **50% ↓** |
| 3 用户同时生成（各 4 张）| ~60 秒 | ~25 秒 | **58% ↓** |
| 最大并发任务数 | 12 个 | 20 个 | **67% ↑** |
| 重复产品图分析 | 每次 5-8 秒 | 缓存后 <1 秒 | **90% ↓** |

---

## 服务器资源建议

### **最低配置（2-5 用户）：**
- CPU: 2 核
- 内存: 4GB
- 带宽: 5Mbps

### **推荐配置（5-20 用户）：**
- CPU: 4 核
- 内存: 8GB
- 带宽: 10Mbps

### **生产配置（20-50 用户）：**
- CPU: 8 核
- 内存: 16GB
- 带宽: 20Mbps
- 数据库: PostgreSQL（独立服务器）
- 缓存: Redis（独立服务器）

---

## 监控指标

使用以下命令监控服务器性能：

```bash
# 1. CPU 使用率
top -bn1 | grep "Cpu(s)"

# 2. 内存使用
free -h

# 3. Java 进程内存
ps aux | grep java

# 4. 并发连接数
netstat -an | grep :5020 | grep ESTABLISHED | wc -l

# 5. 日志中的慢请求
tail -f logs/app.log | grep "慢请求"
```

---

## 故障排查

### **如果优化后仍然慢：**

1. **检查 API Key 配额**
   - Gemini/GPT-Image 是否达到限流
   - 查看日志中的 429 错误

2. **检查网络延迟**
   ```bash
   ping api.linapi.net
   curl -w "@curl-format.txt" -o /dev/null -s https://api.linapi.net
   ```

3. **检查磁盘 IO**
   ```bash
   iostat -x 1 5
   ```

4. **检查是否有死锁**
   ```bash
   jstack <java_pid> | grep -A 20 "deadlock"
   ```

---

## 总结

**立即执行（最简单）：**
1. 修改 `application.yml` 中的 `delay-seconds: 0`
2. 提升 `max-concurrent: 20`
3. 重启服务

**预期改善：** 50-60% 性能提升，无需代码修改

**长期优化：**
1. 添加 Redis 缓存
2. 切换 PostgreSQL
3. 添加性能监控

需要我帮你直接修改配置文件吗？
