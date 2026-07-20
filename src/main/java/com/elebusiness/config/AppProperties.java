package com.elebusiness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Gemini gemini = new Gemini();
    private Veo veo = new Veo();
    private Volcengine volcengine = new Volcengine();
    private DingTalk dingtalk = new DingTalk();
    private GptImage gptImage = new GptImage();
    private Paths paths = new Paths();
    private Api api = new Api();
    private Proxy proxy = new Proxy();
    private Cos cos = new Cos();
    private Auth auth = new Auth();
    private Qwen qwen = new Qwen();
    private Billing billing = new Billing();
    private SuiXiangVideo suiXiangVideo = new SuiXiangVideo();

    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }
    public Veo getVeo() { return veo; }
    public void setVeo(Veo veo) { this.veo = veo; }
    public Volcengine getVolcengine() { return volcengine; }
    public void setVolcengine(Volcengine volcengine) { this.volcengine = volcengine; }
    public DingTalk getDingtalk() { return dingtalk; }
    public void setDingtalk(DingTalk dingtalk) { this.dingtalk = dingtalk; }
    public GptImage getGptImage() { return gptImage; }
    public void setGptImage(GptImage gptImage) { this.gptImage = gptImage; }
    public Cos getCos() { return cos; }
    public void setCos(Cos cos) { this.cos = cos; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public Paths getPaths() { return paths; }
    public void setPaths(Paths paths) { this.paths = paths; }
    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }
    public Proxy getProxy() { return proxy; }
    public void setProxy(Proxy proxy) { this.proxy = proxy; }
    public Qwen getQwen() { return qwen; }
    public void setQwen(Qwen qwen) { this.qwen = qwen; }
    public Billing getBilling() { return billing; }
    public void setBilling(Billing billing) { this.billing = billing; }
    public SuiXiangVideo getSuiXiangVideo() { return suiXiangVideo; }
    public void setSuiXiangVideo(SuiXiangVideo suiXiangVideo) { this.suiXiangVideo = suiXiangVideo; }

    public static class Gemini {
        private String apiKey;
        private String model = "gemini-2.0-flash-preview-image-generation";
        /** 文本分析端点（OpenAI 兼容代理），用于 /v1/chat/completions */
        private String baseUrl = "https://api.linapi.net";
        /** 图像生成端点（Google native），用于 {model}:generateContent */
        private String imageBaseUrl = "https://generativelanguage.googleapis.com/v1beta/models/";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getImageBaseUrl() { return imageBaseUrl; }
        public void setImageBaseUrl(String imageBaseUrl) { this.imageBaseUrl = imageBaseUrl; }
    }

    public static class Veo {
        private String model = "veo-3.1-generate-preview";
        private int durationSeconds = 8;
        private String resolution = "720p";
        private boolean generateAudio = true;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
        public boolean isGenerateAudio() { return generateAudio; }
        public void setGenerateAudio(boolean generateAudio) { this.generateAudio = generateAudio; }
    }

    public static class Volcengine {
        private String apiKey;
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
        private String model = "doubao-seedance-2-0-260128";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class DingTalk {
        private String appKey;
        private String appSecret;
        private String appUuid;
        private String sheetId;

        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getAppUuid() { return appUuid; }
        public void setAppUuid(String appUuid) { this.appUuid = appUuid; }
        public String getSheetId() { return sheetId; }
        public void setSheetId(String sheetId) { this.sheetId = sheetId; }
    }

    public static class Paths {
        private String referenceDir = "./大参考";
        private String outputDir = "./生成结果";
        private String tempOutputDir = "./.temp-output";
        // Phase 2：历史记录用的参考图归档目录。生图时把 ref 图复制一份到这里，DB 存路径，
        // 用户在历史 UI 点"重生"时能找到原参考图；只在用户手动删除历史条目时才清理。
        // 不参与 .temp-output 的 2h TTL 清理。
        private String historyRefsDir = "./.history-refs";
        private String configFile = "./config.json";
        private String promptsDir = "./prompts";
        // 打包态由 electron 经 -Dapp.paths.user-data-dir=... 注入；源码态留空则 fallback "./"
        // 导入工具写到 ${userDataDir}/data/categories/，前端按 file:${userDataDir}/ 优先 serve
        private String userDataDir = "./";

        public String getReferenceDir() { return referenceDir; }
        public void setReferenceDir(String referenceDir) { this.referenceDir = referenceDir; }
        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
        public String getTempOutputDir() { return tempOutputDir; }
        public void setTempOutputDir(String tempOutputDir) { this.tempOutputDir = tempOutputDir; }
        public String getHistoryRefsDir() { return historyRefsDir; }
        public void setHistoryRefsDir(String historyRefsDir) { this.historyRefsDir = historyRefsDir; }
        public String getConfigFile() { return configFile; }
        public void setConfigFile(String configFile) { this.configFile = configFile; }
        public String getPromptsDir() { return promptsDir; }
        public void setPromptsDir(String promptsDir) { this.promptsDir = promptsDir; }
        public String getUserDataDir() { return userDataDir; }
        public void setUserDataDir(String userDataDir) { this.userDataDir = userDataDir; }
    }

    public static class Api {
        private int delaySeconds = 2;
        private int timeoutSeconds = 75;
        private int maxRetries = 2;
        private int maxConcurrent = 6;
        private int userMaxConcurrentTasks = 3;
        private int adminMaxConcurrentTasks = 10;

        public int getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(int delaySeconds) { this.delaySeconds = delaySeconds; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getUserMaxConcurrentTasks() { return userMaxConcurrentTasks; }
        public void setUserMaxConcurrentTasks(int userMaxConcurrentTasks) {
            this.userMaxConcurrentTasks = userMaxConcurrentTasks;
        }
        public int getAdminMaxConcurrentTasks() { return adminMaxConcurrentTasks; }
        public void setAdminMaxConcurrentTasks(int adminMaxConcurrentTasks) {
            this.adminMaxConcurrentTasks = adminMaxConcurrentTasks;
        }
    }

    public static class SuiXiangVideo {
        private String baseUrl = "https://sui-xiang.com/v1";
        private ProviderCredential grok = new ProviderCredential();
        private ProviderCredential jimeng = new ProviderCredential();
        private MediaProxy mediaProxy = new MediaProxy();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public ProviderCredential getGrok() { return grok; }
        public void setGrok(ProviderCredential grok) { this.grok = grok; }
        public ProviderCredential getJimeng() { return jimeng; }
        public void setJimeng(ProviderCredential jimeng) { this.jimeng = jimeng; }
        public MediaProxy getMediaProxy() { return mediaProxy; }
        public void setMediaProxy(MediaProxy mediaProxy) {
            this.mediaProxy = mediaProxy == null ? new MediaProxy() : mediaProxy;
        }
    }

    public static class MediaProxy {
        private boolean enabled;
        private String type = "socks5";
        private String host = "127.0.0.1";
        private int port = 40000;
        private java.util.List<String> hosts = new java.util.ArrayList<>(java.util.List.of("vidgen.x.ai"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type == null ? "" : type.trim(); }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host == null ? "" : host.trim(); }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public java.util.List<String> getHosts() { return hosts; }
        public void setHosts(java.util.List<String> hosts) {
            this.hosts = hosts == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(hosts);
        }
    }

    public static class ProviderCredential {
        private String apiKey = "";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    public static class Proxy {
        /** 代理主机，留空则跟随系统代理；填写则强制使用指定代理 */
        private String host = "";
        private int port = 8086;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public boolean isEnabled() { return host != null && !host.isBlank(); }
    }

    public static class GptImage {
        private java.util.List<String> apiKeys = new java.util.ArrayList<>();
        private String baseUrl = "https://api.linapi.net";
        private java.util.Map<String, String> keyBaseUrls = new java.util.LinkedHashMap<>();

        public java.util.List<String> getApiKeys() { return apiKeys; }
        public void setApiKeys(java.util.List<String> apiKeys) { this.apiKeys = apiKeys; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public java.util.Map<String, String> getKeyBaseUrls() { return keyBaseUrls; }
        public void setKeyBaseUrls(java.util.Map<String, String> keyBaseUrls) { this.keyBaseUrls = keyBaseUrls; }
    }

    public static class Cos {
        private String secretId = "";
        private String secretKey = "";
        private String region = "ap-guangzhou";
        private String bucket = "graduation-project-1416091844";

        public String getSecretId() { return secretId; }
        public void setSecretId(String secretId) { this.secretId = secretId; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }

        public boolean isEnabled() { return secretId != null && !secretId.isBlank()
                                         && secretKey != null && !secretKey.isBlank(); }
    }

    public static class Auth {
        private String username = "admin";
        private String password = "123456";
        private String displayName = "Admin 用户";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    public static class Qwen {
        private String apiKey = "";
        private String model = "qwen-vl-max";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public boolean isEnabled() { return apiKey != null && !apiKey.isBlank(); }
    }

    public static class Billing {
        private boolean chargeEnabled = false;
        private int defaultImagePoints = 10;
        private int defaultVideoBasePoints = 80;
        private int defaultVideoSecondPoints = 5;
        private String callbackToken = "";
        private String credentialSecret = "";
        private int exportMaxRows = 10000;
        private boolean reconciliationScheduleEnabled = false;
        private String reconciliationCron = "0 0 3 * * *";
        private String reconciliationScheduleMode = "GLOBAL";
        private int reconciliationUserBatchSize = 100;
        private int reconciliationLeaseSeconds = 900;
        private java.util.Map<String, Integer> imageAgentPoints = new java.util.LinkedHashMap<>();
        private java.util.Map<String, Integer> videoModelBasePoints = new java.util.LinkedHashMap<>();

        public boolean isChargeEnabled() { return chargeEnabled; }
        public void setChargeEnabled(boolean chargeEnabled) { this.chargeEnabled = chargeEnabled; }
        public int getDefaultImagePoints() { return defaultImagePoints; }
        public void setDefaultImagePoints(int defaultImagePoints) { this.defaultImagePoints = defaultImagePoints; }
        public int getDefaultVideoBasePoints() { return defaultVideoBasePoints; }
        public void setDefaultVideoBasePoints(int defaultVideoBasePoints) { this.defaultVideoBasePoints = defaultVideoBasePoints; }
        public int getDefaultVideoSecondPoints() { return defaultVideoSecondPoints; }
        public void setDefaultVideoSecondPoints(int defaultVideoSecondPoints) { this.defaultVideoSecondPoints = defaultVideoSecondPoints; }
        public String getCallbackToken() { return callbackToken; }
        public void setCallbackToken(String callbackToken) { this.callbackToken = callbackToken; }
        public String getCredentialSecret() { return credentialSecret; }
        public void setCredentialSecret(String credentialSecret) { this.credentialSecret = credentialSecret; }
        public int getExportMaxRows() { return exportMaxRows; }
        public void setExportMaxRows(int exportMaxRows) { this.exportMaxRows = exportMaxRows; }
        public boolean isReconciliationScheduleEnabled() { return reconciliationScheduleEnabled; }
        public void setReconciliationScheduleEnabled(boolean reconciliationScheduleEnabled) { this.reconciliationScheduleEnabled = reconciliationScheduleEnabled; }
        public String getReconciliationCron() { return reconciliationCron; }
        public void setReconciliationCron(String reconciliationCron) { this.reconciliationCron = reconciliationCron; }
        public String getReconciliationScheduleMode() { return reconciliationScheduleMode; }
        public void setReconciliationScheduleMode(String reconciliationScheduleMode) { this.reconciliationScheduleMode = reconciliationScheduleMode; }
        public int getReconciliationUserBatchSize() { return reconciliationUserBatchSize; }
        public void setReconciliationUserBatchSize(int reconciliationUserBatchSize) { this.reconciliationUserBatchSize = reconciliationUserBatchSize; }
        public int getReconciliationLeaseSeconds() { return reconciliationLeaseSeconds; }
        public void setReconciliationLeaseSeconds(int reconciliationLeaseSeconds) { this.reconciliationLeaseSeconds = reconciliationLeaseSeconds; }
        public java.util.Map<String, Integer> getImageAgentPoints() { return imageAgentPoints; }
        public void setImageAgentPoints(java.util.Map<String, Integer> imageAgentPoints) { this.imageAgentPoints = imageAgentPoints; }
        public java.util.Map<String, Integer> getVideoModelBasePoints() { return videoModelBasePoints; }
        public void setVideoModelBasePoints(java.util.Map<String, Integer> videoModelBasePoints) { this.videoModelBasePoints = videoModelBasePoints; }
    }
}
