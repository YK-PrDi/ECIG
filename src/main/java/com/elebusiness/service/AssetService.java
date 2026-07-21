package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.CompanyAsset;
import com.elebusiness.repository.CompanyAssetRepository;
import com.elebusiness.service.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 企业资产库：把生成结果复制/转存到企业共享目录，并登记元数据。
 * 目录：{userDataDir}/company-assets/
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024; // 200MB（视频）

    private final CompanyAssetRepository assetRepository;
    private final AppProperties appProperties;
    private final com.elebusiness.service.workspace.UserStorageService userStorageService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AssetService(CompanyAssetRepository assetRepository, AppProperties appProperties,
                        com.elebusiness.service.workspace.UserStorageService userStorageService) {
        this.assetRepository = assetRepository;
        this.appProperties = appProperties;
        this.userStorageService = userStorageService;
    }

    public Path companyAssetsRoot() {
        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) userDataDir = ".";
        return Paths.get(userDataDir).toAbsolutePath().normalize()
                .resolve("company-assets").normalize();
    }

    public Page<CompanyAsset> list(Long enterpriseId, String type, int page, int size) {
        if (enterpriseId == null) {
            return Page.empty();
        }
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (type != null && !type.isBlank()) {
            return assetRepository.findByEnterpriseIdAndTypeOrderByCreatedAtDesc(enterpriseId, type.trim(), pr);
        }
        return assetRepository.findByEnterpriseIdOrderByCreatedAtDesc(enterpriseId, pr);
    }

    /**
     * 入库：sourcePath 可以是 http(s) URL 或服务器本地绝对路径。
     */
    @Transactional
    public CompanyAsset publish(AuthService.AuthUser user, String sourcePath,
                                String title, String type, String sourceMode) throws IOException {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("缺少来源文件路径");
        }
        if (user.enterpriseId() == null) {
            throw new SecurityException("当前账号没有企业空间，不能入库");
        }
        String assetType = "video".equalsIgnoreCase(type) ? "video" : "image";
        Path root = companyAssetsRoot();
        Files.createDirectories(root);

        String ext = guessExtension(sourcePath, assetType);
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法目标路径");
        }

        if (sourcePath.startsWith("http://") || sourcePath.startsWith("https://")) {
            downloadTo(sourcePath, target);
        } else {
            Path source = Paths.get(sourcePath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("来源文件不存在或已过期（临时产出 2 小时后会被清理）");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return saveAsset(user, sourcePath, title, assetType, sourceMode, target);
    }

    /**
     * 视频入库：按文件名从该用户的 视频/ 产出目录解析（与 VideoController.file 同一查找逻辑）。
     */
    @Transactional
    public CompanyAsset publishVideo(AuthService.AuthUser user, String videoFilename,
                                     String title, String sourceMode) throws IOException {
        if (videoFilename == null || videoFilename.isBlank()
                || videoFilename.contains("..") || videoFilename.contains("/") || videoFilename.contains("\\")) {
            throw new IllegalArgumentException("非法视频文件名");
        }
        if (user.enterpriseId() == null) {
            throw new SecurityException("当前账号没有企业空间，不能入库");
        }
        Path source = userStorageService.tempOutputRoot(user.id()).resolve("视频").resolve(videoFilename);
        if (!Files.isRegularFile(source)) {
            source = userStorageService.galleryRoot(user.id()).resolve("视频").resolve(videoFilename);
        }
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("视频文件不存在或已过期");
        }
        Path root = companyAssetsRoot();
        Files.createDirectories(root);
        Path target = root.resolve(UUID.randomUUID().toString().replace("-", "") + ".mp4").normalize();
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return saveAsset(user, videoFilename, title, "video", sourceMode, target);
    }

    private CompanyAsset saveAsset(AuthService.AuthUser user, String sourceName, String title,
                                   String assetType, String sourceMode, Path target) {
        CompanyAsset asset = new CompanyAsset();
        asset.setUploaderId(user.id());
        asset.setUploaderName(user.displayName() == null || user.displayName().isBlank()
                ? user.username() : user.displayName());
        asset.setEnterpriseId(user.enterpriseId());
        asset.setType(assetType);
        asset.setTitle(title == null || title.isBlank() ? null : title.trim());
        asset.setSourceMode(sourceMode == null || sourceMode.isBlank() ? null : sourceMode.trim());
        asset.setStoragePath(target.toString());
        asset.setOriginalName(sourceName.substring(sourceName.replace('\\', '/').lastIndexOf('/') + 1));
        CompanyAsset saved = assetRepository.save(asset);
        log.info("企业资产入库: id={}, uploader={}, enterprise={}, type={}, path={}",
                saved.getId(), user.username(), user.enterpriseId(), assetType, target);
        return saved;
    }

    /**
     * 删除：上传者本人，或同企业的负责人。平台中控也不得越权。
     */
    @Transactional
    public void delete(long id, AuthService.AuthUser user) {
        CompanyAsset asset = assetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("资产不存在"));
        if (user.enterpriseId() == null || !user.enterpriseId().equals(asset.getEnterpriseId())) {
            throw new SecurityException("无权访问其他企业的资产");
        }
        boolean isOwner = asset.getUploaderId() != null && asset.getUploaderId() == user.id();
        boolean isEnterpriseAdmin = user.isAdmin();
        if (!isOwner && !isEnterpriseAdmin) {
            throw new SecurityException("只有上传者或企业负责人可以删除");
        }
        assetRepository.delete(asset);
        try {
            Path path = Paths.get(asset.getStoragePath()).toAbsolutePath().normalize();
            if (path.startsWith(companyAssetsRoot())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("企业资产文件删除失败: {}", asset.getStoragePath(), e);
        }
    }

    /** 取文件（同企业校验，中控也不可见），供控制器流式返回。 */
    public Path fileOf(long id, Long enterpriseId) {
        if (enterpriseId == null) return null;
        CompanyAsset asset = assetRepository.findById(id).orElse(null);
        if (asset == null || !enterpriseId.equals(asset.getEnterpriseId())) return null;
        Path path = Paths.get(asset.getStoragePath()).toAbsolutePath().normalize();
        if (!path.startsWith(companyAssetsRoot()) || !Files.isRegularFile(path)) return null;
        return path;
    }

    public String originalNameOf(long id) {
        CompanyAsset asset = assetRepository.findById(id).orElse(null);
        return asset == null ? null : asset.getOriginalName();
    }

    private void downloadTo(String url, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("下载来源文件失败（HTTP " + response.statusCode() + "）");
            }
            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(target) > MAX_FILE_BYTES) {
                Files.deleteIfExists(target);
                throw new IOException("文件超过大小限制");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
    }

    private String guessExtension(String sourcePath, String type) {
        String lower = sourcePath.toLowerCase(Locale.ROOT);
        String clean = lower.split("[?#]")[0];
        if (clean.endsWith(".png")) return ".png";
        if (clean.endsWith(".webp")) return ".webp";
        if (clean.endsWith(".gif")) return ".gif";
        if (clean.endsWith(".mp4")) return ".mp4";
        if (clean.endsWith(".webm")) return ".webm";
        if (clean.endsWith(".jpg") || clean.endsWith(".jpeg")) return ".jpg";
        return "video".equals(type) ? ".mp4" : ".jpg";
    }
}
