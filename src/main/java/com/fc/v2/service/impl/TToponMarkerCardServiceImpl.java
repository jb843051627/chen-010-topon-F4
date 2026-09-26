package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TToponMarkerCardMapper;
import com.fc.v2.model.auto.TToponMarkerCard;
import com.fc.v2.service.ITToponMarkerCardService;

/**
 * 界桩标志补办事务卡 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponMarkerCardServiceImpl implements ITToponMarkerCardService {

    /** 格口 0..5（与 schema stage 注释一致），走到末格即成卷 */
    private static final int MAX_STAGE = 5;
    /** 卡的落定 0在办 1已成卷 2已封存（与 schema 同口径） */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_ARCHIVED = 1;
    private static final int STATUS_SEALED = 2;
    private static final int DEL_DELETED = 1;

    @javax.annotation.Resource
    private TToponMarkerCardMapper toponMarkerCardMapper;

    /** 在册（含 del_flag 为 NULL 的存量老数据）；读写两头同一口径 */
    private QueryWrapper<TToponMarkerCard> alive(QueryWrapper<TToponMarkerCard> wrapper) {
        return wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
    }

    /** 在办（status 缺省视为在办），用于流转/改记事的前提条件 */
    private UpdateWrapper<TToponMarkerCard> running(UpdateWrapper<TToponMarkerCard> wrapper) {
        return wrapper.and(w -> w.eq("status", STATUS_RUNNING).or().isNull("status"));
    }

    @Override
    public TToponMarkerCard selectTToponMarkerCardById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponMarkerCardMapper.selectOne(alive(new QueryWrapper<TToponMarkerCard>().eq("id", id)));
    }

    @Override
    public List<TToponMarkerCard> selectTToponMarkerCardList(QueryWrapper<TToponMarkerCard> queryWrapper) {
        QueryWrapper<TToponMarkerCard> wrapper = queryWrapper == null ? new QueryWrapper<TToponMarkerCard>() : queryWrapper;
        alive(wrapper);
        return this.toponMarkerCardMapper.selectList(wrapper);
    }

    @Override
    public TToponMarkerCard advance(Long id, String remark) {
        if (id == null) {
            return null;
        }
        TToponMarkerCard r = selectTToponMarkerCardById(id);
        if (r == null) {
            return null;
        }
        // 成卷、封存即办结，不得再推进
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        int next = st + 1;
        r.setStage(next);
        r.setStatus(next >= MAX_STAGE ? STATUS_ARCHIVED : STATUS_RUNNING);
        r.setLastAction(remark);
        // 以旧格口为流转前提：连点两回时第二笔抢不到旧格口，不会重复落账
        int rows = this.toponMarkerCardMapper.update(r, running(new UpdateWrapper<TToponMarkerCard>()
                .eq("id", id)
                .eq("stage", st))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
        return rows > 0 ? r : null;
    }

    @Override
    public TToponMarkerCard rollback(Long id, String remark) {
        if (id == null) {
            return null;
        }
        TToponMarkerCard r = selectTToponMarkerCardById(id);
        if (r == null) {
            return null;
        }
        // 封存后不得回退；成卷的卡回退一格即重新打开
        if (r.getStatus() != null && r.getStatus() == STATUS_SEALED) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0) {
            return null;
        }
        int prev = st - 1;
        r.setStage(prev);
        r.setStatus(STATUS_RUNNING);
        r.setLastAction(remark);
        // 同样以旧格口为前提，防止连点连退
        int rows = this.toponMarkerCardMapper.update(r, new UpdateWrapper<TToponMarkerCard>()
                .eq("id", id)
                .eq("stage", st)
                .and(s -> s.ne("status", STATUS_SEALED).or().isNull("status"))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
        return rows > 0 ? r : null;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        if (id == null) {
            return false;
        }
        TToponMarkerCard r = selectTToponMarkerCardById(id);
        if (r == null) {
            return false;
        }
        // 成卷/封存后内容锁死
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return false;
        }
        r.setContent(remark);
        int rows = this.toponMarkerCardMapper.update(r, running(new UpdateWrapper<TToponMarkerCard>()
                .eq("id", id))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
        return rows > 0;
    }

    @Override
    public boolean remove(Long id) {
        if (id == null) {
            return false;
        }
        TToponMarkerCard r = selectTToponMarkerCardById(id);
        if (r == null) {
            return false;
        }
        // 成卷/封存不得删除；其余逻辑删，不做物理抹去
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return false;
        }
        int rows = this.toponMarkerCardMapper.update(null, running(new UpdateWrapper<TToponMarkerCard>()
                .eq("id", id))
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .set("del_flag", DEL_DELETED));
        return rows > 0;
    }

}
