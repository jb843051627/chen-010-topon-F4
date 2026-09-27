package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.DispatchRoundReport;
import com.fc.v2.model.custom.HangItem;
import com.fc.v2.model.custom.RegionBoard;
import com.fc.v2.service.ITToponCheckTaskService;
import com.fc.v2.service.ITSysUserService;

/**
 * 定期检验派发条目 Service业务层处理（scheduling-job 形状：周期执行，无增删改查入口）。
 *
 * <p>每轮规矩（与 doc/schema/topon.sql 注释同一把尺，挑单与落派 CAS 谓词一致）：
 * <ol>
 *   <li>只在外业时段 [08:30,17:30] 动笔，窗外只读看板、按下不表；</li>
 *   <li>应检时刻一律按当日最后一刻往回数（老条目只填日子先抬到 23:59:59），
 *       余下自然日 ≤ 提前天数（整日相减）即该派，当天余 0 日照算；</li>
 *   <li>照在册养护员「在手几条」摊派（在手 = 已派记号在自己名下的存量），
 *       手上松的先接，id 升序破平；派出去即打记号，下轮不当新单重派；</li>
 *   <li>没接话的隔一轮再提（节奏闸 45 分钟），第三遍仍无应转科里人工，系统住口；</li>
 *   <li>调度重入导致同日第三句重复声算毛病：闸下、只计数不出声；</li>
 *   <li>段空 / 原定人离册 / 口径缺失的单挂起写明缘由，不堵全场；缘由消除自动回轮。</li>
 * </ol>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponCheckTaskServiceImpl implements ITToponCheckTaskService {

    private static final Logger log = LoggerFactory.getLogger(TToponCheckTaskServiceImpl.class);

    /** 条目情形 0待派 1已派(打记号) 2派不出去(提满三声转人工) 3挂起 */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DISPATCHED = 1;
    private static final int STATUS_MANUAL = 2;
    private static final int STATUS_HUNG = 3;

    /** 提示遍数封顶：第三遍仍无应即转人工 */
    private static final int MAX_NOTIFY = 3;

    private static final String REGION_UNSET = "未分区";

    @javax.annotation.Resource
    private TToponCheckTaskMapper toponCheckTaskMapper;

    @javax.annotation.Resource
    private ITSysUserService sysUserService;

    /** 最近一轮回话（单实例内存持有；volatile 保页面线程可见性） */
    private volatile DispatchRoundReport lastReport;

    // ---------------------------------------------------------------- 封存签名

    @Override
    public TToponCheckTask selectTToponCheckTaskById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponCheckTaskMapper.selectOne(alive(new QueryWrapper<TToponCheckTask>().eq("id", id)));
    }

    @Override
    public List<TToponCheckTask> listDue(Date at) {
        if (at == null || !DispatchClock.inWindow(at)) {
            return new ArrayList<>();
        }
        List<TToponCheckTask> list = this.toponCheckTaskMapper.selectList(
                alive(new QueryWrapper<TToponCheckTask>()).and(w -> w.in("status", STATUS_WAIT, STATUS_DISPATCHED).or().isNull("status")));
        List<TToponCheckTask> due = new ArrayList<>();
        for (TToponCheckTask r : list) {
            // 已接话的单这轮不再处理；口径缺失/该挂起的也不算到期可办
            if (r.getAcknowledgedAt() != null) {
                continue;
            }
            if (r.getDueAt() == null || r.getAmount() == null
                    || r.getDutySegment() == null || r.getDutySegment().trim().isEmpty()) {
                continue;
            }
            if (DispatchClock.isOpen(at, r.getDueAt(), r.getAmount())) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        DispatchRoundReport r = runRound(at);
        // 封存口径：状态落了笔的条数
        return r.getNudged() + r.getReminded() + r.getManual() + r.getHung() + r.getResumed();
    }

    // ---------------------------------------------------------------- 周期执行正门

    @Override
    public DispatchRoundReport runOnce() {
        return runRound(new Date());
    }

    @Override
    public DispatchRoundReport lastReport() {
        return lastReport;
    }

    @Override
    public DispatchRoundReport runRound(Date at) {
        DispatchRoundReport report = doRound(at);
        // 只认服务回话：每轮跑完留一份给看板页，页面不得另算
        this.lastReport = report;
        return report;
    }

    private DispatchRoundReport doRound(Date at) {
        DispatchRoundReport report = new DispatchRoundReport();
        report.setAt(at);
        if (at == null) {
            report.setInWindow(false);
            report.setEmpty(true);
            report.setMessage("本轮无件");
            report.setBoards(new ArrayList<RegionBoard>());
            return report;
        }

        boolean inWindow = DispatchClock.inWindow(at);
        report.setInWindow(inWindow);

        // 窗外：只读看板，一切按下不表，等下一轮
        if (!inWindow) {
            report.setEmpty(true);
            report.setMessage("非外业时段（08:30-17:30），本轮按下不表");
            report.setBoards(buildBoards(at));
            return report;
        }

        Set<Long> maintainerIds = loadMaintainers();

        // 一、挂起恢复扫描：缘由消除的挂单重回轮转，不叫挂起堵全场
        int resumed = resumeScan(at, maintainerIds);

        // 二、推不动的一小撮：口径缺失 / 段空 / 原定人离册，挂起写明缘由
        List<TToponCheckTask> openItems = this.toponCheckTaskMapper.selectList(
                alive(new QueryWrapper<TToponCheckTask>())
                        .and(w -> w.in("status", STATUS_WAIT, STATUS_DISPATCHED).or().isNull("status"))
                        .orderByAsc("due_at", "id"));

        List<TToponCheckTask> actionable = new ArrayList<>();
        int hung = 0;
        List<HangItem> hangs = new ArrayList<>();
        // 在手量：已派记号在各养护员名下的存量（含已接话——单仍压在他手上）
        Map<Long, Integer> load = new HashMap<>();

        for (TToponCheckTask r : openItems) {
            String defect = defectReason(r, maintainerIds);
            if (defect != null) {
                if (casHang(r, defect)) {
                    hung++;
                    hangs.add(new HangItem(r.getId(), r.getItemNo(), r.getRegionName(), defect));
                }
                continue;
            }
            if (r.getStatus() != null && r.getStatus() == STATUS_DISPATCHED) {
                load.merge(r.getOwnerId() == null ? 0L : r.getOwnerId(), 1, Integer::sum);
            }
            actionable.add(r);
        }

        // 三、到期挑单 + 摊派 / 隔轮再提。挑单谓词与落派 CAS 同一把尺（DispatchClock）
        int nudged = 0;
        int reminded = 0;
        int manual = 0;
        int faultBlocked = 0;

        for (TToponCheckTask r : actionable) {
            try {
                if (!DispatchClock.isOpen(at, r.getDueAt(), r.getAmount())) {
                    continue;
                }
                Integer status = r.getStatus() == null ? STATUS_WAIT : r.getStatus();
                if (status == STATUS_WAIT) {
                    Long pick = pickLightest(maintainerIds, load);
                    if (pick == null) {
                        // 无人可摊也是“推不动”：挂起，不堵后面
                        String reason = "在册养护员为空，无人可摊";
                        if (casHang(r, reason)) {
                            hung++;
                            hangs.add(new HangItem(r.getId(), r.getItemNo(), r.getRegionName(), reason));
                        }
                        continue;
                    }
                    if (casFirstDispatch(r, pick, at)) {
                        nudged++;
                        load.merge(pick, 1, Integer::sum);
                    }
                } else if (status == STATUS_DISPATCHED) {
                    // 已接话的不再催；没接话的走隔轮节奏
                    if (r.getAcknowledgedAt() != null) {
                        continue;
                    }
                    if (!DispatchClock.cadenceReady(at, r.getLastNotifyAt())) {
                        // 节奏没到（同轮重入/邻轮连催）不出声；同日已响过两声、这是没隔轮的
                        // 第三句重复话——算毛病，闸下只计数，不许系统就此多嘴
                        boolean sameDayRepeat = r.getLastNotifyAt() != null
                                && DispatchClock.day(r.getLastNotifyAt()).equals(DispatchClock.day(at))
                                && nz(r.getDayNotifyCount()) >= MAX_NOTIFY - 1;
                        if (sameDayRepeat) {
                            faultBlocked++;
                        }
                        continue;
                    }
                    int nextCount = nz(r.getNotifyCount()) + 1;
                    boolean sameDay = r.getLastNotifyAt() != null
                            && DispatchClock.day(r.getLastNotifyAt()).equals(DispatchClock.day(at));
                    int dayCount = sameDay ? nz(r.getDayNotifyCount()) + 1 : 1;
                    if (nextCount >= MAX_NOTIFY) {
                        // 隔轮节奏下的第三遍仍无人应：转科里人工盯着，系统就此住口
                        if (casManual(r, nextCount, dayCount, at)) {
                            manual++;
                            load.merge(r.getOwnerId() == null ? 0L : r.getOwnerId(), -1, Integer::sum);
                        }
                    } else {
                        if (casRemind(r, nextCount, dayCount, at)) {
                            reminded++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                // 单条失败跳过继续，不许一条堵住全场
                log.error("派工条目摊派失败 id={}", r.getId(), e);
            }
        }

        report.setNudged(nudged);
        report.setReminded(reminded);
        report.setManual(manual);
        report.setHung(hung);
        report.setHangs(hangs);
        report.setResumed(resumed);
        report.setFaultBlocked(faultBlocked);

        boolean empty = nudged + reminded + manual + hung + resumed == 0 && faultBlocked == 0;
        report.setEmpty(empty);
        report.setMessage(empty ? "本轮无件"
                : (nudged + reminded + manual + hung + resumed == 0
                        ? "同日重复毛病 " + faultBlocked + " 件已闸下，本轮未派"
                        : "本轮派出 " + nudged + " 件、隔轮再提 " + reminded + " 件、转人工 " + manual
                                + " 件、挂起 " + hung + " 件、恢复 " + resumed + " 件"
                                + (faultBlocked > 0 ? "；同日重复毛病 " + faultBlocked + " 件已闸下" : "")));

        report.setBoards(buildBoards(at));
        return report;
    }

    // ---------------------------------------------------------------- 接话（应答流转）

    @Override
    public boolean acknowledge(Long id, Long ownerId) {
        if (id == null || ownerId == null) {
            return false;
        }
        // 只许接自己名下、已派未接、未转人工未挂起的单；CAS 防重复接话
        int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                .eq("id", id)
                .eq("owner_id", ownerId)
                .eq("status", STATUS_DISPATCHED)
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .isNull("acknowledged_at")
                .set("acknowledged_at", new Date())
                .set("update_time", new Date()));
        return rows > 0;
    }

    // ---------------------------------------------------------------- 挂起 / 恢复

    /** 挂起缘由；无毛病返回 null。段空、原定人离册、口径缺失都属“推不动的一小撮” */
    private String defectReason(TToponCheckTask r, Set<Long> maintainerIds) {
        List<String> reasons = new ArrayList<>();
        if (r.getDueAt() == null) {
            reasons.add("应检日期缺失");
        }
        if (r.getAmount() == null) {
            reasons.add("提前启口天数缺失");
        }
        if (r.getDutySegment() == null || r.getDutySegment().trim().isEmpty()) {
            reasons.add("养护责任段空缺");
        }
        // owner 是派出去打的记号；只有老单带着记号、而人已离册才算毛病，空记号待摊派
        if (r.getOwnerId() != null && !maintainerIds.contains(r.getOwnerId())) {
            reasons.add("原定养护员已离册");
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    private boolean casHang(TToponCheckTask r, String reason) {
        int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                .eq("id", r.getId())
                .and(w -> w.in("status", STATUS_WAIT, STATUS_DISPATCHED).or().isNull("status"))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .set("status", STATUS_HUNG)
                .set("hang_reason", reason)
                .set("update_time", new Date()));
        if (rows > 0) {
            r.setStatus(STATUS_HUNG);
            r.setHangReason(reason);
        }
        return rows > 0;
    }

    /** 挂单缘由消除即回轮转：置回待派、清缘由/轮次；离册人的记号抹掉，等系统按在手量重摊 */
    private int resumeScan(Date at, Set<Long> maintainerIds) {
        int resumed = 0;
        List<TToponCheckTask> hung = this.toponCheckTaskMapper.selectList(
                alive(new QueryWrapper<TToponCheckTask>().eq("status", STATUS_HUNG)));
        for (TToponCheckTask r : hung) {
            if (defectReason(r, maintainerIds) != null) {
                continue;
            }
            Long owner = maintainerIds.contains(r.getOwnerId()) ? r.getOwnerId() : null;
            int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                    .eq("id", r.getId())
                    .eq("status", STATUS_HUNG)
                    .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                    .set("status", STATUS_WAIT)
                    .set("owner_id", owner)
                    .set("notify_count", 0)
                    .set("day_notify_count", 0)
                    .set("last_notify_at", null)
                    .set("hang_reason", null)
                    .set("update_time", at));
            if (rows > 0) {
                resumed++;
                log.info("挂起条目恢复轮转 id={} itemNo={}", r.getId(), r.getItemNo());
            }
        }
        return resumed;
    }

    // ---------------------------------------------------------------- 摊派落笔（CAS）

    private boolean casFirstDispatch(TToponCheckTask r, Long ownerId, Date at) {
        // 以待派为前提：同轮重入只可能有一处抢到，派过即打记号，下轮不重派
        int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                .eq("id", r.getId())
                .and(w -> w.eq("status", STATUS_WAIT).or().isNull("status"))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .set("status", STATUS_DISPATCHED)
                .set("owner_id", ownerId)
                .set("notify_count", 1)
                .set("day_notify_count", 1)
                .set("last_notify_at", at)
                .set("update_time", at));
        if (rows > 0) {
            r.setStatus(STATUS_DISPATCHED);
            r.setOwnerId(ownerId);
            r.setNotifyCount(1);
            r.setDayNotifyCount(1);
            r.setLastNotifyAt(at);
        }
        return rows > 0;
    }

    private boolean casRemind(TToponCheckTask r, int nextCount, int dayCount, Date at) {
        // 以旧遍数/旧提示时刻/未接话为前提：重复触发抢不到旧值，第三句重复声闸在这里
        Integer oldCount = nz(r.getNotifyCount());
        int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                .eq("id", r.getId())
                .eq("status", STATUS_DISPATCHED)
                .eq("notify_count", oldCount)
                .eq("owner_id", r.getOwnerId())
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .isNull("acknowledged_at")
                .set("notify_count", nextCount)
                .set("day_notify_count", dayCount)
                .set("last_notify_at", at)
                .set("update_time", at));
        if (rows > 0) {
            r.setNotifyCount(nextCount);
            r.setDayNotifyCount(dayCount);
            r.setLastNotifyAt(at);
        }
        return rows > 0;
    }

    private boolean casManual(TToponCheckTask r, int nextCount, int dayCount, Date at) {
        Integer oldCount = nz(r.getNotifyCount());
        int rows = this.toponCheckTaskMapper.update(null, new UpdateWrapper<TToponCheckTask>()
                .eq("id", r.getId())
                .eq("status", STATUS_DISPATCHED)
                .eq("notify_count", oldCount)
                .eq("owner_id", r.getOwnerId())
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .isNull("acknowledged_at")
                .set("status", STATUS_MANUAL)
                .set("notify_count", nextCount)
                .set("day_notify_count", dayCount)
                .set("last_notify_at", at)
                .set("hang_reason", null)
                .set("update_time", at));
        if (rows > 0) {
            r.setStatus(STATUS_MANUAL);
            r.setNotifyCount(nextCount);
            r.setDayNotifyCount(dayCount);
            r.setLastNotifyAt(at);
        }
        return rows > 0;
    }

    // ---------------------------------------------------------------- 在手量摊派

    /** 手上松的先接：在手存量最少者；同量按用户 id 升序，落得定、测得准 */
    private Long pickLightest(Set<Long> maintainerIds, Map<Long, Integer> load) {
        if (maintainerIds.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(maintainerIds);
        Collections.sort(sorted);
        Long pick = sorted.get(0);
        int min = load.getOrDefault(pick, 0);
        for (Long uid : sorted) {
            int n = load.getOrDefault(uid, 0);
            if (n < min) {
                min = n;
                pick = uid;
            }
        }
        return pick;
    }

    private Set<Long> loadMaintainers() {
        try {
            List<TSysUser> users = this.sysUserService.selectTSysUserList(new QueryWrapper<TSysUser>());
            Set<Long> ids = new HashSet<>();
            if (users != null) {
                for (TSysUser u : users) {
                    if (u != null && u.getId() != null) {
                        ids.add(u.getId());
                    }
                }
            }
            return ids;
        } catch (RuntimeException e) {
            // 用户册读不动不阻断整轮：候选池按空处理，待派单会以“无人可摊”挂起而非全场静默
            log.error("养护员名册读取失败", e);
            return new HashSet<>();
        }
    }

    // ---------------------------------------------------------------- 当轮看板

    /** 跑完那一刻各区当日（应检日 = 本轮日）的已派/未派/挂起，只报当轮格局 */
    private List<RegionBoard> buildBoards(Date at) {
        String today = DispatchClock.day(at);
        List<TToponCheckTask> all = this.toponCheckTaskMapper.selectList(
                alive(new QueryWrapper<TToponCheckTask>()
                        .select("id", "region_name", "status", "due_at")));
        // 区 -> [已派, 未派, 挂起]；TreeMap 区名定序，柱状摆开稳
        Map<String, int[]> agg = new TreeMap<>();
        for (TToponCheckTask r : all) {
            if (r.getDueAt() == null) {
                continue;
            }
            if (!today.equals(DispatchClock.day(DispatchClock.effectiveDue(r.getDueAt())))) {
                continue;
            }
            String region = (r.getRegionName() == null || r.getRegionName().trim().isEmpty())
                    ? REGION_UNSET : r.getRegionName().trim();
            int[] row = agg.computeIfAbsent(region, k -> new int[3]);
            int st = r.getStatus() == null ? STATUS_WAIT : r.getStatus();
            if (st == STATUS_DISPATCHED) {
                row[0]++;
            } else if (st == STATUS_HUNG) {
                row[2]++;
            } else {
                // 待派 / 提满三声转人工，都算“还没派出去”
                row[1]++;
            }
        }
        List<RegionBoard> boards = new ArrayList<>();
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            boards.add(new RegionBoard(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        }
        return boards;
    }

    // ---------------------------------------------------------------- 小工具

    /** 在册（含 del_flag 为 NULL 的迁入老数据），读写两头同一口径 */
    private QueryWrapper<TToponCheckTask> alive(QueryWrapper<TToponCheckTask> wrapper) {
        return wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
