package com.elebusiness.service.agent;

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
}
