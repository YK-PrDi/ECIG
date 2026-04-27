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

    // unionId 不会变，永久缓存
    private volatile String cachedUnionId;

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

    public String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAt) {
            return cachedToken;
        }
        AppProperties.DingTalk dt = appProperties.getDingtalk();
        String url = DINGTALK_OLD_BASE + "/gettoken"
                + "?appkey=" + dt.getAppKey()
                + "&appsecret=" + dt.getAppSecret();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body().string();
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

    public String getUnionId(String accessToken) throws Exception {
        if (cachedUnionId != null) {
            return cachedUnionId;
        }
        String url = DINGTALK_OLD_BASE + "/topapi/v2/user/get?access_token=" + accessToken;
        String jsonBody = objectMapper.writeValueAsString(
                Map.of("userid", appProperties.getDingtalk().getUserId())
        );
        RequestBody body = RequestBody.create(jsonBody, JSON_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            JsonNode node = objectMapper.readTree(responseBody);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode != 0) {
                throw new RuntimeException("获取用户 unionId 失败: " + node.path("errmsg").asText());
            }
            cachedUnionId = node.path("result").path("unionid").asText();
            return cachedUnionId;
        }
    }

    public List<DingTalkRecord> getAllRecords() throws Exception {
        String sheetId = configService.getCurrentSheetId();
        long now = System.currentTimeMillis();
        if (cachedRecords != null && now < recordsExpireAt && sheetId.equals(cachedSheetId)) {
            log.info("命中缓存，返回 {} 条产品记录", cachedRecords.size());
            return cachedRecords;
        }

        String accessToken = getAccessToken();
        log.info("获取 AccessToken 成功");

        String unionId = getUnionId(accessToken);
        log.info("获取 unionId 成功: {}", unionId);

        String appUuid = appProperties.getDingtalk().getAppUuid();
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
        String unionId = getUnionId(accessToken);
        String appUuid = appProperties.getDingtalk().getAppUuid();
        String sheetId = configService.getCurrentSheetId();

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
