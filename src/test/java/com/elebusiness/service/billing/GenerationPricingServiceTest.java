package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationPricingServiceTest {

    @Test
    void pricingReturnsZeroWhenChargingIsDisabled() {
        AppProperties props = new AppProperties();
        GenerationPricingService pricingService = new GenerationPricingService(props);

        assertEquals(0, pricingService.estimateImageGeneration("custom", "liblib-lora", 3));
        assertEquals(0, pricingService.estimateVideoGeneration("veo-3.1-generate-preview", 8));
    }

    @Test
    void imagePricingUsesAgentOverrideWhenChargingIsEnabled() {
        AppProperties props = new AppProperties();
        props.getBilling().setChargeEnabled(true);
        props.getBilling().setDefaultImagePoints(5);
        props.getBilling().getImageAgentPoints().put("liblib-lora", 12);
        GenerationPricingService pricingService = new GenerationPricingService(props);

        assertEquals(24, pricingService.estimateImageGeneration("custom", "liblib-lora", 2));
        assertEquals(15, pricingService.estimateImageGeneration("custom", "gpt-image", 3));
        assertEquals(0, pricingService.estimateImageGeneration("custom", "gpt-image", 0));
    }

    @Test
    void videoPricingUsesModelOverrideAndDuration() {
        AppProperties props = new AppProperties();
        props.getBilling().setChargeEnabled(true);
        props.getBilling().setDefaultVideoBasePoints(50);
        props.getBilling().setDefaultVideoSecondPoints(3);
        props.getBilling().getVideoModelBasePoints().put("doubao-seedance", 90);
        GenerationPricingService pricingService = new GenerationPricingService(props);

        assertEquals(74, pricingService.estimateVideoGeneration("veo-3.1-generate-preview", 8));
        assertEquals(114, pricingService.estimateVideoGeneration("doubao-seedance-2-0-260128", 8));
    }
}
