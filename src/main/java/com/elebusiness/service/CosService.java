package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 腾讯云 COS 上传服务。
 * COS 未配置时（secretId/secretKey 为空）isEnabled() 返回 false，调用方降级到本地存储。
 */
@Service
public class CosService {

    private static final Logger log = LoggerFactory.getLogger(CosService.class);

    private final AppProperties appProperties;
    private volatile COSClient client;

    public CosService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public boolean isEnabled() {
        return appProperties.getCos().isEnabled();
    }

    /**
     * 上传文件到 COS，返回公开访问 URL。
     * key 格式：generated/{yyyyMMdd}/{filename}
     */
    public String upload(File file, String filename) {
        String bucket = appProperties.getCos().getBucket();
        String region = appProperties.getCos().getRegion();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = "generated/" + date + "/" + filename;

        try {
            COSClient cos = getClient();
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(file.length());
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) meta.setContentType("image/png");
            else if (lower.endsWith(".mp4")) meta.setContentType("video/mp4");
            else meta.setContentType("image/jpeg");

            cos.putObject(new PutObjectRequest(bucket, key, file).withMetadata(meta));
            // COS 公开读 URL 格式
            String url = "https://" + bucket + ".cos." + region + ".myqcloud.com/" + key;
            log.info("COS upload: {} -> {}", file.getName(), url);
            return url;
        } catch (Exception e) {
            log.error("COS upload failed: {}", e.getMessage(), e);
            throw new RuntimeException("COS 上传失败: " + e.getMessage(), e);
        }
    }

    private COSClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    AppProperties.Cos cfg = appProperties.getCos();
                    client = new COSClient(
                        new BasicCOSCredentials(cfg.getSecretId(), cfg.getSecretKey()),
                        new ClientConfig(new Region(cfg.getRegion()))
                    );
                }
            }
        }
        return client;
    }
}
