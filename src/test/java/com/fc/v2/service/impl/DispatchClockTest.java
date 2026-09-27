package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Date;

import org.junit.jupiter.api.Test;

/**
 * DispatchClock 口径钉死：外业窗边界、日终归一、启口线压线、隔轮节奏、北京墙钟。
 *
 * @author fuce
 * @date 2026-09-16
 */
class DispatchClockTest {

    @Test
    void windowBounds() {
        // 两端那一秒都算窗内；前后一秒都不算
        assertTrue(DispatchClock.inWindow(DispatchClock.wall("2026-09-16 08:30:00")));
        assertTrue(DispatchClock.inWindow(DispatchClock.wall("2026-09-16 12:00:00")));
        assertTrue(DispatchClock.inWindow(DispatchClock.wall("2026-09-16 17:30:00")));
        assertFalse(DispatchClock.inWindow(DispatchClock.wall("2026-09-16 08:29:59")));
        assertFalse(DispatchClock.inWindow(DispatchClock.wall("2026-09-16 17:30:01")));
        assertFalse(DispatchClock.inWindow(DispatchClock.wall("2026-09-16 07:00:00")));
        assertFalse(DispatchClock.inWindow(null));
    }

    @Test
    void midnightOnlyEntriesAreLiftedToEndOfDay() {
        Date oldEntry = DispatchClock.wall("2026-09-20 00:00:00");
        assertEquals("2026-09-20 23:59:59", DispatchClock.wall(DispatchClock.effectiveDue(oldEntry)));

        // 系统新单本来就落在最后一刻，原样
        Date newEntry = DispatchClock.wall("2026-09-20 23:59:59");
        assertEquals(newEntry, DispatchClock.effectiveDue(newEntry));

        // 带钟点的非午夜时刻也原样（不许顺手改别人）
        Date noon = DispatchClock.wall("2026-09-20 12:30:00");
        assertEquals(noon, DispatchClock.effectiveDue(noon));
    }

    @Test
    void openLineIsCountedBackFromEndOfDayAndBoundaryCounts() {
        Date due = DispatchClock.wall("2026-09-20 23:59:59");
        BigDecimal twoDays = new BigDecimal("2");

        // 启口日 = 应检日往回第 2 个自然日（09-18）。那天一进外业窗就算到线，
        // 窗内不看秒——08:30:00 与 17:30:00 同判该派
        assertTrue(DispatchClock.isOpen(DispatchClock.wall("2026-09-18 08:30:00"), due, twoDays));
        assertTrue(DispatchClock.isOpen(DispatchClock.wall("2026-09-18 17:30:00"), due, twoDays));
        // 启口日的前一个自然日（09-17），哪怕夜里 23:59:59 也还差一日：不派
        assertFalse(DispatchClock.isOpen(DispatchClock.wall("2026-09-17 23:59:59"), due, twoDays));
        // 应检当天（余 0 日）必算该派，且与提前量无关
        assertTrue(DispatchClock.isOpen(DispatchClock.wall("2026-09-20 08:30:00"), due, BigDecimal.ZERO));
        assertTrue(DispatchClock.isOpen(DispatchClock.wall("2026-09-20 08:30:00"), due, twoDays));
    }

    @Test
    void fractionalAdvanceSwallowsByNaturalDayFromEndOfDay() {
        Date due = DispatchClock.wall("2026-09-20 23:59:59");
        BigDecimal twoAndHalf = new BigDecimal("2.5");
        // 自然日整日计数：余下 2 日已在 2.5 日之内，09-18 窗内即该派
        assertTrue(DispatchClock.isOpen(DispatchClock.wall("2026-09-18 12:00:00"), due, twoAndHalf));
        assertFalse(DispatchClock.isOpen(DispatchClock.wall("2026-09-17 12:00:00"), due, twoAndHalf));
        // 负提前量是坏口径，不许偷偷放开
        assertFalse(DispatchClock.isOpen(DispatchClock.wall("2026-09-20 12:00:00"), due, new BigDecimal("-1")));
    }

    @Test
    void oldDateOnlyAndNewEntryShareOneRuler() {
        // 老条目只填日子(00:00:00) 与 新单(23:59:59) 在应检当天 08:30 同判：都算该派
        Date oldEntry = DispatchClock.wall("2026-09-20 00:00:00");
        Date newEntry = DispatchClock.wall("2026-09-20 23:59:59");
        Date at = DispatchClock.wall("2026-09-20 08:30:00");
        assertTrue(DispatchClock.isOpen(at, oldEntry, BigDecimal.ZERO));
        assertTrue(DispatchClock.isOpen(at, newEntry, BigDecimal.ZERO));

        // 提前量 2 天时，两者的启口线也同为 09-18 23:59:59，不各判各的
        Date line = DispatchClock.wall("2026-09-18 23:59:59");
        assertTrue(DispatchClock.isOpen(line, oldEntry, new BigDecimal("2")));
        assertTrue(DispatchClock.isOpen(line, newEntry, new BigDecimal("2")));
    }

    @Test
    void cadenceGateIsOneAndHalfRounds() {
        Date first = DispatchClock.wall("2026-09-16 09:00:00");
        // 从没提过：可提
        assertTrue(DispatchClock.cadenceReady(DispatchClock.wall("2026-09-16 09:00:00"), null));
        // 隔一轮有余（45 分钟，半轮容差）：可提
        assertTrue(DispatchClock.cadenceReady(DispatchClock.wall("2026-09-16 09:45:00"), first));
        // 44 分 59 秒：节奏没到，不出声
        assertFalse(DispatchClock.cadenceReady(new Date(first.getTime() + 44 * 60_000 + 59_000), first));
    }

    @Test
    void wallClockIsBeijingRegardlessOfJvmZone() {
        // 格式化固定走 GMT+8
        assertEquals("2026-09-16", DispatchClock.day(DispatchClock.wall("2026-09-16 00:00:00")));
        assertEquals("2026-09-16 23:59:59", DispatchClock.wall(DispatchClock.wall("2026-09-16 23:59:59")));
        assertNotEquals(null, DispatchClock.ZONE.getID());
    }

    @Test
    void missingInputsNeverOpen() {
        Date at = DispatchClock.wall("2026-09-20 10:00:00");
        assertFalse(DispatchClock.isOpen(at, null, new BigDecimal("2")));
        assertFalse(DispatchClock.isOpen(at, DispatchClock.wall("2026-09-20 23:59:59"), null));
        assertFalse(DispatchClock.isOpen(null, DispatchClock.wall("2026-09-20 23:59:59"), new BigDecimal("2")));
    }
}
