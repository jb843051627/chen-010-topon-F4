package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponNameBillMapper;
import com.fc.v2.model.auto.TToponNameBill;
import com.fc.v2.service.ITToponNameBillService;

/**
 * 地名命名更名逐级核准单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponNameBillServiceImpl implements ITToponNameBillService {

    /** 层 0..3（与 schema node_no 注释一致），末层点齐即核讫 */
    private static final int MAX_NODE = 3;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TToponNameBillMapper toponNameBillMapper;

    /** 在核（status 缺省视为在核），作为一切签批动作的前提 */
    private UpdateWrapper<TToponNameBill> running(UpdateWrapper<TToponNameBill> wrapper) {
        return wrapper.and(w -> w.eq("status", STATUS_RUNNING).or().isNull("status"));
    }

    @Override
    public TToponNameBill selectTToponNameBillById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponNameBillMapper.selectById(id);
    }

    @Override
    public TToponNameBill approve(Long id, String approver, String comment) {
        if (id == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TToponNameBill r = this.toponNameBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        int mode = r.getSignMode() == null ? MODE_OR : r.getSignMode();
        int need;
        if (r.getNeedCount() != null && r.getNeedCount() > 0) {
            need = r.getNeedCount();
        } else {
            need = mode == MODE_AND ? 2 : 1;
        }
        int signed = r.getSignCount() == null ? 0 : r.getSignCount();
        if (signed >= need) {
            return null;
        }
        int now = signed + 1;
        if (now < need) {
            // 本层票未点齐：只累加落印数，仍停在本层，不得窜层
            r.setSignCount(now);
            int rows = this.toponNameBillMapper.update(r, running(new UpdateWrapper<TToponNameBill>()
                    .eq("id", id)
                    .eq("node_no", node)
                    .eq("sign_count", signed)));
            return rows > 0 ? r : null;
        }
        // 本层点齐：落印数清零后进下一层；末层点齐则核讫
        r.setSignCount(0);
        if (node >= MAX_NODE) {
            r.setStatus(STATUS_PASS);
        } else {
            r.setNodeNo(node + 1);
            r.setStatus(STATUS_RUNNING);
        }
        // 以旧层、旧票数为前提，防连点/并发把同一票记成两笔
        int rows = this.toponNameBillMapper.update(r, running(new UpdateWrapper<TToponNameBill>()
                .eq("id", id)
                .eq("node_no", node)
                .eq("sign_count", signed)));
        return rows > 0 ? r : null;
    }

    @Override
    public TToponNameBill reject(Long id, String approver, String comment) {
        if (id == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TToponNameBill r = this.toponNameBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        int signed = r.getSignCount() == null ? 0 : r.getSignCount();
        r.setStatus(STATUS_VETO);
        if (comment != null) {
            r.setRemark(comment);
        }
        int rows = this.toponNameBillMapper.update(r, running(new UpdateWrapper<TToponNameBill>()
                .eq("id", id)
                .eq("node_no", node)
                .eq("sign_count", signed)));
        return rows > 0 ? r : null;
    }

    @Override
    public TToponNameBill rollback(Long id, String comment) {
        if (id == null) {
            return null;
        }
        TToponNameBill r = this.toponNameBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        // 已核讫封死，不得回退；在核、已打回可退
        if (r.getStatus() != null && r.getStatus() == STATUS_PASS) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node <= 0) {
            return null;
        }
        r.setNodeNo(node - 1);
        r.setSignCount(0);
        r.setStatus(STATUS_RUNNING);
        // 以旧层为前提防连点；status 缺省（老数据）也可退
        int rows = this.toponNameBillMapper.update(r, new UpdateWrapper<TToponNameBill>()
                .eq("id", id)
                .eq("node_no", node)
                .and(w -> w.ne("status", STATUS_PASS).or().isNull("status")));
        return rows > 0 ? r : null;
    }
}
