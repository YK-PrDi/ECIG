package com.elebusiness.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义生图的分析拆分规则：每个任务仅对一张参考图提取一个模板单元。
 */
final class CustomPromptAnalysisPlan {

    private static final List<AnalysisUnit> UNITS = List.of(
            new AnalysisUnit("构图", "画幅比例、版式分区、视觉重心、主体位置、镜头角度与层级关系"),
            new AnalysisUnit("核心主体", "产品外形、比例、关键结构、接口、按键、开孔、Logo、可见材质和不可改变的识别点"),
            new AnalysisUnit("人物元素", "人物或手部的数量、位置、动作、抓握关系、比例、肤质或服饰，以及与产品的真实接触关系"),
            new AnalysisUnit("背景", "背景基底色、空间类型、环境元素、材质肌理、留白区域和主体衬托方式"),
            new AnalysisUnit("文字", "仅提取参考中可确认的文字区位置、层级、排版与可读性要求；不臆造文字内容"),
            new AnalysisUnit("放大模块", "局部放大、引线、细节窗、局部特写与其对应的产品结构关系"),
            new AnalysisUnit("对比模块", "正反对比、分栏、步骤或证据模块的布局、视觉符号和要证明的差异"),
            new AnalysisUnit("光影与材质", "主辅光方向、阴影、反射、透明度、表面工艺与材质质感"),
            new AnalysisUnit("色彩体系", "主色、辅色、强调色、占比、饱和度、冷暖关系和整体调性"),
            new AnalysisUnit("风格与画质", "商业风格、摄影或插画方式、清晰度、细节密度、氛围和画质要求"),
            new AnalysisUnit("禁止项", "参考中必须避免的变形、错字、结构变化、穿模、杂物、水印或不符合物理关系的画面问题")
    );

    private CustomPromptAnalysisPlan() {
    }

    static List<Assignment> assignments(int referenceCount, int targetCount) {
        int safeReferenceCount = Math.max(1, referenceCount);
        int safeTargetCount = Math.max(1, targetCount);
        List<Assignment> assignments = new ArrayList<>(safeReferenceCount * safeTargetCount);
        for (int targetIndex = 0; targetIndex < safeTargetCount; targetIndex++) {
            for (int referenceIndex = 0; referenceIndex < safeReferenceCount; referenceIndex++) {
                int unitIndex = Math.floorMod(targetIndex * safeReferenceCount + referenceIndex, UNITS.size());
                assignments.add(new Assignment(targetIndex, referenceIndex, UNITS.get(unitIndex)));
            }
        }
        return assignments;
    }

    static String buildExtractionPrompt(String userPrompt, Assignment assignment, boolean withText) {
        String textConstraint = withText
                ? "若本单元是【文字】，只能记录图片中清晰可确认的文字或排版证据；未确认的文字不得编造。"
                : "本次不需要画面文字；若参考中有文字，只说明其位置和布局，不提取或建议任何文案。";
        return """
                你是电商视觉拆解分析师。当前只给出一张参考图，并且只能分析一个指定单元。

                【用户总要求】
                %s

                【本次唯一分析单元】
                【%s】%s

                输出规则：
                1. 仅分析这一项，不要分析其他单元，不要总结整张图，不要输出列表以外的标题。
                2. 只写 100-180 个中文字符，必须基于图片可见证据；不确定时写“未见明确证据”，不得补造。
                3. 用可直接给生图模型执行的画面语言描述位置、关系、材质、颜色、镜头或限制。
                4. 严禁改变参考图中产品主体、关键结构、比例、接口、按键、开孔、Logo 或可见文字的事实。
                5. %s

                直接输出该单元正文，不要 Markdown、不要代码块、不要解释。
                """.formatted(
                blankToDefault(userPrompt, "根据上传的产品参考图提炼可执行的电商视觉信息。"),
                assignment.unit().label(),
                assignment.unit().description(),
                textConstraint
        );
    }

    static String buildIntegrationPrompt(String userPrompt, int targetNumber, int totalCount,
                                         List<UnitResult> unitResults, boolean withText) {
        StringBuilder evidence = new StringBuilder();
        for (UnitResult result : unitResults) {
            if (result == null || result.content() == null || result.content().isBlank()) {
                continue;
            }
            evidence.append("【参考图 ").append(result.referenceIndex() + 1).append(" / ")
                    .append(result.unitLabel()).append("】\n")
                    .append(result.content().trim()).append("\n\n");
        }
        String textField = withText
                ? "【画面文案】只保留用户明确给出的文字或确定的文字区要求；给出主标题、副标题和 2-3 个标签的确切文本、位置和排版。"
                : "【画面要求】只生成纯产品图，画面上不要出现文字、标题、标签、水印或 logo。";
        return """
                你是电商生图提示词整合师。请把本张图的单元分析证据整合为一个可直接生图的完整方案。

                【用户总要求】
                %s

                【本张图编号】第 %d/%d 张
                【本张图的单元分析证据】
                %s

                输出要求：
                1. 只输出【第 %d 张方案】及下列固定字段，不要【总分析】、不要 Markdown、不要解释。
                2. 所有单元证据都来自不同参考图的单项分析，必须整合为一张结构完整、可生成的画面；证据不足时补充通用电商表达，但不得编造产品结构、品牌、认证、价格或未确认文字。
                3. 产品一致性必须覆盖所有可见参考图共同确认的外形、比例、颜色、材质、关键结构、接口、按键、开孔和 Logo。
                4. 本方案约 280-520 个中文字符；第 %d 张要与同系列其他图在卖点、镜头、构图或场景上形成差异，同时保持产品主体一致。

                【第 %d 张方案】
                【本图卖点】
                【系列连续性】
                【本图风格】
                【产品一致性】
                【安装方式】
                【形态结构】
                【材质/工艺】
                【场景构图】
                %s
                【禁止项】
                """.formatted(
                blankToDefault(userPrompt, "根据上传的产品参考图生成电商视觉方案。"),
                targetNumber,
                totalCount,
                evidence.isEmpty() ? "（本张没有有效的单元分析结果，请依据用户总要求谨慎补全。）" : evidence,
                targetNumber,
                targetNumber,
                targetNumber,
                textField
        );
    }

    static String buildSummary(String userPrompt, int targetCount, List<UnitResult> results) {
        StringBuilder confirmed = new StringBuilder();
        for (UnitResult result : results) {
            if (result == null || result.content() == null || result.content().isBlank()) continue;
            if (confirmed.length() > 0) confirmed.append("；");
            confirmed.append(result.unitLabel()).append("：")
                    .append(trimToLength(result.content().trim(), 80));
        }
        return "【总卖点】" + blankToDefault(userPrompt, "基于上传参考图提炼产品识别与核心购买理由") + "\n"
                + "【目标人群】根据产品使用场景和参考图可见结构确定目标消费者及其痛点。\n"
                + "【产品识别】" + (confirmed.isEmpty() ? "保持所有参考图共同可见的主体外形、比例、颜色、材质和关键结构。" : confirmed) + "\n"
                + "【系列连续性】共 " + targetCount + " 张图：每张图由不同参考图的单元证据整合而成，卖点与构图递进变化，但产品主体、材质识别和关键结构保持一致。";
    }

    private static String blankToDefault(String text, String defaultValue) {
        return text == null || text.isBlank() ? defaultValue : text.trim();
    }

    private static String trimToLength(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    record AnalysisUnit(String label, String description) {
    }

    record Assignment(int targetIndex, int referenceIndex, AnalysisUnit unit) {
    }

    record UnitResult(int referenceIndex, String unitLabel, String content) {
    }
}
