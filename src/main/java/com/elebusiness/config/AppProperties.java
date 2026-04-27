package com.elebusiness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Gemini gemini = new Gemini();
    private DingTalk dingtalk = new DingTalk();
    private Paths paths = new Paths();
    private Api api = new Api();
    private Proxy proxy = new Proxy();

    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }
    public DingTalk getDingtalk() { return dingtalk; }
    public void setDingtalk(DingTalk dingtalk) { this.dingtalk = dingtalk; }
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

    public static class DingTalk {
        private String appKey;
        private String appSecret;
        private String userId;
        private String appUuid;
        private String sheetId;

        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getAppUuid() { return appUuid; }
        public void setAppUuid(String appUuid) { this.appUuid = appUuid; }
        public String getSheetId() { return sheetId; }
        public void setSheetId(String sheetId) { this.sheetId = sheetId; }
    }

    public static class Paths {
        private String referenceDir = "./大参考";
        private String outputDir = "./生成结果";
        private String configFile = "./config.json";

        public String getReferenceDir() { return referenceDir; }
        public void setReferenceDir(String referenceDir) { this.referenceDir = referenceDir; }
        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
        public String getConfigFile() { return configFile; }
        public void setConfigFile(String configFile) { this.configFile = configFile; }
    }

    public static class Api {
        private int delaySeconds = 10;
        private int timeoutSeconds = 90;
        private int maxRetries = 3;

        public int getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(int delaySeconds) { this.delaySeconds = delaySeconds; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Proxy {
        /** 代理主机，留空则不使用代理 */
        private String host = "";
        private int port = 7890;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public boolean isEnabled() { return host != null && !host.isBlank(); }
    }
}
