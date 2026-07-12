package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

@Service
public class GenerationPricingService {

    private final AppProperties appProperties;

    public GenerationPricingService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public int estimateImageGeneration(String mode, String agentId, int imageCount) {
        AppProperties.Billing billing = billing();
        if (!billing.isChargeEnabled() || imageCount <= 0) {
            return 0;
        }
        int unitPoints = resolvePoints(billing.getImageAgentPoints(), agentId, billing.getDefaultImagePoints());
        return multiplySafely(imageCount, unitPoints);
    }

    public int estimateVideoGeneration(String model, int durationSeconds) {
        AppProperties.Billing billing = billing();
        if (!billing.isChargeEnabled()) {
            return 0;
        }
        int safeDuration = Math.max(1, durationSeconds);
        int basePoints = resolvePoints(billing.getVideoModelBasePoints(), model, billing.getDefaultVideoBasePoints());
        int secondPoints = Math.max(0, billing.getDefaultVideoSecondPoints());
        return basePoints + multiplySafely(safeDuration, secondPoints);
    }

    private AppProperties.Billing billing() {
        AppProperties.Billing billing = appProperties.getBilling();
        return billing == null ? new AppProperties.Billing() : billing;
    }

    private int resolvePoints(Map<String, Integer> overrides, String key, int fallback) {
        int safeFallback = Math.max(0, fallback);
        if (overrides == null || overrides.isEmpty() || key == null || key.isBlank()) {
            return safeFallback;
        }
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            String configuredKey = entry.getKey();
            Integer configuredPoints = entry.getValue();
            if (configuredKey == null || configuredKey.isBlank() || configuredPoints == null) {
                continue;
            }
            String normalizedConfiguredKey = configuredKey.toLowerCase(Locale.ROOT);
            if (normalizedKey.equals(normalizedConfiguredKey) || normalizedKey.startsWith(normalizedConfiguredKey)) {
                return Math.max(0, configuredPoints);
            }
        }
        return safeFallback;
    }

    private int multiplySafely(int left, int right) {
        long value = Math.max(0L, (long) left) * Math.max(0L, (long) right);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
