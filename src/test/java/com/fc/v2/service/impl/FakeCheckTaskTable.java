package com.fc.v2.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mockito.Mockito;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;

/**
 * 内存版派工条目 mapper：本机无 MySQL，用它把服务层整套轮次跑在一张内存主表上。
 * 语义解析 MyBatis-Plus wrapper 的 SQL 片段（WHERE 谓词 + SET 列），
 * 与真实 SQL 同形：id / status（单值、IN、IS NULL）/ owner_id / notify_count /
 * acknowledged_at IS NULL / del_flag。
 */
class FakeCheckTaskTable {

    final TToponCheckTaskMapper mapper = Mockito.mock(TToponCheckTaskMapper.class);
    final Map<Long, TToponCheckTask> rows = new LinkedHashMap<>();

    FakeCheckTaskTable() {
        when(mapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TToponCheckTask> w = inv.getArgument(0);
            return select(w.getSqlSegment(), w.getParamNameValuePairs());
        });
        when(mapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TToponCheckTask> w = inv.getArgument(0);
            List<TToponCheckTask> hit = select(w.getSqlSegment(), w.getParamNameValuePairs());
            return hit.isEmpty() ? null : hit.get(0);
        });
        when(mapper.selectById(any())).thenAnswer(inv -> rows.get(inv.getArgument(0)));
        when(mapper.update(any(), any())).thenAnswer(inv -> {
            UpdateWrapper<TToponCheckTask> w = inv.getArgument(1);
            return applyUpdate(w);
        });
    }

    private List<TToponCheckTask> select(String sql, Map<String, Object> p) {
        List<TToponCheckTask> out = new ArrayList<>();
        for (TToponCheckTask r : rows.values()) {
            if (matches(r, sql, p)) {
                out.add(r);
            }
        }
        return out;
    }

    private boolean alive(TToponCheckTask r) {
        return r.getDelFlag() == null || r.getDelFlag() == 0;
    }

    private boolean matches(TToponCheckTask r, String sql, Map<String, Object> p) {
        if (!alive(r)) {
            // wrapper 一律带 (del_flag = 0 OR del_flag IS NULL)
            return false;
        }
        Long idVal = colParam(sql, p, "id", false);
        if (idVal != null && !idVal.equals(r.getId())) {
            return false;
        }
        Long ownerVal = colParam(sql, p, "owner_id", false);
        if (ownerVal != null && !ownerVal.equals(r.getOwnerId())) {
            return false;
        }
        Integer notifyVal = colParamInt(sql, p, "notify_count");
        if (notifyVal != null && !notifyVal.equals(nz(r.getNotifyCount()))) {
            return false;
        }
        // status IN (..) OR status IS NULL
        Matcher in = Pattern.compile("status IN \\(([^)]+)\\)").matcher(sql);
        if (in.find()) {
            List<String> keys = placeholders(in.group(1));
            boolean nullOk = sql.contains("status IN") && sql.contains("OR status IS NULL");
            boolean hit = false;
            for (String k : keys) {
                if (p.get(k) != null && ((Number) p.get(k)).intValue() == nz(r.getStatus())) {
                    hit = true;
                }
            }
            if (!hit && !(nullOk && r.getStatus() == null)) {
                return false;
            }
        } else {
            Integer statusVal = colParamInt(sql, p, "status");
            if (statusVal != null) {
                boolean nullOk = Pattern.compile("status = #\\{[^}]+\\} OR status IS NULL").matcher(sql).find();
                boolean hit = statusVal.equals(nz(r.getStatus())) || (nullOk && r.getStatus() == null);
                if (!hit) {
                    return false;
                }
            }
        }
        if (sql.contains("acknowledged_at IS NULL") && r.getAcknowledgedAt() != null) {
            return false;
        }
        return true;
    }

    private int applyUpdate(UpdateWrapper<TToponCheckTask> w) {
        String sql = w.getSqlSegment();
        String set = w.getSqlSet();
        Map<String, Object> p = w.getParamNameValuePairs();
        int n = 0;
        for (TToponCheckTask r : new ArrayList<>(rows.values())) {
            if (!matches(r, sql, p)) {
                continue;
            }
            for (String part : set.split(",")) {
                Matcher m = Pattern.compile("([a-z_]+)=#\\{[^}]+MPGENVAL(\\d+)}").matcher(part);
                if (!m.find()) {
                    continue;
                }
                String col = m.group(1);
                Object v = p.get("MPGENVAL" + m.group(2));
                setField(r, col, v);
            }
            n++;
        }
        return n;
    }

    private void setField(TToponCheckTask r, String col, Object v) {
        try {
            String camel = Arrays.stream(col.split("_"))
                    .reduce((a, b) -> a + Character.toUpperCase(b.charAt(0)) + b.substring(1)).orElse(col);
            Field f = TToponCheckTask.class.getDeclaredField(camel);
            f.setAccessible(true);
            if (v == null) {
                f.set(r, null);
            } else if (f.getType() == Long.class) {
                f.set(r, ((Number) v).longValue());
            } else if (f.getType() == Integer.class) {
                f.set(r, ((Number) v).intValue());
            } else {
                f.set(r, v);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // update_time 等不影响断言的列可忽略
        }
    }

    private List<String> placeholders(String group) {
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("MPGENVAL(\\d+)").matcher(group);
        while (m.find()) {
            keys.add("MPGENVAL" + m.group(1));
        }
        return keys;
    }

    private Long colParam(String sql, Map<String, Object> p, String col, boolean allowPrefix) {
        Integer i = colParamInt(sql, p, col);
        return i == null ? null : i.longValue();
    }

    private Integer colParamInt(String sql, Map<String, Object> p, String col) {
        Matcher m = Pattern.compile("(?:^|[^a-z_])" + col + " = #\\{[^}]+MPGENVAL(\\d+)}").matcher(sql);
        if (!m.find()) {
            return null;
        }
        Object v = p.get("MPGENVAL" + m.group(1));
        return v == null ? null : ((Number) v).intValue();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // -------------------------------------------------------------- 造数

    static TToponCheckTask row(long id, String itemNo, String due, String amount,
                               String region, String segment, Long owner, Integer status) {
        TToponCheckTask r = new TToponCheckTask();
        r.setId(id);
        r.setItemNo(itemNo);
        if (due != null) {
            r.setDueAt(DispatchClock.wall(due));
        }
        if (amount != null) {
            r.setAmount(new java.math.BigDecimal(amount));
        }
        r.setRegionName(region);
        r.setDutySegment(segment);
        r.setOwnerId(owner);
        r.setStatus(status);
        r.setDelFlag(0);
        r.setNotifyCount(0);
        r.setDayNotifyCount(0);
        return r;
    }
}
