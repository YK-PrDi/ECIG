package com.elebusiness.service.agent;

import com.elebusiness.service.CosService;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Liblib Star-3 图生图要求 sourceImage 是公网可访问 URL。
 */
final class LiblibSourceImageResolver {

    private LiblibSourceImageResolver() {
    }

    static String resolve(String imagePath, CosService cosService) throws IOException {
        if (imagePath == null || imagePath.isBlank()) {
            return "";
        }
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            return imagePath;
        }

        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            throw new IOException("Liblib 参考图不存在: " + imagePath);
        }
        if (cosService == null || !cosService.isEnabled()) {
            throw new IOException("Liblib 图生图要求 sourceImage 为公网 URL；当前参考图是本地文件，且 COS 未启用，无法上传参考图");
        }

        return cosService.upload(imageFile, "liblib-source-" + UUID.randomUUID() + extensionOf(imageFile.getName()));
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".png";
        }
        String ext = filename.substring(dot).toLowerCase();
        if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || ext.equals(".webp")) {
            return ext;
        }
        return ".png";
    }
}