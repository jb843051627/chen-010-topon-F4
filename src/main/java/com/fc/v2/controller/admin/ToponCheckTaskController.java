package com.fc.v2.controller.admin;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.service.ITToponCheckTaskService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Date;

/**
 * 定期检验派发条目 Controller（scheduling-job 形状：周期执行 + 当轮看板）
 *
 * <p>派发由「周期执行」唤起，此处只留看板这一扇看屏的门；
 * 手工新增、编辑的门一个不留。屏上摆什么一概认服务回话，页面不预先钉死。</p>
 *
 * @author fuce
 * @date 2026-09-26
 */
@Api(value = "定期检验派发条目")
@Controller
@RequestMapping("/toponCheckTask")
public class ToponCheckTaskController extends BaseController {

    private final String prefix = "admin/toponCheckTask";

    @Autowired
    private ITToponCheckTaskService toponCheckTaskService;

    @ApiOperation(value = "当轮看板跳转", notes = "当轮看板跳转")
    @GetMapping("/view")
    @RequiresPermissions("toponCheckTask:view")
    public String view(ModelMap model) {
        return prefix + "/board";
    }

    @Log(title = "定期检验派发当轮看板", action = "board")
    @ApiOperation(value = "当轮看板", notes = "各区已派/未派/挂起各多少，只报当轮格局")
    @GetMapping("/board")
    @RequiresPermissions("toponCheckTask:board")
    @ResponseBody
    public AjaxResult board() {
        return retobject(200, toponCheckTaskService.board(new Date()));
    }
}
