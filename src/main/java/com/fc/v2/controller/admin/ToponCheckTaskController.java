package com.fc.v2.controller.admin;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.mapper.auto.TToponCheckTaskMapper;
import com.fc.v2.model.auto.TToponCheckTask;
import com.fc.v2.model.custom.DispatchRoundReport;
import com.fc.v2.service.ITToponCheckTaskService;
import com.fc.v2.shiro.util.ShiroUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * 定期检验派工摊派 Controller（scheduling-job 形状）。
 *
 * <p>只有只读看板与「接话」应答门：当轮格局一概认服务回话（lastReport），
 * 页面不预先钉死任何数字；手工新增、编辑、删除的门一个不留——单子全由周期执行派发。
 *
 * @author fuce
 * @date 2026-09-16
 */
@Api(value = "定期检验派工摊派")
@Controller
@RequestMapping("/toponCheckTask")
public class ToponCheckTaskController extends BaseController {

    private final String prefix = "admin/toponCheckTask";

    @Resource
    private ITToponCheckTaskService toponCheckTaskService;

    @Resource
    private TToponCheckTaskMapper toponCheckTaskMapper;

    @ApiOperation(value = "摊派看板跳转", notes = "摊派看板跳转")
    @GetMapping("/view")
    @org.apache.shiro.authz.annotation.RequiresPermissions("topon:checkTask:view")
    public String view(ModelMap model) {
        return prefix + "/board";
    }

    @ApiOperation(value = "接办台跳转", notes = "养护员接话 + 挂起台账（只读，无新增编辑）")
    @GetMapping("/inboxView")
    public String inboxView(ModelMap model) {
        return prefix + "/inbox";
    }

    @ApiOperation(value = "挂起台账", notes = "只读：当前挂起条目及缘由，供科里人工盯着")
    @GetMapping("/hung")
    @ResponseBody
    public AjaxResult hung() {
        List<TToponCheckTask> list = toponCheckTaskMapper.selectList(
                new QueryWrapper<TToponCheckTask>()
                        .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                        .eq("status", 3)
                        .orderByAsc("region_name", "id"));
        return AjaxResult.successData(200, list == null ? new ArrayList<>() : list);
    }

    @ApiOperation(value = "最近一轮回话", notes = "当轮格局一概认服务回话，页面不另算")
    @GetMapping("/report")
    @ResponseBody
    public AjaxResult report() {
        DispatchRoundReport report = toponCheckTaskService.lastReport();
        if (report == null) {
            return AjaxResult.success(200, "尚未跑过", null);
        }
        return AjaxResult.success(200, report.getMessage(), report);
    }

    @ApiOperation(value = "我名下在手未接的单", notes = "只读：已派到自己手上、还没接话的条目")
    @GetMapping("/mine")
    @ResponseBody
    public AjaxResult mine() {
        Long uid = ShiroUtils.getUserId();
        List<TToponCheckTask> list = toponCheckTaskMapper.selectList(
                new QueryWrapper<TToponCheckTask>()
                        .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"))
                        .eq("owner_id", uid)
                        .eq("status", 1)
                        .isNull("acknowledged_at")
                        .orderByAsc("due_at", "id"));
        return AjaxResult.successData(200, list == null ? new ArrayList<>() : list);
    }

    @Log(title = "派工摊派养护员接话", action = "acknowledge")
    @ApiOperation(value = "养护员接话", notes = "应答流转，不是新增/编辑门")
    @PostMapping("/acknowledge")
    @ResponseBody
    public AjaxResult acknowledge(Long id) {
        Long uid = ShiroUtils.getUserId();
        boolean ok = toponCheckTaskService.acknowledge(id, uid);
        return ok ? AjaxResult.success("已接话") : AjaxResult.error("接单失败：单子不在你名下，或已接/已转人工");
    }
}
