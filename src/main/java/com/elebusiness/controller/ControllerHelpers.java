package com.elebusiness.controller;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller 之间共享的私有工具方法。A.1 拆分时各自保留了一份重复，B 阶段 #10 抽取到这里。
 * 没有 @Component —— 全部是无状态静态方法，不需要 Spring 容器管理。
 */
final class ControllerHelpers {

    private ControllerHelpers() {}

    /** 拼装 task 结果项。output / message 为 null 时不写入对应字段，让 JSON 输出更干净。 */
    static Map<String, Object> result(String name, String status, String message, String output) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        if (message != null) m.put("message", message);
        if (output  != null) m.put("output",  output);
        return m;
    }

    /** 递归统计目录下的图片文件数（jpg/jpeg/png）。null/空目录返回 0。 */
    static int countImages(File dir) {
        if (dir == null) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                count += countImages(child);
            } else {
                String n = child.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) count++;
            }
        }
        return count;
    }
}
