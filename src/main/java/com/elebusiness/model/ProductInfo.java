package com.elebusiness.model;

import java.util.ArrayList;
import java.util.List;

public class ProductInfo {
    private String recordId;
    private String name;
    private String category;
    private List<String> main = new ArrayList<>();
    private List<String> sku = new ArrayList<>();
    private boolean has123;

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getMain() { return main; }
    public void setMain(List<String> main) { this.main = main; }
    public List<String> getSku() { return sku; }
    public void setSku(List<String> sku) { this.sku = sku; }
    public boolean isHas123() { return has123; }
    public void setHas123(boolean has123) { this.has123 = has123; }
}
