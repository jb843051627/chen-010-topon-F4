package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponBoundLineMapper;
import com.fc.v2.model.auto.TToponBoundLine;
import com.fc.v2.service.ITToponBoundLineService;

/**
 * 界线勘定基准档线 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponBoundLineServiceImpl implements ITToponBoundLineService {

    /** 线的情形 0当家 1已让位（与 schema 同口径） */
    private static final int STATUS_ACTIVE = 0;

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TToponBoundLine> PRIORITY_THEN_CODE_DESC = new Comparator<TToponBoundLine>() {
        @Override
        public int compare(TToponBoundLine a, TToponBoundLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TToponBoundLineMapper toponBoundLineMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 占位绑定：
     * JDBC 的 serverTimezone=GMT 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /**
     * 唯一的"可用"口径：未删（含 del_flag 为 NULL 的老数据）+ 当家（status 缺省当家）
     * + at 落在 [eff_start, eff_end)；eff_end 交棒日不含，eff_start 缺省即自古启用。
     * 定位 / 列表 / 单条可用 / 计数四个入口全走这里，不再各信各的。
     */
    private QueryWrapper<TToponBoundLine> effective(QueryWrapper<TToponBoundLine> wrapper, Date at) {
        String now = ts(at);
        wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        wrapper.and(w -> w.eq("status", STATUS_ACTIVE).or().isNull("status"));
        wrapper.apply("(eff_start IS NULL OR eff_start <= {0})", now);
        wrapper.apply("(eff_end IS NULL OR eff_end > {0})", now);
        return wrapper;
    }

    @Override
    public TToponBoundLine selectTToponBoundLineById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponBoundLineMapper.selectById(id);
    }

    @Override
    public List<TToponBoundLine> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TToponBoundLine>();
        }
        List<TToponBoundLine> list = this.toponBoundLineMapper.selectList(
                effective(new QueryWrapper<TToponBoundLine>(), at));
        Collections.sort(list, PRIORITY_THEN_CODE_DESC);
        return list;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        TToponBoundLine rule = findRule(ruleCode, at);
        return rule == null ? 0 : levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null || input.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        List<TToponBoundLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        Integer cnt = this.toponBoundLineMapper.selectCount(
                effective(new QueryWrapper<TToponBoundLine>().eq("id", id), at));
        return cnt != null && cnt > 0;
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        Integer cnt = this.toponBoundLineMapper.selectCount(
                effective(new QueryWrapper<TToponBoundLine>(), at));
        return cnt == null ? 0 : cnt;
    }

    private TToponBoundLine findRule(String ruleCode, Date at) {
        QueryWrapper<TToponBoundLine> wrapper = effective(new QueryWrapper<TToponBoundLine>(), at);
        wrapper.eq("rule_code", ruleCode);
        List<TToponBoundLine> hit = this.toponBoundLineMapper.selectList(wrapper);
        if (hit.isEmpty()) {
            return null;
        }
        // 同代号多版本（窗口理论上不重叠）：优先级、启用日、代号降序，取法确定唯一
        Collections.sort(hit, new Comparator<TToponBoundLine>() {
            @Override
            public int compare(TToponBoundLine a, TToponBoundLine b) {
                int byPriority = PRIORITY_THEN_CODE_DESC.compare(a, b);
                if (byPriority != 0) {
                    return byPriority;
                }
                Date sa = a.getEffStart();
                Date sb = b.getEffStart();
                if (sa == null && sb == null) {
                    return 0;
                }
                if (sa == null) {
                    return 1;
                }
                if (sb == null) {
                    return -1;
                }
                return sb.compareTo(sa);
            }
        });
        return hit.get(0);
    }

    /** 分档：等于上限仍归本档（与建档卡容量档判定同一口径）；阈值缺失视为该档不设限 */
    private int levelOf(TToponBoundLine rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
