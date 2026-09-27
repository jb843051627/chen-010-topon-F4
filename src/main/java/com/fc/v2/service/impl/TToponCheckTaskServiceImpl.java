package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.ToponCheckRound;
import com.fc.v2.service.ITToponCheckTaskService;

/**
 * 定期检验派发条目 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * <p>2026-09 摊派新能：派单不靠人开口，系统照各人在手几条来摊，手上松的先接。
 * 调度那头触发的整套执行都落在本层；现成签名原样封存，新能一概另起重载。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponCheckTaskServiceImpl implements ITToponCheckTaskService {

    /** 允许提前派发的自然日数窗口 [2, 5] */
    private static final BigDecimal WIN_FROM = new BigDecimal("2");
    private static final BigDecimal WIN_TO = new BigDecimal("5");
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    // ---------------------------------------------------------------
    // 2026-09 摊派新能的口径常数
    // ---------------------------------------------------------------
    /** 外业作业时段 [08:30, 17:30]（含两头），出了这段按下不表 */
    private static final LocalTime WORK_FROM = LocalTime.of(8, 30);
    private static final LocalTime WORK_TO = LocalTime.of(17, 30);
    /** 墙钟轮次桶：30 分钟一桶，同一桶内重复触发视同同一轮 */
    private static final long ROUND_MILLIS = 30L * 60L * 1000L;
    /** 隔一轮提一遍：距上一遍满两轮才再开口 */
    private static final long REMIND_EVERY_ROUNDS = 2L;
    /** 一张单一共只提三遍（已派算第一遍），三遍无人应转人工，系统就此住口——
     *  一天里断不会听见第三句重复的话（重复的话顶天两句） */
    private static final int NOTICE_MAX = 3;
    /** 条目情形补充：3转人工 4已接（0待派 1已派待接 2派不出去/挂起 沿用旧口径） */
    private static final int STATUS_MANUAL = 3;
    private static final int STATUS_ACCEPTED = 4;
    /** 所属区落空的条目，看板上归这一格 */
    private static final String AREA_UNSET = "未落区";

    @javax.annotation.Resource
    private TToponCheckTaskMapper toponCheckTaskMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone=GMT 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /**
     * 唯一的"可处理"口径：未删（含 del_flag 为 NULL 的老数据）+ 待派（status 缺省视为待派）
     * + 已到日终截止（due_at <= at，恰在截止时刻也算到期）+ 提前派发天数落在窗口 [2,5]。
     */
    private QueryWrapper<TToponCheckTask> due(QueryWrapper<TToponCheckTask> wrapper, Date at) {
        String now = ts(at);
        wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        wrapper.and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"));
        wrapper.apply("(due_at IS NOT NULL AND due_at <= {0})", now);
        wrapper.apply("(amount IS NOT NULL AND amount >= {0} AND amount <= {1})", WIN_FROM, WIN_TO);
        return wrapper;
    }

    @Override
    public TToponCheckTask selectTToponCheckTaskById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponCheckTaskMapper.selectById(id);
    }

    @Override
    public List<TToponCheckTask> listDue(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TToponCheckTask>();
        }
        return this.toponCheckTaskMapper.selectList(due(new QueryWrapper<TToponCheckTask>(), at));
    }

    @Override
    public int runOnce(Date at) {
        if (at == null) {
            return 0;
        }
        List<TToponCheckTask> todo = listDue(at);
        int ok = 0;
        for (TToponCheckTask r : todo) {
            try {
                // 以待派为前提条件更新：同一时刻两处调度只可能有一处抢到，单条失败不影响其余
                int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                        .eq("id", r.getId())
                        .and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"))
                        .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                        .set("status", STATUS_DONE)
                        .set("update_time", new Date()));
                if (rows > 0) {
                    ok++;
                }
            } catch (RuntimeException e) {
                // 单条派发失败：记"派不出去"，跳过继续
                this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                        .eq("id", r.getId())
                        .and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"))
                        .set("status", STATUS_FAIL)
                        .set("update_time", new Date()));
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------
    // 2026-09 摊派新能：整套执行落在本层，旧签名一律未动
    // ---------------------------------------------------------------

    /**
     * 唯一的一把尺：距应检之日余下几日，一律从「最后一刻」往回数。
     * 早年迁进来只填日子没填钟点的，先归到那天的最后一刻再数——与系统自己落的
     * 新单同一个说法，不许各判各的。压到当天也算该派（此刻不早于启口时刻即该派）。
     */
    private static boolean dispatchable(Date dueAt, BigDecimal amount, Date at) {
        if (dueAt == null || amount == null || at == null) {
            return false;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate dueDay = dueAt.toInstant().atZone(zone).toLocalDate();
        LocalDateTime lastMoment = LocalDateTime.of(dueDay, LocalTime.of(23, 59, 59));
        long leadSeconds = amount.multiply(BigDecimal.valueOf(86400L)).longValue();
        LocalDateTime openAt = lastMoment.minusSeconds(leadSeconds);
        LocalDateTime now = at.toInstant().atZone(zone).toLocalDateTime();
        return !now.isBefore(openAt);
    }

    /** 外业只认 08:30-17:30 这一段（含两头） */
    private static boolean inWorkWindow(Date at) {
        LocalTime t = at.toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
        return !t.isBefore(WORK_FROM) && !t.isAfter(WORK_TO);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 挂起写明原因：原因缀在备注后头，不盖掉旧记事 */
    private static String appendRemark(String oldRemark, String reason) {
        return isBlank(oldRemark) ? reason : oldRemark + "；" + reason;
    }

    @Override
    public long roundOf(Date at) {
        if (at == null) {
            return -1L;
        }
        return at.getTime() / ROUND_MILLIS;
    }

    @Override
    public List<TToponCheckTask> listDue(Date at, boolean workWindowGate) {
        List<TToponCheckTask> due = new ArrayList<TToponCheckTask>();
        if (at == null) {
            return due;
        }
        if (workWindowGate && !inWorkWindow(at)) {
            // 出了这段即便到了该派的时候也按下不表
            return due;
        }
        // SQL 只挡粗口径（在册 + 待派 + 凭据齐全），该派与否一律过 Java 里那把尺，
        // 挑单与落派同用 dispatchable，一处不许两把尺
        QueryWrapper<TToponCheckTask> wrapper = new QueryWrapper<TToponCheckTask>();
        wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        wrapper.and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"));
        wrapper.isNotNull("due_at");
        wrapper.isNotNull("amount");
        wrapper.orderByAsc("id");
        List<TToponCheckTask> rows = this.toponCheckTaskMapper.selectList(wrapper);
        for (TToponCheckTask r : rows) {
            if (dispatchable(r.getDueAt(), r.getAmount(), at)) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public ToponCheckRound runOnce(Date at, long round) {
        ToponCheckRound outcome = new ToponCheckRound();
        outcome.setAt(at);
        outcome.setRound(round);
        if (at == null) {
            outcome.setNote(ToponCheckRound.NOTE_EMPTY);
            return outcome;
        }
        if (!inWorkWindow(at)) {
            // 出了作业时段，即便到了该派的时候也按下不表，等下一轮
            outcome.setNote(ToponCheckRound.NOTE_OUT_OF_WINDOW);
            outcome.setBoard(board(at));
            return outcome;
        }

        // 外业队名册与在手几条：在册条目上落过名的养护员；待派+已派待接即压在手上的未办结
        Map<String, Integer> inHand = rosterLoads();

        // 一、摊派：该派的逐条落，手上松的先接；推不动的挂起写明原因，不许叫一条堵住全场
        for (TToponCheckTask r : listDue(at, false)) {
            try {
                if (isBlank(r.getSection())) {
                    outcome.setSuspended(outcome.getSuspended() + (suspend(r, "养护责任段空缺，挂起待科里补登") > 0 ? 1 : 0));
                    continue;
                }
                if (isBlank(r.getAssignee())) {
                    outcome.setSuspended(outcome.getSuspended() + (suspend(r, "原定养护员已调离，挂起待科里定夺") > 0 ? 1 : 0));
                    continue;
                }
                String picked = pickFreest(inHand);
                if (picked == null) {
                    outcome.setSuspended(outcome.getSuspended() + (suspend(r, "外业队名册无人可派，挂起待科里定夺") > 0 ? 1 : 0));
                    continue;
                }
                // 以待派为前提条件落派：同一轮两处触发只抢得到一处，这轮派过的下轮也翻不出来
                int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                        .eq("id", r.getId())
                        .and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"))
                        .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                        .set("status", STATUS_DONE)
                        .set("assignee", picked)
                        .set("notice_count", 1)
                        .set("last_notice_round", round)
                        .set("last_notice_at", at)
                        .set("update_time", new Date()));
                if (rows > 0) {
                    inHand.merge(picked, 1, Integer::sum);
                    outcome.setDispatched(outcome.getDispatched() + 1);
                }
            } catch (RuntimeException e) {
                // 单条掉链子不堵全场：挂起写明原因，后面的照样挨个轮到
                outcome.setSuspended(outcome.getSuspended() + (suspend(r, "派发异常挂起：" + e.getMessage()) > 0 ? 1 : 0));
            }
        }

        // 二、提遍：已派待接而没接话的，隔一轮提一遍；提到第三遍仍无人应，
        // 转科里人工盯着，系统就此住口
        for (TToponCheckTask r : listWaitingAccept()) {
            try {
                Long lastRound = r.getLastNoticeRound();
                if (lastRound != null && round - lastRound < REMIND_EVERY_ROUNDS) {
                    continue;
                }
                int noticed = r.getNoticeCount() == null ? 1 : r.getNoticeCount();
                if (noticed >= NOTICE_MAX) {
                    int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                            .eq("id", r.getId())
                            .eq("status", STATUS_DONE)
                            .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                            .set("status", STATUS_MANUAL)
                            .set("remark", appendRemark(r.getRemark(), "三遍无人应，转科里人工盯着"))
                            .set("update_time", new Date()));
                    if (rows > 0) {
                        outcome.setManual(outcome.getManual() + 1);
                    }
                    continue;
                }
                // 以读到的遍数/轮次为前提：同一轮重复触发抢不到第二遍
                UpdateWrapper<TToponCheckTask> remind = new UpdateWrapper<TToponCheckTask>()
                        .eq("id", r.getId())
                        .eq("status", STATUS_DONE)
                        .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                        .and(w -> w.eq("notice_count", noticed).or().isNull("notice_count"));
                if (lastRound == null) {
                    remind.isNull("last_notice_round");
                } else {
                    remind.eq("last_notice_round", lastRound);
                }
                int rows = this.toponCheckTaskMapper.update(null, remind
                        .set("notice_count", noticed + 1)
                        .set("last_notice_round", round)
                        .set("last_notice_at", at)
                        .set("update_time", new Date()));
                if (rows > 0) {
                    outcome.setReminded(outcome.getReminded() + 1);
                }
            } catch (RuntimeException e) {
                // 单条提遍失败跳过继续，不堵全场
            }
        }

        // 三、交代：空轮答一声本轮无件，别报成故障；跑完顺带出一屏当轮看板
        boolean idle = outcome.getDispatched() == 0 && outcome.getReminded() == 0
                && outcome.getSuspended() == 0 && outcome.getManual() == 0;
        outcome.setNote(idle ? ToponCheckRound.NOTE_EMPTY : ToponCheckRound.NOTE_DONE);
        outcome.setBoard(board(at));
        return outcome;
    }

    @Override
    public List<ToponCheckRound.Board> board(Date at) {
        // 当轮格局：截至该时刻在册条目按所属区摊开，已派/未派/挂起各多少
        QueryWrapper<TToponCheckTask> wrapper = new QueryWrapper<TToponCheckTask>();
        wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        wrapper.select("area_name", "status");
        List<TToponCheckTask> rows = this.toponCheckTaskMapper.selectList(wrapper);
        Map<String, ToponCheckRound.Board> byArea = new TreeMap<String, ToponCheckRound.Board>();
        for (TToponCheckTask r : rows) {
            String area = isBlank(r.getAreaName()) ? AREA_UNSET : r.getAreaName().trim();
            ToponCheckRound.Board b = byArea.get(area);
            if (b == null) {
                b = new ToponCheckRound.Board();
                b.setAreaName(area);
                byArea.put(area, b);
            }
            Integer st = r.getStatus();
            if (st == null || st == STATUS_WAIT) {
                b.setPending(b.getPending() + 1);
            } else if (st == STATUS_DONE || st == STATUS_ACCEPTED) {
                b.setDispatched(b.getDispatched() + 1);
            } else {
                // 2派不出去、3转人工，都归挂起这一桶
                b.setSuspended(b.getSuspended() + 1);
            }
        }
        return new ArrayList<ToponCheckRound.Board>(byArea.values());
    }

    /** 在册（含 del_flag 为 NULL 的存量老数据）且已派待接、还没接话的条目 */
    private List<TToponCheckTask> listWaitingAccept() {
        QueryWrapper<TToponCheckTask> wrapper = new QueryWrapper<TToponCheckTask>();
        wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        wrapper.eq("status", STATUS_DONE);
        wrapper.orderByAsc("id");
        return this.toponCheckTaskMapper.selectList(wrapper);
    }

    /** 外业队名册与在手几条：在册条目上落过名的养护员都在册；待派+已派待接即压在手上的未办结 */
    private Map<String, Integer> rosterLoads() {
        QueryWrapper<TToponCheckTask> wrapper = new QueryWrapper<TToponCheckTask>();
        wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        wrapper.isNotNull("assignee");
        wrapper.ne("assignee", "");
        wrapper.select("assignee", "status");
        List<TToponCheckTask> rows = this.toponCheckTaskMapper.selectList(wrapper);
        Map<String, Integer> loads = new LinkedHashMap<String, Integer>();
        for (TToponCheckTask r : rows) {
            String a = r.getAssignee();
            if (isBlank(a)) {
                continue;
            }
            a = a.trim();
            if (!loads.containsKey(a)) {
                loads.put(a, 0);
            }
            Integer st = r.getStatus();
            if (st == null || st == STATUS_WAIT || st == STATUS_DONE) {
                loads.merge(a, 1, Integer::sum);
            }
        }
        return loads;
    }

    /** 手上松的先接；一般松的按名序，求个落定 */
    private static String pickFreest(Map<String, Integer> inHand) {
        String best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : inHand.entrySet()) {
            int load = e.getValue() == null ? 0 : e.getValue();
            if (load < bestLoad || (load == bestLoad && best != null && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestLoad = load;
            }
        }
        return best;
    }

    /** 挂起写明原因（以待派为前提，抢不到就当已被别处处置） */
    private int suspend(TToponCheckTask r, String reason) {
        return this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                .eq("id", r.getId())
                .and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .set("status", STATUS_FAIL)
                .set("remark", appendRemark(r.getRemark(), reason))
                .set("update_time", new Date()));
    }
}
