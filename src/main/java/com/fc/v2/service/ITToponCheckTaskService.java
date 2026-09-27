package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.ToponCheckRound;

/**
 * 定期检验派发条目 Service接口（scheduling-job 形状：周期执行，无增删改查入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITToponCheckTaskService {

    /** 按主键回查条目 */
    TToponCheckTask selectTToponCheckTaskById(Long id);

    /** 该时刻可处理的条目（执行窗口内 + 到期 + 尚未处理） */
    List<TToponCheckTask> listDue(Date at);

    /** 执行一次，返回**成功条数**；单条失败跳过继续 */
    int runOnce(Date at);

    // ---------------------------------------------------------------
    // 2026-09 摊派新能：现成签名原样封存，新能一概另起重载/另立名目
    // ---------------------------------------------------------------

    /**
     * 墙钟轮次：把时刻折进固定的轮次桶。同一桶内重复触发视同同一轮，
     * 这轮派过的条目下轮才许再翻——靠它挡住一轮内的重复落派。
     */
    long roundOf(Date at);

    /**
     * 该时刻该派的条目（挑单）。口径与落派共用同一把尺：
     * 距应检之日余下几日一律从「最后一刻」往回数，压到当天也算该派；
     * 只填日子没填钟点的老数据与系统新单同一个说法。
     *
     * @param workWindowGate true 时外加作业时段闸门（08:30-17:30 之外按下不表）
     */
    List<TToponCheckTask> listDue(Date at, boolean workWindowGate);

    /**
     * 周期执行一轮（调度那头触发的整套执行都落在这里）：
     * 时段闸门 → 照各人在手几条摊派（手上松的先接）→ 隔一轮提遍、
     * 三遍无人应转人工 → 推不动的挂起写明原因 → 顺带出一屏当轮看板。
     * 空轮回一句「本轮无件」，不报故障。
     */
    ToponCheckRound runOnce(Date at, long round);

    /** 当轮看板：各区已派/未派/挂起各多少（只报当轮格局） */
    List<ToponCheckRound.Board> board(Date at);
}
