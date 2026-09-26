package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TToponPlateCardMapper;
import com.fc.v2.mapper.auto.TToponDistrictDocMapper;
import com.fc.v2.model.auto.TToponPlateCard;
import com.fc.v2.model.auto.TToponDistrictDoc;
import com.fc.v2.service.ITToponPlateCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 门楼牌号段建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TToponPlateCardServiceImpl extends ServiceImpl<TToponPlateCardMapper, TToponPlateCard> implements ITToponPlateCardService {

    /** 进展 0待核对 1已核对 2已定档（与 schema 同口径），定档即锁死 */
    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_FIXED = 2;
    private static final int DEL_DELETED = 1;

    @Autowired
    private TToponDistrictDocMapper toponDistrictDocMapper;

    /** 在册（含 del_flag 为 NULL 的存量老数据），明细页、台账、查重、删改四处同一口径 */
    private QueryWrapper<TToponPlateCard> alive(QueryWrapper<TToponPlateCard> wrapper) {
        return wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
    }

    private UpdateWrapper<TToponPlateCard> alive(UpdateWrapper<TToponPlateCard> wrapper) {
        return wrapper.and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
    }

    /** 可改可删：在册且未定档（status 缺省视为草稿） */
    private UpdateWrapper<TToponPlateCard> editable(UpdateWrapper<TToponPlateCard> wrapper) {
        return alive(wrapper).and(w -> w.ne("status", STATUS_FIXED).or().isNull("status"));
    }

    @Override
    public TToponPlateCard selectTToponPlateCardById(Long id) {
        if (id == null) {
            return null;
        }
        return this.baseMapper.selectOne(alive(new QueryWrapper<TToponPlateCard>().eq("id", id)));
    }

    @Override
    public List<TToponPlateCard> selectTToponPlateCardList(Wrapper<TToponPlateCard> queryWrapper) {
        // 分页与过滤条件一律以调用方传入的 Wrapper 为准，本处不再另起一本账（不硬编码分页、不夹 status=0）
        QueryWrapper<TToponPlateCard> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TToponPlateCard>) queryWrapper
                : new QueryWrapper<TToponPlateCard>();
        return this.baseMapper.selectList(alive(wrapper));
    }

    @Override
    public int insertTToponPlateCard(TToponPlateCard record) {
        if (record == null || record.getSiteId() == null) {
            return 0;
        }

        // 档案只取这一本：未删、在用；阈值与 siteNo 同源，不再一处查状态、一处裸取主键
        TToponDistrictDoc refArch = toponDistrictDocMapper.selectOne(new QueryWrapper<TToponDistrictDoc>()
                .eq("id", record.getSiteId())
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                .and(w -> w.eq("status", 0).or().isNull("status")));
        if (refArch == null) {
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(alive(new QueryWrapper<TToponPlateCard>()
                    .eq("bill_no", record.getBillNo())));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setGradeLevel(gradeLevelOf(refArch, record.getQty()));

        // 新卡一律待核对起手；状态与删除标记不许由新增请求越权指定
        record.setStatus(STATUS_DRAFT);
        record.setDelFlag(0);
        // createBy/createTime 走 MetaObjectHandler 自动填充，不得拿卡号顶替创建人
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTToponPlateCard(TToponPlateCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TToponPlateCard existed = selectTToponPlateCardById(record.getId());
        if (existed == null) {
            return 0;
        }
        // 已定档即锁死，编辑不得再改内容
        if (existed.getStatus() != null && existed.getStatus() == STATUS_FIXED) {
            return 0;
        }

        // 进展与删除标记只准走服务端动作，编辑请求体不得越权带改
        record.setStatus(existed.getStatus());
        record.setDelFlag(existed.getDelFlag());

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(alive(new QueryWrapper<TToponPlateCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId())));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        // 容量档随所属档案阈值联动（档案、卡号两头只取一本档案，口径与界线分档一致：等于上限归本档）
        Integer siteId = record.getSiteId() == null ? existed.getSiteId() : record.getSiteId();
        BigDecimal qty = record.getQty() == null ? existed.getQty() : record.getQty();
        TToponDistrictDoc refArch = null;
        if (siteId != null) {
            refArch = toponDistrictDocMapper.selectOne(new QueryWrapper<TToponDistrictDoc>()
                    .eq("id", siteId)
                    .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                    .and(w -> w.eq("status", 0).or().isNull("status")));
        }
        // 所属档案不存在或已撤并：整笔拒绝，不许静默写回旧区划
        if (refArch == null) {
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        record.setGradeLevel(gradeLevelOf(refArch, qty));

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, editable(new UpdateWrapper<TToponPlateCard>()
                .eq("id", record.getId())));
    }

    @Override
    public int deleteTToponPlateCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        int rows = 0;
        for (Long id : idArr) {
            rows += deleteTToponPlateCardById(id);
        }
        return rows;
    }

    @Override
    public int deleteTToponPlateCardById(Long id) {
        if (id == null) {
            return 0;
        }
        // 逻辑删、不做物理抹去；已定档锁死不得删
        return this.baseMapper.update(null, editable(new UpdateWrapper<TToponPlateCard>()
                .eq("id", id))
                .set("del_flag", DEL_DELETED)
                .set("update_time", new Date()));
    }

    /**
     * 容量分档：等于上限归本档（与界线 rule-eval 的 levelOf 同一把尺）；
     * 阈值缺省视为该档不设限，数量为空则不判定（0 档）。
     */
    private Integer gradeLevelOf(TToponDistrictDoc arch, BigDecimal qty) {
        if (qty == null) {
            return 0;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) <= 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) <= 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
