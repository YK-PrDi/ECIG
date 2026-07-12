package com.elebusiness.service.agent;

import com.elebusiness.service.billing.BillingService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.StringJoiner;

public final class GenerationProviderCostContext {

    private static final ThreadLocal<Map<String, LinkedHashSet<String>>> PROVIDER_TASK_IDS =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private GenerationProviderCostContext() {
    }

    public static void recordProviderTaskId(String provider, String providerTaskId) {
        if (providerTaskId == null || providerTaskId.isBlank()) {
            return;
        }
        String safeProvider = provider == null || provider.isBlank() ? "unknown" : provider.trim();
        PROVIDER_TASK_IDS.get()
                .computeIfAbsent(safeProvider, key -> new LinkedHashSet<>())
                .add(providerTaskId.trim());
    }

    public static BillingService.ProviderCost snapshotAndClear() {
        try {
            String providerTaskIds = joinedTaskIds(PROVIDER_TASK_IDS.get());
            if (providerTaskIds == null) {
                return BillingService.ProviderCost.actualPoints(0);
            }
            return new BillingService.ProviderCost(
                    0,
                    providerTaskIds,
                    null,
                    null,
                    "PROVIDER_TASK_REPORTED",
                    null
            );
        } finally {
            clear();
        }
    }

    public static boolean isEmpty() {
        return PROVIDER_TASK_IDS.get().values().stream().allMatch(LinkedHashSet::isEmpty);
    }

    public static void clear() {
        PROVIDER_TASK_IDS.remove();
    }

    private static String joinedTaskIds(Map<String, LinkedHashSet<String>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(",");
        values.values().forEach(ids -> ids.forEach(joiner::add));
        String joined = joiner.toString();
        return joined.isBlank() ? null : joined;
    }
}
