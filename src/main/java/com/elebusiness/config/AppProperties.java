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
    public Paths getPaths() { return paths; }
    public void setPaths(Paths paths) { this.paths = paths; }
    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }
    public Proxy getProxy() { return proxy; }
    public void setProxy(Proxy proxy) { this.proxy = proxy; }

    public static class Gemini {
        private String apiKey;
        private String model = "gemini-2.0-flash-preview-image-generation";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
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
    }

    public static class Api {
        private int delaySeconds = 2;
        private int timeoutSeconds = 75;
        private int maxRetries = 2;
        private int maxConcurrent = 6;

        public int getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(int delaySeconds) { this.delaySeconds = delaySeconds; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
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

        public java.util.List<String> getApiKeys() { return apiKeys; }
        public void setApiKeys(java.util.List<String> apiKeys) { this.apiKeys = apiKeys; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
