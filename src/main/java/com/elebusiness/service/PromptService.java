package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

@Service
public class PromptService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public List<Map<String, Object>> getTree() {
        File dir = new File(appProperties.getPaths().getPromptsDir());
        if (!dir.exists() || !dir.isDirectory()) return Collections.emptyList();
        return buildTree(dir);
    }

    private List<Map<String, Object>> buildTree(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return Collections.emptyList();
        Arrays.sort(entries, Comparator.comparing(File::getName));

        List<Map<String, Object>> result = new ArrayList<>();
        for (File entry : entries) {
            if (entry.isDirectory()) {
                List<Map<String, Object>> children = buildTree(entry);
                if (!children.isEmpty()) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("label", entry.getName());
                    node.put("value", entry.getName());
                    node.put("children", children);
                    result.add(node);
                }
            } else if (entry.getName().endsWith(".json")) {
                String label = entry.getName().replaceAll("\\.json$", "");
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("label", label);
                node.put("value", label);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = objectMapper.readValue(entry, Map.class);
                    node.put("prompt", data.getOrDefault("prompt", ""));
                    node.put("negativePrompt", data.getOrDefault("negativePrompt", ""));
                } catch (Exception e) {
                    node.put("prompt", "");
                    node.put("negativePrompt", "");
                }
                result.add(node);
            }
        }
        return result;
    }

    public List<Map<String, Object>> search(String keyword) {
        List<Map<String, Object>> flat = new ArrayList<>();
        flattenTree(getTree(), flat);
        String q = keyword.toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> node : flat) {
            String label  = String.valueOf(node.getOrDefault("label", "")).toLowerCase();
            String prompt = String.valueOf(node.getOrDefault("prompt", "")).toLowerCase();
            String neg    = String.valueOf(node.getOrDefault("negativePrompt", "")).toLowerCase();
            if (label.contains(q) || prompt.contains(q) || neg.contains(q)) {
                result.add(node);
            }
        }
        return result;
    }

    private void flattenTree(List<Map<String, Object>> nodes, List<Map<String, Object>> out) {
        for (Map<String, Object> node : nodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            if (children != null && !children.isEmpty()) {
                flattenTree(children, out);
            } else {
                out.add(node);
            }
        }
    }
}
