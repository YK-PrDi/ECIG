package com.elebusiness.model;

import java.util.List;
import java.util.Map;

public class DingTalkRecord {
    private String id;
    private Map<String, Object> fields;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Map<String, Object> getFields() { return fields; }
    public void setFields(Map<String, Object> fields) { this.fields = fields; }

    public String getFieldAsString(String key) {
        Object val = fields == null ? null : fields.get(key);
        return val == null ? "" : val.toString();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFieldAsImageList(String key) {
        Object val = fields == null ? null : fields.get(key);
        if (val instanceof List) {
            return (List<Map<String, Object>>) val;
        }
        return List.of();
    }
}
