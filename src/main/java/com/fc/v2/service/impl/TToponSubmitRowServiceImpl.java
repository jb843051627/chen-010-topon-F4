package com.fc.v2.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponSubmitRowMapper;
import com.fc.v2.model.auto.TToponSubmitRow;
import com.fc.v2.service.ITToponSubmitRowService;

/**
 * 地名数据汇交核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponSubmitRowServiceImpl implements ITToponSubmitRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TToponSubmitRowMapper toponSubmitRowMapper;

    @Override
    public TToponSubmitRow selectTToponSubmitRowById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponSubmitRowMapper.selectById(id);
    }

    /**
     * 整批提交，一个事务、一遍循环、同一口径：
     * 空批次返回 0；超过单批上限返回 -1 且整批不入库；
     * 每行保留原册行号；非法行落失败明细，合法行入账；
     * (册号, 条目代号) 全批唯一——库内已入账/已登记的、本批重复出现的，都不再重复入库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TToponSubmitRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        String no = batchNo == null ? null : batchNo.trim();
        if (no.isEmpty()) {
            return 0;
        }

        // 册内既已入账的条目代号（含 del_flag 为 NULL 的老数据），重复提交不再入库；
        // 失败行是待修正的明细，不挡修正后重报
        List<TToponSubmitRow> existed = this.toponSubmitRowMapper.selectList(new QueryWrapper<TToponSubmitRow>()
                .eq("batch_no", no)
                .eq("status", STATUS_OK)
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
        Set<String> seenCodes = new HashSet<String>();
        for (TToponSubmitRow e : existed) {
            if (e.getItemCode() != null) {
                seenCodes.add(e.getItemCode());
            }
        }

        int seq = 0;
        int ok = 0;
        for (TToponSubmitRow r : rows) {
            seq++;
            if (r == null) {
                continue;
            }
            r.setBatchNo(no);
            // 行号以原册为准；原册没带行号才按本次提交次序补
            if (r.getRowNo() == null) {
                r.setRowNo(seq);
            }
            r.setDelFlag(0);

            boolean valid = r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                    && r.getQty() != null
                    && r.getQty().compareTo(java.math.BigDecimal.ZERO) > 0;

            if (!valid) {
                r.setStatus(STATUS_FAIL);
                this.toponSubmitRowMapper.insert(r);
                continue;
            }
            if (!seenCodes.add(r.getItemCode())) {
                // 库内已入账或本批已处理过同一代号：跳过，不重复入账，也不算失败
                continue;
            }
            r.setStatus(STATUS_OK);
            this.toponSubmitRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<TToponSubmitRow> listErrors(String batchNo) {
        return this.toponSubmitRowMapper.selectList(new QueryWrapper<TToponSubmitRow>()
                .eq("batch_no", batchNo)
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .eq("status", STATUS_FAIL)
                .orderByAsc("row_no"));
    }
}
