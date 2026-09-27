package com.fc.v2.common.quartz.task;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fc.v2.model.custom.DispatchRoundReport;
import com.fc.v2.service.ITToponCheckTaskService;

/**
 * 派工摊派的「周期执行」门：Quartz 经 JobInvokeUtil 反射调本 bean 的无参方法，
 * 整套执行规矩全部落在服务层（ITToponCheckTaskService.runOnce），门里不许夹带判断。
 *
 * <p>配套种子（doc/schema/topon_seed_v2.sql）：
 * invoke_target = checkDispatchTask.runRound()，cron = 0 0/30 8-17 * * ?（30 分钟一轮，
 * 8:00 那声落在外业窗外，服务自会按下不表），禁止并发。
 *
 * @author fuce
 * @date 2026-09-16
 */
@Component("checkDispatchTask")
public class CheckDispatchTask {

    private static final Logger log = LoggerFactory.getLogger(CheckDispatchTask.class);

    @Resource
    private ITToponCheckTaskService toponCheckTaskService;

    /** 周期执行正门：跑一轮摊派，回话记进服务，异常不吞（交给 Quartz 记失败日志） */
    public void runRound() {
        DispatchRoundReport report = toponCheckTaskService.runOnce();
        log.info("派工摊派一轮：{}", report.getMessage());
    }
}
