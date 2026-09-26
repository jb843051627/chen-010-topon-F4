package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.service.ITToponCheckTaskService;

/**
 * 定期检验派发条目 Service业务层处理（scheduling-job 形状：周期执行）
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
}
