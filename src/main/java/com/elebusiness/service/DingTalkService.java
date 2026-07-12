package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.DingTalkRecord;
import com.elebusiness.model.ProductInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DingTalkService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkService.class);

    // AccessToken 缓存（钉钉 token 有效期 7200 秒，缓存 90 分钟）
    private static final long TOKEN_TTL_MS = 90 * 60 * 1000L;
    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    // getAllRecords 缓存（TTL 10 分钟）
    private static final long RECORDS_TTL_MS = 10 * 60 * 1000L;
    private volatile List<DingTalkRecord> cachedRecords;
    private volatile long recordsExpireAt;
    private volatile String cachedSheetId; // sheetId 变更时使缓存失效

    private final AppProperties appProperties;
    private final ConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final String DINGTALK_OLD_BASE = "https://oapi.dingtalk.com";
    private static final String DINGTALK_NEW_BASE = "https://api.dingtalk.com/v1.0";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    public DingTalkService(AppProperties appProperties, ConfigService configService) {
        this.appProperties = appProperties;
        this.configService = configService;
    }

    /** 配置变更后由 ApiController 主动调用，使 token 缓存失效 */
    public void invalidateCache() {
        cachedToken = null;
        tokenExpireAt = 0;
    }

    /**
     * 产品目录依赖钉钉多维表，先显式判断配置，避免把可预期的缺配置状态包装成 500。
     */
    public boolean isConfigured() {
        Map<String, String> config = configService.getDingTalkConfig();
        return hasText(config.get("app_key"))
                && hasText(config.get("app_secret"))
                && hasText(config.get("union_id"))
                && hasText(config.get("app_uuid"))
                && hasText(config.get("sheet_id"));
    }

    public String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAt) {
            return cachedToken;
        }
        Map<String, String> dt = configService.getDingTalkConfig();
        String url = DINGTALK_OLD_BASE + "/gettoken"
                + "?appkey=" + dt.get("app_key")
                + "&appsecret=" + dt.get("app_secret");

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            // OkHttp Response.body() 在异常路径上可能返回 null，直接 .string() 会 NPE（B 阶段审查 #6）
            okhttp3.ResponseBody rb = response.body();
            if (rb == null) throw new RuntimeException("获取钉钉 Token 失败: 响应为空");
            String body = rb.string();
            JsonNode node = objectMapper.readTree(body);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode != 0) {
                throw new RuntimeException("获取钉钉 Token 失败: " + node.path("errmsg").asText());
            }
            cachedToken = node.path("access_token").asText();
            tokenExpireAt = now + TOKEN_TTL_MS;
            return cachedToken;
        }
    }

    /** 直接从配置读取 union_id，不再需要额外接口换取 */
    public String getUnionId() {
        return configService.getDingTalkConfig().get("union_id");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public List<DingTalkRecord> getAllRecords() throws Exception {
        Map<String, String> dtCfg = configService.getDingTalkConfig();
        String sheetId = dtCfg.get("sheet_id");
        long now = System.currentTimeMillis();
        if (cachedRecords != null && now < recordsExpireAt && sheetId.equals(cachedSheetId)) {
            log.info("命中缓存，返回 {} 条产品记录", cachedRecords.size());
            return cachedRecords;
        }

        String accessToken = getAccessToken();
        log.info("获取 AccessToken 成功");

        String unionId = getUnionId();
        log.info("使用 unionId: {}", unionId);

        String appUuid = configService.getDingTalkConfig().get("app_uuid");
        List<DingTalkRecord> allRecords = new ArrayList<>();
        String nextToken = null;
        int pageCount = 0;

        do {
            StringBuilder urlBuilder = new StringBuilder(DINGTALK_NEW_BASE)
                    .append("/notable/bases/")
                    .append(appUuid)
                    .append("/sheets/")
                    .append(sheetId)
                    .append("/records")
                    .append("?operatorId=").append(unionId)
                    .append("&maxResults=100");

            if (nextToken != null) {
                urlBuilder.append("&nextToken=").append(nextToken);
            }

            Request request = new Request.Builder()
                    .url(urlBuilder.toString())
                    .addHeader("x-acs-dingtalk-access-token", accessToken)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body().string();
                JsonNode node = objectMapper.readTree(responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("获取记录失败 HTTP " + response.code() + ": " + responseBody);
                }

                JsonNode recordsNode = node.path("records");
                if (recordsNode.isArray()) {
                    for (JsonNode recordNode : recordsNode) {
                        DingTalkRecord record = parseRecord(recordNode);
                        // 只保留有 123 字段的记录，早期过滤避免内存溢出
                        if (record.getFields() != null && record.getFields().containsKey("123")) {
                            Object val = record.getFields().get("123");
                            if (val != null && !val.toString().isBlank()) {
                                allRecords.add(record);
                            }
                        }
                    }
                }

                // 用 hasMore 判断是否还有下一页（比 nextToken 更可靠）
                boolean hasMore = node.path("hasMore").asBoolean(false);
                if (!hasMore) break;

                JsonNode nextTokenNode = node.path("nextToken");
                nextToken = (nextTokenNode.isNull() || nextTokenNode.isMissingNode() || nextTokenNode.asText().isBlank())
                        ? null : nextTokenNode.asText();

                pageCount++;
                if (pageCount % 50 == 0) {
                    log.info("已扫描 {} 页，已找到 {} 条有效产品记录...", pageCount, allRecords.size());
                }
            }

        } while (nextToken != null);

        log.info("钉钉数据读取完成，共找到 {} 条有效产品记录", allRecords.size());

        // 写入缓存
        cachedRecords = allRecords;
        cachedSheetId = sheetId;
        recordsExpireAt = System.currentTimeMillis() + RECORDS_TTL_MS;

        return allRecords;
    }

    public DingTalkRecord getRecordById(String recordId) throws Exception {
        String accessToken = getAccessToken();
        String unionId = getUnionId();
        Map<String, String> dtCfg = configService.getDingTalkConfig();
        String appUuid = dtCfg.get("app_uuid");
        String sheetId = dtCfg.get("sheet_id");

        String url = DINGTALK_NEW_BASE
                + "/notable/bases/" + appUuid
                + "/sheets/" + sheetId
                + "/records/" + recordId
                + "?operatorId=" + unionId;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-acs-dingtalk-access-token", accessToken)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("获取记录失败 HTTP " + response.code() + ": " + responseBody);
            }
            return parseRecord(objectMapper.readTree(responseBody));
        }
    }

    @SuppressWarnings("unchecked")
    private DingTalkRecord parseRecord(JsonNode node) throws Exception {
        DingTalkRecord record = new DingTalkRecord();
        record.setId(node.path("id").asText());
        JsonNode fieldsNode = node.path("fields");
        Map<String, Object> fields = objectMapper.convertValue(fieldsNode, Map.class);
        record.setFields(fields);
        return record;
    }

    public ProductInfo parseProductInfo(DingTalkRecord record) {
        ProductInfo info = new ProductInfo();
        String description = record.getFieldAsString("123");
        String productName = record.getFieldAsString("标题");

        info.setName(productName);
        info.setHas123(!description.isBlank());

        Pattern categoryPattern = Pattern.compile("（(.+?)）");
        Matcher categoryMatcher = categoryPattern.matcher(productName);
        if (categoryMatcher.find()) {
            info.setCategory(categoryMatcher.group(1));
        }

        if (!description.isBlank()) {
            Pattern mainPattern = Pattern.compile("主图：\\s*(.+?)(?=\\n\\nSKU:|$)", Pattern.DOTALL);
            Matcher mainMatcher = mainPattern.matcher(description);
            if (mainMatcher.find()) {
                for (String line : mainMatcher.group(1).trim().split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) info.getMain().add(trimmed);
                }
            }

            Pattern skuPattern = Pattern.compile("SKU:\\s*(.+)", Pattern.DOTALL);
            Matcher skuMatcher = skuPattern.matcher(description);
            if (skuMatcher.find()) {
                for (String line : skuMatcher.group(1).trim().split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) info.getSku().add(trimmed);
                }
            }
        }

        return info;
    }
}
