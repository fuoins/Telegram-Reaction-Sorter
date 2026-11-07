package io.github.fuoins.reactionsorter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The subtitle line is narrow: the text must stay short and must never be truncated. */
public class InfoTextTest {

    @Test
    public void normalTextIncludesCountAndMax() {
        String text = InfoText.of(47, 30, false, false, null);
        assertTrue(text.startsWith(InfoText.PREFIX));
        assertTrue(text.contains("47 条"));
        assertTrue(text.contains("最高 30"));
        assertFalse(text.contains("\n"));
    }

    @Test
    public void everyVariantStaysWithinLimit() {
        int[] counts = {0, 1, 9, 99, 9999, Integer.MAX_VALUE};
        int[] maxima = {0, 1, 12, 999, Integer.MAX_VALUE};
        String[] problems = {null, "", "扫描失败，仅当前窗口", "本地库不可用，仅当前窗口",
                "扫描已停止", "一个非常非常非常长的问题描述用来验证不会溢出副标题区域"};
        for (int count : counts) {
            for (int max : maxima) {
                for (String problem : problems) {
                    for (int flags = 0; flags < 4; flags++) {
                        String text = InfoText.of(count, max, (flags & 1) != 0,
                                (flags & 2) != 0, problem);
                        assertTrue("too long: " + text,
                                text.length() <= InfoText.MAX_CHARS);
                        assertTrue(text.startsWith(InfoText.PREFIX));
                        assertFalse(text.contains("\n"));
                        assertFalse(text.contains("\r"));
                    }
                }
            }
        }
    }

    @Test
    public void longProblemIsReducedToTheShortForm() {
        String text = InfoText.of(12, 5, false, false,
                "这是一个特别特别长的问题描述，绝对不应该出现在频道名旁边的副标题里");
        assertEquals(InfoText.PREFIX + " · 12 条", text);
    }

    @Test
    public void scanningAndWindowModesAreShort() {
        assertEquals(InfoText.PREFIX + " · 扫描中…",
                InfoText.of(5, 3, true, false, ""));
        assertEquals(InfoText.PREFIX + " · 仅当前窗口",
                InfoText.of(5, 3, false, true, ""));
        assertEquals(InfoText.PREFIX, InfoText.of(0, 0, false, false, ""));
    }

    @Test
    public void problemWinsOverOtherStates() {
        String text = InfoText.of(7, 4, true, true, "扫描已停止");
        assertTrue(text.contains("扫描已停止"));
        assertTrue(text.length() <= InfoText.MAX_CHARS);
    }
}
