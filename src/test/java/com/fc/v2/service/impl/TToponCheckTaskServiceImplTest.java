package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.DispatchRoundReport;
import com.fc.v2.model.custom.RegionBoard;
import com.fc.v2.service.ITSysUserService;

/**
 * 派工摊派服务层每轮口径（内存假 mapper，无数据库）：
 * 窗口、当天算不算、在手摊派、隔轮三遍、接话、挂起/恢复、老条目归一、毛病闸、空轮、看板。
 *
 * @author fuce
 * @date 2026-09-16
 */
class TToponCheckTaskServiceImplTest {

    private TToponCheckTaskServiceImpl svc;
    private FakeCheckTaskTable db;
    private ITSysUserService userService;

    private static final long U1 = 101L;
    private static final long U2 = 102L;

    /** 小工具：墙钟 */
    private static java.util.Date t(String s) {
        return DispatchClock.wall(s);
    }

    @BeforeEach
    void setUp() {
        svc = new TToponCheckTaskServiceImpl();
        db = new FakeCheckTaskTable();
        userService = Mockito.mock(ITSysUserService.class);
        TSysUser a = new TSysUser();
        a.setId(U1);
        TSysUser b = new TSysUser();
        b.setId(U2);
        when(userService.selectTSysUserList(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<TSysUser>>any())).thenReturn(Arrays.asList(a, b));
        ReflectionTestUtils.setField(svc, "toponCheckTaskMapper", db.mapper);
        ReflectionTestUtils.setField(svc, "sysUserService", userService);
    }

    // ------------------------------------------------------------ 空轮 / 窗口

    @Test
    void emptyRoundIsAnsweredNotAFailure() {
        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        assertTrue(r.isEmpty());
        assertTrue(r.isInWindow());
        assertEquals("本轮无件", r.getMessage());
        assertEquals(0, r.getNudged());
    }

