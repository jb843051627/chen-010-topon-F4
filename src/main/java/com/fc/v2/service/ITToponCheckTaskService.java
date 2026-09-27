package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.DispatchRoundReport;

/**
 * 定期检验派发条目 Service接口（scheduling-job 形状：周期执行，无增删改查入口）。
 *
 * <p>旧签名原样封存（selectTToponCheckTaskById / listDue(Date) / runOnce(Date)）；
 * 摊派轮次的新能耐一律另起重载（runOnce() / runRound(Date) / acknowledge）。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITToponCheckTaskService {

    /** 按主键回查条目 */
    TToponCheckTask selectTToponCheckTaskById(Long id);

    /**
     * 【封存签名】at 时刻本轮应处理的条目：在册 + 未挂起 + 待派/已派待接话 + 外业窗内
     * + 已压过启口线。挑单口径与落派 CAS 同一把尺（DispatchClock）。
     */
    List<TToponCheckTask> listDue(Date at);

    /**
     * 【封存签名】执行一轮（指定时刻），返回**状态落了笔的条数**
     * （首次派出 + 隔轮提醒 + 转人工 + 挂起 + 恢复，凡 CAS 成功一行算一条）。
     */
    int runOnce(Date at);

    /**
     * 周期执行的正门：以当前墙钟跑一轮摊派，整套规矩落在服务层。
     * Quartz 那道门只许薄转到此方法；页面作数与否一概认返回的回话。
     */
    DispatchRoundReport runOnce();

    /**
     * 最近一轮跑完留下的回话（单实例内存持有；没有跑过返回 null）。
     * 看板页只认这一份，页面自身不得预先钉死当轮格局。
     */
    DispatchRoundReport lastReport();

    /**
     * 跑一轮摊派（指定时刻，测试/补数旁路复用，口径与无参正门完全一致）：
     * 挂起扫描与恢复 → 窗外按下不表 → 到期挑单 → 照在手量摊派首派 →
     * 隔轮再提（三声封顶转人工）→ 出当轮各区看板。
     */
    DispatchRoundReport runRound(Date at);

    /**
     * 养护员接话：把已派到自己手上的单标记为已接（acknowledged_at 落时刻）。
     * 这是应答流转，不是新增/编辑门；已接、转人工、挂起、非本人的单一律接不动。
     * 落笔成功返回 true。
     */
    boolean acknowledge(Long id, Long ownerId);
}
