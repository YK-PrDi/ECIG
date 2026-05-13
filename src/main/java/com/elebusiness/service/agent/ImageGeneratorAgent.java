package com.elebusiness.service.agent;

import java.util.List;

/**
 * 图片生成智能体统一契约。
 * 每个接入的 AI 模型实现此接口，即可自动注册到系统并在前端可选。
 */
public interface ImageGeneratorAgent {

    /** 唯一标识符，前端通过此 ID 选择模型，例如 "gemini"、"wan2.7" */
    String getId();

    /** 前端显示名称，例如 "Gemini 3.1 Flash"、"万相 2.7 Pro" */
    String getDisplayName();

    /**
     * 执行图片生成。
     *
     * @param prompt       提示词
     * @param refImagePath 参考图本地路径（图像编辑类模型使用，可为 null）
     * @param whiteBgPath  白底图本地路径或 HTTP URL（可为 null）
     * @param outputPath   输出文件保存路径
     * @return 生成成功返回 true
     */
    boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath);

    /**
     * 多参考图版本（用于品牌/产品一致性强约束场景）。
     * 默认实现：仅取第一张走 {@link #generate}，方便老 agent 平滑兼容；支持多图的实现应覆写此方法。
     *
     * @param refImagePaths 参考图列表（顺序保持），可为空
     * @param aspect        画幅比例提示，"1:1"/"9:16"/"16:9"，由 agent 自行映射到 size；null 视作 1:1
     */
    default boolean generateMulti(String prompt, List<String> refImagePaths,
                                  String whiteBgPath, String outputPath, String aspect) {
        String first = (refImagePaths != null && !refImagePaths.isEmpty()) ? refImagePaths.get(0) : null;
        return generate(prompt, first, whiteBgPath, outputPath);
    }
}
