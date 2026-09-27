package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.ToponCheckRound;
import com.fc.v2.service.ITToponCheckTaskService;
import com.fc.v2.service.impl.TToponCheckTaskServiceImpl;

/**
 * 定期检验派发——周期执行的口径单测。
 *
 * <p>用一座内存假库顶替 mapper：selectList 按服务里四类查询的既有语义过滤，
 * update 把 SET 落回内存行。被测的是服务层的章程——同一把尺、时段闸门、
 * 摊派、提遍节奏、挂起不堵场、空轮交代与当轮看板。</p>
 */
class ToponCheckTaskServiceImplTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    /** 内存假库 */
    private List<TToponCheckTask> table;
    /** 本轮回合被捕获的更新 */
    private List<UpdateWrapper<TToponCheckTask>> updates;

    private ITToponCheckTaskService service;

    private static Date at(int hour, int minute) {
        return Date.from(LocalDateTime.of(DAY, java.time.LocalTime.of(hour, minute)).atZone(ZONE).toInstant());
    }

    private static Date at(LocalDate day, int hour, int minute, int second) {
        return Date.from(LocalDateTime.of(day, java.time.LocalTime.of(hour, minute, second)).atZone(ZONE).toInstant());
    }

    private static TToponCheckTask row(long id, String itemNo) {
        TToponCheckTask r = new TToponCheckTask();
        r.setId(id);
        r.setItemNo(itemNo);
        r.setDelFlag(0);
        return r;
    }

    /** 待派凭据：应检日 + 提前几日 + 区 + 段 + 养护员 */
    private static TToponCheckTask waiting(long id, LocalDate dueDay, boolean endOfDay, String amount,
                                           String area, String section, String assignee) {
        TToponCheckTask r = row(id, "PH-" + id);
        r.setDueAt(at(dueDay, 0, 0, 0));
        if (endOfDay) {
            r.setDueAt(at(dueDay, 23, 59, 59));
        }
        r.setAmount(new BigDecimal(amount));
        r.setAreaName(area);
        r.setSection(section);
        r.setAssignee(assignee);
        r.setStatus(0);
        return r;
    }

    @BeforeEach
    void setUp() {
        table = new ArrayList<TToponCheckTask>();
        updates = new ArrayList<UpdateWrapper<TToponCheckTask>>();
        TToponCheckTaskMapper mapper = mock(TToponCheckTaskMapper.class);

        when(mapper.selectList(Mockito.<QueryWrapper<TToponCheckTask>>any())).thenAnswer(selectRoute());
        when(mapper.update(isNull(), Mockito.<UpdateWrapper<TToponCheckTask>>any())).thenAnswer(applyUpdate());

        TToponCheckTaskServiceImpl impl = new TToponCheckTaskServiceImpl();
        ReflectionTestUtils.setField(impl, "toponCheckTaskMapper", mapper);
        service = impl;
    }

    /** 按服务里四类查询的既有语义，从内存假库取数 */
    private Answer<List<TToponCheckTask>> selectRoute() {
        return invocation -> {
            QueryWrapper<?> w = invocation.getArgument(0);
            String seg = w.getSqlSegment() == null ? "" : w.getSqlSegment();
            String sel = w.getSqlSelect();
            return table.stream().filter(r -> {
                boolean alive = r.getDelFlag() == null || r.getDelFlag() == 0;
                if (!alive) {
                    return false;
                }
                if (sel != null && sel.contains("assignee")) {
                    return r.getAssignee() != null && !r.getAssignee().trim().isEmpty();
                }
                if (sel != null && sel.contains("area_name")) {
                    return true;
                }
                if (seg.contains("due_at")) {
                    boolean waiting = r.getStatus() == null || r.getStatus() == 0;
                    return waiting && r.getDueAt() != null && r.getAmount() != null;
                }
                return r.getStatus() != null && r.getStatus() == 1;
            }).collect(Collectors.toList());
        };
    }

    /** 把 SET 子句落回内存行，并捕获更新供断言 */
    private Answer<Integer> applyUpdate() {
        return invocation -> {
            UpdateWrapper<TToponCheckTask> w = invocation.getArgument(1);
            updates.add(w);
            Long id = (Long) paramValue(w, w.getSqlSegment(), "id");
            TToponCheckTask row = null;
            for (TToponCheckTask r : table) {
                if (r.getId().equals(id)) {
                    row = r;
                    break;
                }
            }
            if (row == null) {
                return 0;
            }
            String sqlSet = w.getSqlSet();
            for (String pair : sqlSet.split(",")) {
                String[] kv = pair.split("=", 2);
                Object val = paramValue(w, pair, kv[0].trim());
                applyColumn(row, kv[0].trim(), val);
            }
            return 1;
        };
    }

    private static Object paramValue(UpdateWrapper<?> w, String sql, String column) {
        Matcher m = Pattern.compile(Pattern.quote(column) + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sql);
        if (!m.find()) {
            return null;
        }
        return w.getParamNameValuePairs().get(m.group(1));
    }

    private static void applyColumn(TToponCheckTask row, String column, Object val) {
        switch (column) {
            case "status":
                row.setStatus(val == null ? null : ((Number) val).intValue());
                break;
            case "assignee":
                row.setAssignee((String) val);
                break;
            case "notice_count":
                row.setNoticeCount(val == null ? null : ((Number) val).intValue());
                break;
            case "last_notice_round":
                row.setLastNoticeRound(val == null ? null : ((Number) val).longValue());
                break;
            case "last_notice_at":
                row.setLastNoticeAt((Date) val);
                break;
            case "remark":
                row.setRemark((String) val);
                break;
            default:
                break;
        }
    }

    private TToponCheckTask find(long id) {
        return table.stream().filter(r -> r.getId() == id).findFirst().orElse(null);
    }

    private long noticeSets() {
        return updates.stream().filter(u -> u.getSqlSet() != null && u.getSqlSet().contains("notice_count")).count();
    }

    // ---------------------------------------------------------------
    // 同一把尺：从「最后一刻」往回数，挑单落派一处口径
    // ---------------------------------------------------------------

    @Test
    void rulerCountsFromLastMomentAndTreatsLegacyDateOnlyTheSame() {
        // 压到当天：新单记到最后一刻
        table.add(waiting(1L, DAY, true, "1", "城东区", "城郊一线", "老顾"));
        // 压到当天：早年迁进来的老数据只填了日子没填钟点——须是同一个说法
        table.add(waiting(2L, DAY, false, "1", "城东区", "城郊一线", "老顾"));
        // 明天才到最后一刻，提前 1 日启口时刻为今天 23:59:59，白天还不到
        table.add(waiting(3L, DAY.plusDays(1), true, "1", "城东区", "城郊一线", "老顾"));

        List<TToponCheckTask> due = service.listDue(at(9, 0), false);
        assertTrue(due.stream().anyMatch(r -> r.getId() == 1L), "压到当天的新单该派");
        assertTrue(due.stream().anyMatch(r -> r.getId() == 2L), "只填日子的老数据与新单同一个说法");
        assertFalse(due.stream().anyMatch(r -> r.getId() == 3L), "提前 1 日，白天还未到启口时刻");
    }

    @Test
    void rulerBoundaryIsTheExactSecond() {
        // 提前 2 日：启口时刻 = 后天最后一刻往回数 2 天 = 今天 23:59:59
        table.add(waiting(1L, DAY.plusDays(2), true, "2", "城东区", "城郊一线", "老顾"));
        assertEquals(0, service.listDue(at(DAY, 23, 59, 58), false).size(), "差一秒不到启口");
        assertEquals(1, service.listDue(at(DAY, 23, 59, 59), false).size(), "正到启口时刻即该派");
    }

    // ---------------------------------------------------------------
    // 作业时段：八时半到十七时半，出了这段按下不表
    // ---------------------------------------------------------------

    @Test
    void workWindowGatesTheRound() {
        table.add(waiting(1L, DAY, true, "1", "城东区", "城郊一线", "老顾"));

        ToponCheckRound early = service.runOnce(at(8, 29), 100L);
        assertEquals(ToponCheckRound.NOTE_OUT_OF_WINDOW, early.getNote());
        assertEquals(0, early.getDispatched());
        assertEquals(0, find(1L).getStatus(), "时辰未到不许出手");

        ToponCheckRound open = service.runOnce(at(8, 30), 101L);
        assertEquals(1, open.getDispatched(), "八时半开门即派");
        assertEquals(1, find(1L).getStatus());

        table.add(waiting(2L, DAY, true, "1", "城东区", "城郊一线", "老顾"));
        ToponCheckRound close = service.runOnce(at(17, 30), 102L);
        assertEquals(1, close.getDispatched(), "十七时半仍认");

        table.add(waiting(3L, DAY, true, "1", "城东区", "城郊一线", "老顾"));
        ToponCheckRound late = service.runOnce(at(17, 31), 103L);
        assertEquals(ToponCheckRound.NOTE_OUT_OF_WINDOW, late.getNote());
        assertEquals(0, find(3L).getStatus(), "过了十七时半按下不表，等下一轮");

        assertEquals(0, service.listDue(at(7, 0), true).size(), "挑单也认同一道闸门");
    }

    // ---------------------------------------------------------------
    // 摊派：照各人在手几条来摊，手上松的先接；派过的不许重派
    // ---------------------------------------------------------------

    @Test
    void dispatchGoesToTheFreestHand() {
        // 老顾手上压着六张没办完的（已派待接）；小王在册但手上空
        for (long i = 0; i < 6; i++) {
            TToponCheckTask held = row(100 + i, "PH-OLD-" + i);
            held.setStatus(1);
            held.setAssignee("老顾");
            held.setAreaName("城东区");
            held.setNoticeCount(1);
            held.setLastNoticeRound(9999L);
            table.add(held);
        }
        TToponCheckTask wang = row(200L, "PH-WANG");
        wang.setStatus(4);
        wang.setAssignee("小王");
        wang.setAreaName("城西区");
        table.add(wang);

        // 城郊线路又到期一张，原定的人还是老顾
        table.add(waiting(300L, DAY, true, "1", "城东区", "城郊一线", "老顾"));

        ToponCheckRound out = service.runOnce(at(10, 0), 100L);
        assertEquals(1, out.getDispatched());
        assertEquals("小王", find(300L).getAssignee(), "手上松的先接，不许尽往一个人身上落");
        assertEquals(1, find(300L).getStatus());
        assertEquals(Integer.valueOf(1), find(300L).getNoticeCount(), "派出即第一遍");
        assertEquals(Long.valueOf(100L), find(300L).getLastNoticeRound());
    }

    @Test
    void dispatchedEntryNeverComesBackNextRound() {
        table.add(waiting(1L, DAY, true, "1", "城东区", "城郊一线", "老顾"));
        ToponCheckRound first = service.runOnce(at(10, 0), 100L);
        assertEquals(1, first.getDispatched());

        ToponCheckRound second = service.runOnce(at(11, 0), 101L);
        assertEquals(0, second.getDispatched(), "这轮派过的下轮不许再翻出来重派");
        assertEquals(0, second.getReminded(), "隔一轮未满，还不到提第二遍的时候");
        assertEquals(ToponCheckRound.NOTE_EMPTY, second.getNote());
        assertEquals(1, find(1L).getNoticeCount(), "仍是派出时那一遍");
    }

    // ---------------------------------------------------------------
    // 提遍：隔一轮提第二遍，三遍无人应转人工，系统就此住口
    // ---------------------------------------------------------------

    @Test
    void remindEveryOtherRoundAndGoManualAfterTheThird() {
        TToponCheckTask m = row(1L, "PH-1");
        m.setStatus(1);
        m.setAssignee("老顾");
        m.setAreaName("城东区");
        m.setNoticeCount(1);
        m.setLastNoticeRound(100L);
        table.add(m);

        assertEquals(0, service.runOnce(at(10, 0), 101L).getReminded(), "隔一轮未满，闭口");
        assertEquals(1, service.runOnce(at(10, 0), 102L).getReminded(), "隔一轮提第二遍");
        assertEquals(Integer.valueOf(2), find(1L).getNoticeCount());
        assertEquals(0, service.runOnce(at(10, 0), 103L).getReminded());
        assertEquals(1, service.runOnce(at(10, 0), 104L).getReminded(), "提到第三遍");
        assertEquals(Integer.valueOf(3), find(1L).getNoticeCount());

        ToponCheckRound manual = service.runOnce(at(10, 0), 106L);
        assertEquals(1, manual.getManual(), "三遍仍无人应，转科里人工");
        assertEquals(3, find(1L).getStatus());
        assertTrue(find(1L).getRemark().contains("三遍无人应"));

        ToponCheckRound silent = service.runOnce(at(10, 0), 108L);
        assertEquals(0, silent.getReminded() + silent.getManual(), "系统就此住口");
    }

    @Test
    void neverAThirdRepeatedWordInOneDay() {
        // 一张单一天里听见第三句重复的话，算毛病：一整天连番跑轮，重复的话顶天两句
        TToponCheckTask m = row(1L, "PH-1");
        m.setStatus(0);
        m.setAssignee("老顾");
        m.setAreaName("城东区");
        m.setSection("城郊一线");
        m.setDueAt(at(DAY, 23, 59, 59));
        m.setAmount(new BigDecimal("1"));
        table.add(m);

        for (long round = 100L; round <= 120L; round++) {
            service.runOnce(at(10, 0), round);
        }
        long mentions = noticeSets();
        assertTrue(mentions <= 3, "一天里重复的话不许到第三句，实际开口 " + mentions + " 次");
        assertEquals(3, find(1L).getStatus(), "三遍无人应已转人工，系统住口");
    }

    // ---------------------------------------------------------------
    // 挂起：推不动的写明为什么挂，不许叫一条堵住全场
    // ---------------------------------------------------------------

    @Test
    void stuckOnesAreSuspendedWithReasonAndNeverBlockTheRest() {
        TToponCheckTask noSection = waiting(1L, DAY, true, "1", "城东区", null, "老顾");
        TToponCheckTask noKeeper = waiting(2L, DAY, true, "1", "城东区", "城郊二线", "  ");
        TToponCheckTask normal = waiting(3L, DAY, true, "1", "城西区", "城西一线", "小王");
        table.add(noSection);
        table.add(noKeeper);
        table.add(normal);

        ToponCheckRound out = service.runOnce(at(10, 0), 100L);
        assertEquals(2, out.getSuspended());
        assertEquals(1, out.getDispatched(), "后头的照样挨个轮到，不许叫一条堵住全场");

        assertEquals(2, find(1L).getStatus());
        assertTrue(find(1L).getRemark().contains("养护责任段空缺"), "挂起要写明为什么挂");
        assertEquals(2, find(2L).getStatus());
        assertTrue(find(2L).getRemark().contains("原定养护员已调离"));
        assertEquals(1, find(3L).getStatus());
        assertEquals("小王", find(3L).getAssignee());

        ToponCheckRound next = service.runOnce(at(11, 0), 101L);
        assertEquals(0, next.getSuspended(), "挂起的不再翻来覆去地挂");
    }

    // ---------------------------------------------------------------
    // 空轮与看板
    // ---------------------------------------------------------------

    @Test
    void emptyRoundSaysNoItemsNotAFault() {
        ToponCheckRound out = service.runOnce(at(10, 0), 100L);
        assertEquals(ToponCheckRound.NOTE_EMPTY, out.getNote(), "开春头一轮空跑，答一声本轮无件");
        assertEquals(0, out.getDispatched() + out.getReminded() + out.getSuspended() + out.getManual());
        assertTrue(out.getBoard().isEmpty());
    }

    @Test
    void boardLaysOutEachDistrictInThreeBuckets() {
        table.add(waiting(1L, DAY.plusDays(5), true, "1", "城东区", "城郊一线", "老顾"));
        TToponCheckTask a = row(2L, "PH-2");
        a.setStatus(1);
        a.setAssignee("老顾");
        a.setAreaName("城东区");
        a.setNoticeCount(1);
        a.setLastNoticeRound(9999L);
        table.add(a);
        TToponCheckTask b = row(3L, "PH-3");
        b.setStatus(2);
        b.setAreaName("城东区");
        table.add(b);
        TToponCheckTask c = row(4L, "PH-4");
        c.setStatus(3);
        c.setAreaName("城西区");
        table.add(c);
        TToponCheckTask d = row(5L, "PH-5");
        d.setStatus(0);
        table.add(d);

        List<ToponCheckRound.Board> board = service.board(at(10, 0));
        ToponCheckRound.Board east = board.stream().filter(x -> "城东区".equals(x.getAreaName())).findFirst().get();
        assertEquals(1, east.getDispatched());
        assertEquals(1, east.getPending());
        assertEquals(1, east.getSuspended());
        ToponCheckRound.Board west = board.stream().filter(x -> "城西区".equals(x.getAreaName())).findFirst().get();
        assertEquals(1, west.getSuspended(), "转人工归挂起这一桶");
        ToponCheckRound.Board unset = board.stream().filter(x -> "未落区".equals(x.getAreaName())).findFirst().get();
        assertEquals(1, unset.getPending(), "区落空的也有个交代");
    }

    // ---------------------------------------------------------------
    // 现成签名原样封存：旧三招还在，老路照走
    // ---------------------------------------------------------------

    @Test
    void sealedSignaturesStillAnswer() {
        assertEquals(0, service.runOnce(at(10, 0)), "旧 runOnce 原样封存");
        assertTrue(service.listDue(at(10, 0)).isEmpty(), "旧 listDue 原样封存");
        assertNull(service.selectTToponCheckTaskById(null), "旧回查原样封存");
    }
}
