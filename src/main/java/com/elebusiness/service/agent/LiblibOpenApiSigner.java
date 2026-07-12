package com.elebusiness.service.agent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Liblib 开放平台签名工具。
 */
final class LiblibOpenApiSigner {

    private LiblibOpenApiSigner() {
    }

    static String sign(String uri, String timestamp, String signatureNonce, String secretKey) {
        String content = uri + "&" + timestamp + "&" + signatureNonce;
        try {
            SecretKeySpec secret = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(secret);
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 HmacSHA1", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Liblib SecretKey 无效", e);
        }
    }

    static String signedUrl(String baseUrl, String uri, String accessKey, String secretKey,
                            String timestamp, String signatureNonce) {
        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String signature = sign(uri, timestamp, signatureNonce, secretKey);
        return cleanBaseUrl + uri
                + "?AccessKey=" + encode(accessKey)
                + "&Signature=" + encode(signature)
                + "&Timestamp=" + encode(timestamp)
                + "&SignatureNonce=" + encode(signatureNonce);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}