    @Test
    void outsideWindowEverythingStaysEvenWhenDue() {
        // 应检当天、已过启口线，可 07:00 在窗外：按下不表
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "东三段", null, 0));
        DispatchRoundReport r = svc.runRound(t("2026-09-16 07:00:00"));
        assertFalse(r.isInWindow());
        assertTrue(r.isEmpty());
        assertEquals(0, db.rows.get(1L).getStatus());
        assertNull(db.rows.get(1L).getOwnerId());
    }

    @Test
    void windowEdgesDispatchAtBothEnds() {
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "东三段", null, 0));
        // 08:30:00 窗内第一刻即派
        DispatchRoundReport r1 = svc.runRound(t("2026-09-16 08:30:00"));
        assertEquals(1, r1.getNudged());
        assertEquals(1, db.rows.get(1L).getStatus());

        // 复位，17:30:00 窗内最后一刻同样派
        db.rows.get(1L).setStatus(0);
        db.rows.get(1L).setOwnerId(null);
        DispatchRoundReport r2 = svc.runRound(t("2026-09-16 17:30:00"));
        assertEquals(1, r2.getNudged());
    }

    // ------------------------------------------------------------ 当天算 / 启口线

    @Test
    void dueDayCountsAsDispatchableWithOneRuler() {
        // 应检当天 09:00，无论提前量 0 还是 2，都算该派
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-1", "2026-09-20 23:59:59", "2",
                "城东区", "段A", null, 0));
        DispatchRoundReport r = svc.runRound(t("2026-09-20 09:00:00"));
        assertEquals(1, r.getNudged());
    }

    @Test
    void oldDateOnlyEntryIsJudgedBySameRuler() {
        // 早年迁入：只填日子 00:00:00，应检当天窗内照派，与新单同口径
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-OLD", "2026-09-20 00:00:00", "0",
                "城西区", "段B", null, 0));
        DispatchRoundReport r = svc.runRound(t("2026-09-20 08:30:00"));
        assertEquals(1, r.getNudged());
        assertEquals(U1, db.rows.get(1L).getOwnerId());
    }

    @Test
    void notYetOpenStaysWaiting() {
        // 应检 09-25，提前 2 天，启口线 09-23 23:59:59；09-20 窗内不派
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-9", "2026-09-25 23:59:59", "2",
                "城东区", "段A", null, 0));
        DispatchRoundReport r = svc.runRound(t("2026-09-20 10:00:00"));
        assertTrue(r.isEmpty());
        assertEquals(0, db.rows.get(1L).getStatus());
    }

    // ------------------------------------------------------------ 在手量摊派

    @Test
    void dispatchFollowsWhoHasFewestInHand() {
        // 101 手上已压一张未接的；新单该落到 102
        TToponCheckTask busy = FakeCheckTaskTable.row(1L, "P-OLD", "2026-09-16 23:59:59", "0",
                "城东区", "段A", U1, 1);
        busy.setNotifyCount(1);
        busy.setDayNotifyCount(1);
        busy.setLastNotifyAt(t("2026-09-16 08:30:00"));
        busy.setAcknowledgedAt(t("2026-09-16 08:31:00")); // 已接话，仍压在手
        db.rows.put(1L, busy);
        db.rows.put(2L, FakeCheckTaskTable.row(2L, "P-NEW", "2026-09-16 23:59:59", "0",
                "城东区", "段B", null, 0));

        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(1, r.getNudged());
        assertEquals(U2, db.rows.get(2L).getOwnerId());
    }

    @Test
    void equalLoadBreaksTieByUserIdAndSpreadsTwoNewOnes() {
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "段A", null, 0));
        db.rows.put(2L, FakeCheckTaskTable.row(2L, "P-2", "2026-09-16 23:59:59", "0",
                "城东区", "段B", null, 0));
        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(2, r.getNudged());
        assertEquals(U1, db.rows.get(1L).getOwnerId());
        assertEquals(U2, db.rows.get(2L).getOwnerId());
        // 派出去打了记号，不会重派
        DispatchRoundReport again = svc.runRound(t("2026-09-16 10:30:00"));
        assertEquals(0, again.getNudged());
    }

    // ------------------------------------------------------------ 隔轮三遍转人工

    @Test
    void remindSkipsOneRoundAndThirdUnansweredGoesManualThenStaysSilent() {
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "段A", null, 0));

        // 第一遍：09:00 派出
        assertEquals(1, svc.runRound(t("2026-09-16 09:00:00")).getNudged());
        assertEquals(1, db.rows.get(1L).getNotifyCount());

        // 相邻一轮（09:30，不足一个半轮）不出声
        DispatchRoundReport at0930 = svc.runRound(t("2026-09-16 09:30:00"));
        assertEquals(0, at0930.getReminded());
        assertEquals(1, db.rows.get(1L).getNotifyCount());

        // 隔一轮（10:00，相距 60 分）提第二遍
        DispatchRoundReport at1000 = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(1, at1000.getReminded());
        assertEquals(2, db.rows.get(1L).getNotifyCount());

        // 10:30 又不到节奏，不出声
        assertEquals(0, svc.runRound(t("2026-09-16 10:30:00")).getReminded());

        // 11:00 第三遍仍无应：转人工，系统住口
        DispatchRoundReport at1100 = svc.runRound(t("2026-09-16 11:00:00"));
        assertEquals(1, at1100.getManual());
        assertEquals(2, db.rows.get(1L).getStatus());

        // 此后再跑多少轮，住口：状态不再变
        svc.runRound(t("2026-09-16 12:00:00"));
        svc.runRound(t("2026-09-16 13:00:00"));
        assertEquals(2, db.rows.get(1L).getStatus());
        assertEquals(3, db.rows.get(1L).getNotifyCount());
    }

    @Test
    void sameDayThirdUnspacedRepeatIsAFaultAndBlocked() {
        // 已两声、同日 20 分钟后又触发（没隔轮）：第三句重复话算毛病，只闸下不出声
        TToponCheckTask r = FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "段A", U1, 1);
        r.setNotifyCount(2);
        r.setDayNotifyCount(2);
        r.setLastNotifyAt(t("2026-09-16 09:00:00"));
        db.rows.put(1L, r);

        DispatchRoundReport rep = svc.runRound(t("2026-09-16 09:20:00"));
        assertEquals(1, rep.getFaultBlocked());
        assertEquals(0, rep.getReminded());
        assertEquals(0, rep.getManual());
        assertEquals(2, db.rows.get(1L).getNotifyCount(), "毛病闸下，遍数不加");
    }

    // ------------------------------------------------------------ 接话

    @Test
    void acknowledgeOnlyByOwnerOnce() {
        TToponCheckTask r = FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "段A", U1, 1);
        r.setNotifyCount(1);
        db.rows.put(1L, r);

        assertTrue(svc.acknowledge(1L, U1));
        assertNotNull(db.rows.get(1L).getAcknowledgedAt());
        // 重复接话、别人接话都接不动
        assertFalse(svc.acknowledge(1L, U1));
        assertFalse(svc.acknowledge(1L, U2));

        // 接话后系统不再催
        DispatchRoundReport rep = svc.runRound(t("2026-09-16 11:00:00"));
        assertEquals(0, rep.getReminded());
        assertEquals(0, rep.getManual());
        assertEquals(1, db.rows.get(1L).getStatus());
    }

    // ------------------------------------------------------------ 挂起 / 恢复 / 不堵场

    @Test
    void defectiveRowsHangWithReasonAndLaterRowsStillProcess() {
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-EMPTY-SEG", "2026-09-16 23:59:59", "0",
                "城东区", "   ", null, 0));
        db.rows.put(2L, FakeCheckTaskTable.row(2L, "P-NO-DATE", null, null,
                "城东区", "段X", null, 0));
        db.rows.put(3L, FakeCheckTaskTable.row(3L, "P-OK", "2026-09-16 23:59:59", "0",
                "城西区", "段Y", null, 0));

        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(2, r.getHung());
        assertEquals(1, r.getNudged(), "挂起不堵后面，好单照派");
        assertEquals(3, db.rows.get(1L).getStatus());
        assertEquals(3, db.rows.get(2L).getStatus());
        assertTrue(db.rows.get(1L).getHangReason().contains("责任段"));
        assertEquals(2, r.getHangs().size(), "回话逐条写明本轮为什么挂");
    }

    @Test
    void oldOwnerLeftPayrollHangs() {
        TToponCheckTask r = FakeCheckTaskTable.row(1L, "P-OLD", "2026-09-16 23:59:59", "0",
                "城东区", "段A", 999L, 1);
        r.setNotifyCount(1);
        db.rows.put(1L, r);
        DispatchRoundReport rep = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(1, rep.getHung());
        assertEquals(3, db.rows.get(1L).getStatus());
        assertTrue(db.rows.get(1L).getHangReason().contains("离册"));
    }

    @Test
    void hungRowResumesAndDispatchesOnceDefectCleared() {
        TToponCheckTask r = FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", null, null, 3);
        r.setHangReason("养护责任段空缺");
        db.rows.put(1L, r);

        // 毛病仍在：继续挂
        DispatchRoundReport first = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(0, first.getResumed());

        // 科里补了责任段：下一轮恢复并当场派出
        db.rows.get(1L).setDutySegment("段A");
        DispatchRoundReport second = svc.runRound(t("2026-09-16 10:30:00"));
        assertEquals(1, second.getResumed());
        assertEquals(1, second.getNudged());
        assertEquals(1, db.rows.get(1L).getStatus());
        assertNull(db.rows.get(1L).getHangReason());
    }

    @Test
    void noMaintainerHangsNewOnesWithoutSilencingWholeRound() {
        when(userService.selectTSysUserList(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<TSysUser>>any())).thenReturn(java.util.Collections.emptyList());
        db.rows.put(1L, FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "段A", null, 0));
        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        assertEquals(1, r.getHung());
        assertTrue(db.rows.get(1L).getHangReason().contains("无人可摊"));
    }

    // ------------------------------------------------------------ 看板

    @Test
    void boardReportsTodayRegionsFromTheRoundReport() {
        // 城东区：1 已派（已接话）/ 1 转人工（算未派）；城西区：1 挂起；隔日单不计
        TToponCheckTask done = FakeCheckTaskTable.row(1L, "P-1", "2026-09-16 23:59:59", "0",
                "城东区", "段A", U1, 1);
        done.setNotifyCount(1);
        done.setDayNotifyCount(1);
        done.setLastNotifyAt(t("2026-09-16 08:30:00"));
        done.setAcknowledgedAt(t("2026-09-16 08:31:00"));
        db.rows.put(1L, done);
        db.rows.put(2L, FakeCheckTaskTable.row(2L, "P-2", "2026-09-16 23:59:59", "0",
                "城东区", "段B", U2, 2)); // 提满三声转人工：仍算未派
        TToponCheckTask hung = FakeCheckTaskTable.row(3L, "P-3", "2026-09-16 23:59:59", "0",
                "城西区", null, null, 3);
        hung.setHangReason("养护责任段空缺");
        db.rows.put(3L, hung);
        db.rows.put(4L, FakeCheckTaskTable.row(4L, "P-OLD-DAY", "2026-09-10 23:59:59", "0",
                "城南区", "段C", null, 0));

        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        List<RegionBoard> boards = r.getBoards();
        assertEquals(2, boards.size(), "只报当日（09-16）有单的区；城南区隔日不上屏");
        RegionBoard east = boards.get(0); // TreeMap 区名定序：城东区 < 城西区
        assertEquals("城东区", east.getRegion());
        assertEquals(1, east.getDispatched());
        assertEquals(1, east.getPending());
        assertEquals(0, east.getHung());
        RegionBoard west = boards.get(1);
        assertEquals("城西区", west.getRegion());
        assertEquals(1, west.getHung());
    }

    @Test
    void lastReportIsKeptForPage() {
        assertNull(svc.lastReport());
        DispatchRoundReport r = svc.runRound(t("2026-09-16 10:00:00"));
        assertNotNull(svc.lastReport());
        assertEquals(r, svc.lastReport());
    }
}
