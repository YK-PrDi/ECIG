package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VideoModelCatalog {

    private static final List<ModelDefinition> DEFINITIONS = List.of(
            new ModelDefinition("veo-3.1-generate-preview", "Veo 3.1", "google", "Google", Provider.VEO, 0, InputMode.FLEXIBLE),
            new ModelDefinition("doubao-seedance-2-0-260128", "Seedance 2.0", "seedance", "火山方舟", Provider.SEEDANCE, 0, InputMode.FLEXIBLE),
            new ModelDefinition("grok-imagine-video", "Grok 文生视频", "suixiang-grok-text", "Grok 文生视频", Provider.SUIXIANG_GROK, 0, InputMode.TEXT_ONLY),
            new ModelDefinition("grok-imagine-video-1.5", "Grok 图生视频", "suixiang-grok-image", "Grok 图生视频", Provider.SUIXIANG_GROK, 0, InputMode.IMAGE_ONLY),
            new ModelDefinition("as-sd2.0-fast", "即梦 SD 2.0 Fast", "suixiang-jimeng", "即梦", Provider.SUIXIANG_JIMENG, 0, InputMode.FLEXIBLE),
            new ModelDefinition("video-ds-2.0", "即梦 Video DS 2.0", "suixiang-jimeng", "即梦", Provider.SUIXIANG_JIMENG, 1, InputMode.FLEXIBLE)
    );

    private final AppProperties properties;

    public VideoModelCatalog(AppProperties properties) {
        this.properties = properties;
    }

    public List<ModelView> models() {
        return DEFINITIONS.stream().map(this::toView).toList();
    }

    public ModelView require(String modelId) {
        return DEFINITIONS.stream()
                .filter(model -> model.id().equals(modelId))
                .findFirst()
                .map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("不支持的视频模型: " + modelId));
    }

    public AppProperties.ProviderCredential credentialFor(ModelView model) {
        return switch (model.provider()) {
            case SUIXIANG_GROK -> properties.getSuiXiangVideo().getGrok();
            case SUIXIANG_JIMENG -> properties.getSuiXiangVideo().getJimeng();
            default -> new AppProperties.ProviderCredential();
        };
    }

    private ModelView toView(ModelDefinition definition) {
        return new ModelView(
                definition.id(),
                definition.name(),
                definition.providerId(),
                definition.providerLabel(),
                definition.provider(),
                definition.level(),
                definition.inputMode(),
                configured(definition.provider())
        );
    }

    private boolean configured(Provider provider) {
        return switch (provider) {
            case VEO -> hasText(properties.getGemini().getApiKey());
            case SEEDANCE -> hasText(properties.getVolcengine().getApiKey());
            case SUIXIANG_GROK -> properties.getSuiXiangVideo().getGrok().isConfigured();
            case SUIXIANG_JIMENG -> properties.getSuiXiangVideo().getJimeng().isConfigured();
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum Provider {
        VEO,
        SEEDANCE,
        SUIXIANG_GROK,
        SUIXIANG_JIMENG
    }

    public enum InputMode {
        FLEXIBLE,
        TEXT_ONLY,
        IMAGE_ONLY
    }

    private record ModelDefinition(
            String id,
            String name,
            String providerId,
            String providerLabel,
            Provider provider,
            int level,
            InputMode inputMode) {
    }

    public record ModelView(
            String id,
            String name,
            String providerId,
            String providerLabel,
            Provider provider,
            int level,
            InputMode inputMode,
            boolean configured) {
    }
}
