package com.fc.v2.common.quartz.task;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.service.ITToponCheckTaskService;

/**
 * 定期检验派发——周期执行门（scheduling-job 形状）
 *
 * <p>这桩活只由调度唤起：quartz 以 {@code toponCheckTaskJob.dispatch()} 登记，
 * 触发的整套执行（时段闸门、摊派、提遍、挂起、看板）一概落在服务层，
 * 本类只递一句话，不揣半点章程。</p>
 *
 * @author fuce
 * @date 2026-09-26
 */
@Component("toponCheckTaskJob")
public class ToponCheckTaskJob {

    @Autowired
    private ITToponCheckTaskService toponCheckTaskService;

    /**
     * 周期执行一轮。轮次由服务层的墙钟轮次桶划定，
     * 同一桶内重复触发视同同一轮，不会重复落派。
     */
    public void dispatch() {
        Date now = new Date();
        this.toponCheckTaskService.runOnce(now, this.toponCheckTaskService.roundOf(now));
    }
}
