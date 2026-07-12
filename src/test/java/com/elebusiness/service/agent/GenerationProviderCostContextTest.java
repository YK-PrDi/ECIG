package com.elebusiness.service.agent;

import com.elebusiness.service.billing.BillingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationProviderCostContextTest {

    @AfterEach
    void tearDown() {
        GenerationProviderCostContext.clear();
    }

    @Test
    void snapshotAggregatesProviderTaskIdsAndClearsThreadLocalState() {
        GenerationProviderCostContext.recordProviderTaskId("liblib", "uuid-001");
        GenerationProviderCostContext.recordProviderTaskId("liblib", "uuid-002");
        GenerationProviderCostContext.recordProviderTaskId("liblib", "uuid-001");

        BillingService.ProviderCost cost = GenerationProviderCostContext.snapshotAndClear();

        assertEquals(0, cost.actualPoints());
        assertEquals("uuid-001,uuid-002", cost.providerTaskId());
        assertEquals("PROVIDER_TASK_REPORTED", cost.costSource());
        assertTrue(GenerationProviderCostContext.isEmpty());
    }
}
