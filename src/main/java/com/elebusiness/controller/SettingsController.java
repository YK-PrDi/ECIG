package com.elebusiness.controller;

import com.elebusiness.service.ConfigService;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.UserPrefsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一设置入口：聚合钉钉同步配置 + 代理配置 + customOutputDir。
 * 替代分散的 openSyncModal / openProxyModal —— 前端用 ⚙️ 设置 一个 modal 同时编辑。
 *
 * GET  /api/settings  返回所有配置（含 customOutputDir 当前值）
 * POST /api/settings  接收所有字段，路径不可写时返 400 给前端即时反馈（A3）
 *
 * 路径校验失败不阻断钉钉/代理保存 —— 用户可能就想清掉自定义路径，让 saveToGallery 走默认。
 */
@RestController
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final ConfigService configService;
    private final DingTalkService dingTalkService;
    private final UserPrefsService userPrefsService;

    public SettingsController(ConfigService configService,
                              DingTalkService dingTalkService,
                              UserPrefsService userPrefsService) {
        this.configService = configService;
        this.dingTalkService = dingTalkService;
        this.userPrefsService = userPrefsService;
    }

    @GetMapping("/api/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> result = new HashMap<>();
        result.put("dingtalk", configService.getDingTalkConfig());
        result.put("proxy", configService.getProxyConfig());
        result.put("customOutputDir", userPrefsService.getCustomOutputDir());
        return result;
    }

    /**
     * 一次性 POST 所有字段。body 形如：
     * { "dingtalk": {app_key, app_secret, app_token, table_id}, "proxy": {proxy_host, proxy_port}, "customOutputDir": "..." }
     * 任一字段缺失视为不更新该类配置。
     */
    @PostMapping("/api/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "请求体为空"));
        }

        // 1) 文件保存位置 — 先校验，失败立刻返 400 (A3)，不污染钉钉/代理
        if (body.containsKey("customOutputDir")) {
            Object v = body.get("customOutputDir");
            String dir = v == null ? "" : String.valueOf(v).trim();
            String err = userPrefsService.validateDir(dir);
            if (err != null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", err));
            }
            userPrefsService.setCustomOutputDir(dir);
        }

        // 2) 钉钉
        if (body.get("dingtalk") instanceof Map<?, ?> dt) {
            Map<String, String> dtMap = new HashMap<>();
            for (Map.Entry<?, ?> e : dt.entrySet()) {
                if (e.getValue() != null) dtMap.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            if (!dtMap.isEmpty()) {
                configService.saveDingTalkConfig(dtMap);
                dingTalkService.invalidateCache();
            }
        }

        // 3) 代理
        if (body.get("proxy") instanceof Map<?, ?> px) {
            Object hostObj = px.get("proxy_host");
            String host = hostObj == null ? "" : String.valueOf(hostObj).trim();
            int port = 7890;
            try {
                Object pObj = px.get("proxy_port");
                if (pObj != null) port = Integer.parseInt(String.valueOf(pObj));
            } catch (NumberFormatException e) {
                log.warn("proxy_port 非数字，回退默认 7890: {}", px.get("proxy_port"));
            }
            configService.saveProxyConfig(host, port);
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 浏览器态目录选择 fallback：列出指定目录下的子目录（不返文件，文件不能选）。
     * path 留空 = 列盘符（Windows）/ 根（POSIX）
     * 返回：{ path: 当前绝对路径, parent: 上级路径或 null, items: [{name, path}] }
     */
    @GetMapping("/api/settings/browse-dir")
    public ResponseEntity<Map<String, Object>> browseDir(@RequestParam(required = false) String path) {
        try {
            List<Map<String, String>> items = new ArrayList<>();
            String currentPath;
            String parent;

            if (path == null || path.isBlank()) {
                // 顶层：Windows 给所有可读盘符；POSIX 给 /
                currentPath = "";
                parent = null;
                File[] roots = File.listRoots();
                if (roots != null) {
                    for (File r : roots) {
                        if (!r.exists()) continue;
                        Map<String, String> it = new LinkedHashMap<>();
                        it.put("name", r.getAbsolutePath());
                        it.put("path", r.getAbsolutePath());
                        items.add(it);
                    }
                }
            } else {
                File dir = new java.io.File(path).getAbsoluteFile();
                if (!dir.exists() || !dir.isDirectory()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "目录不存在或不可访问"));
                }
                currentPath = dir.getAbsolutePath();
                File p = dir.getParentFile();
                parent = (p == null) ? "" : p.getAbsolutePath(); // "" 表示返回到盘符层
                File[] kids = dir.listFiles();
                if (kids != null) {
                    Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File k : kids) {
                        if (!k.isDirectory() || k.isHidden() || k.getName().startsWith(".")) continue;
                        if (!k.canRead()) continue;
                        Map<String, String> it = new LinkedHashMap<>();
                        it.put("name", k.getName());
                        it.put("path", k.getAbsolutePath());
                        items.add(it);
                    }
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("path", currentPath);
            resp.put("parent", parent);
            resp.put("items", items);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("browseDir 失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
