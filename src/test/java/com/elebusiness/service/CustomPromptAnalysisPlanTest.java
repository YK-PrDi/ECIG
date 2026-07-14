package com.elebusiness.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomPromptAnalysisPlanTest {

    @Test
    void assignsOneTemplateUnitToEachReferenceForEveryTargetImage() {
        List<CustomPromptAnalysisPlan.Assignment> assignments =
                CustomPromptAnalysisPlan.assignments(3, 3);

        assertEquals(9, assignments.size());
        assertEquals(3, assignments.stream().filter(a -> a.targetIndex() == 0).count());
        assertEquals(3, assignments.stream().filter(a -> a.targetIndex() == 1).count());
        assertEquals(3, assignments.stream().filter(a -> a.targetIndex() == 2).count());
        assertEquals(3, assignments.stream().filter(a -> a.targetIndex() == 0)
                .map(CustomPromptAnalysisPlan.Assignment::referenceIndex).distinct().count());
        assertEquals(3, assignments.stream().filter(a -> a.targetIndex() == 0)
                .map(CustomPromptAnalysisPlan.Assignment::unit).distinct().count());
    }

    @Test
    void extractionPromptRestrictsOneReferenceToOneShortTemplateUnit() {
        CustomPromptAnalysisPlan.Assignment assignment =
                CustomPromptAnalysisPlan.assignments(1, 1).get(0);

        String prompt = CustomPromptAnalysisPlan.buildExtractionPrompt(
                "突出花洒的自洁功能", assignment, true);

        assertTrue(prompt.contains("【" + assignment.unit().label() + "】"));
        assertTrue(prompt.contains("100-180"));
        assertTrue(prompt.contains("仅分析这一项"));
        assertTrue(!prompt.contains("【核心主体】") || assignment.unit().label().equals("核心主体"));
    }

    @Test
    void integrationPromptUsesOnlyTheTargetImagesUnitResults() {
        String prompt = CustomPromptAnalysisPlan.buildIntegrationPrompt(
                "突出花洒的自洁功能",
                2,
                3,
                List.of(
                        new CustomPromptAnalysisPlan.UnitResult(0, "构图", "参考图一的构图结果"),
                        new CustomPromptAnalysisPlan.UnitResult(2, "人物元素", "参考图三的手部结果")
                ),
                true
        );

        assertTrue(prompt.contains("参考图一的构图结果"));
        assertTrue(prompt.contains("参考图三的手部结果"));
        assertTrue(prompt.contains("【第 2 张方案】"));
        assertTrue(prompt.contains("【画面文案】"));
    }
}
