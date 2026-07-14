package com.elebusiness.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class GenerationTask {

    private final String id;
    private final long ownerUserId;
    /** pending / running / done / stopped / error */
    private volatile String status = "pending";
    private volatile int progress = 0;
    private volatile int total;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String currentProduct = "";
    private volatile Long usageLogId;
    private final List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
    private final long createdAt = System.currentTimeMillis();

    public GenerationTask(String id, int total) {
        this(id, 0L, total);
    }

    public GenerationTask(String id, long ownerUserId, int total) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.total = total;
    }

    public String getId() { return id; }
    public long getOwnerUserId() { return ownerUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void incrementProgress() { this.progress++; }
    public int getTotal() { return total; }
    public boolean isCancelled() { return cancelled.get(); }
    public boolean requestCancel() { return cancelled.compareAndSet(false, true); }
    public void cancel() { requestCancel(); }
    public String getCurrentProduct() { return currentProduct; }
    public void setCurrentProduct(String p) { this.currentProduct = p; }
    public Long getUsageLogId() { return usageLogId; }
    public void setUsageLogId(Long usageLogId) { this.usageLogId = usageLogId; }
    public List<Map<String, Object>> getResults() { return results; }
    public void addResult(Map<String, Object> r) { results.add(r); }
    public long getCreatedAt() { return createdAt; }
}
