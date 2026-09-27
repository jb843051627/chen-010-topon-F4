package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 派工摊派「跑一轮」的服务回话。
 * 页面不得预先钉死当轮格局——作数与否一概认本回话：本轮派了什么、挂了什么、
 * 转了几笔人工，以及跑完那一刻各区当日的已派/未派/挂起看板，全在这里。
 *
 * @author fuce
 * @date 2026-09-16
 */
@ApiModel(value = "DispatchRoundReport", description = "派工摊派当轮回话")
public class DispatchRoundReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本轮取用的时刻（一切日终倒数、窗口、看板皆以此为准） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "本轮时刻")
    private Date at;

    /** 是否落在外业时段 [08:30,17:30]；窗外本轮一切按下不表 */
    @ApiModelProperty(value = "是否外业时段内")
    private boolean inWindow;

    /** 本轮新提示（派出）条数 */
    @ApiModelProperty(value = "本轮派出条数")
    private int nudged;

    /** 本轮隔轮再提（第二声）条数 */
    @ApiModelProperty(value = "本轮隔轮再提条数")
    private int reminded;

    /** 本轮提满三声转科里人工的条数 */
    @ApiModelProperty(value = "本轮转人工条数")
    private int manual;

    /** 本轮新挂起条数 */
    @ApiModelProperty(value = "本轮新挂起条数")
    private int hung;

    /** 本轮新挂起明细（写明为什么挂） */
    @ApiModelProperty(value = "本轮新挂起明细")
    private List<HangItem> hangs = new ArrayList<>();

    /** 本轮挂起消除、重回轮转的条数 */
    @ApiModelProperty(value = "本轮恢复轮转条数")
    private int resumed;

    /** 被“同日第三声”毛病闸下的条数（只计数、不出声） */
    @ApiModelProperty(value = "同日重复毛病闸下条数")
    private int faultBlocked;

    /** 本轮无件可办（空跑答一声，不是故障） */
    @ApiModelProperty(value = "本轮无件")
    private boolean empty;

    /** 给人看的一句话：非外业时段 / 本轮无件 / 本轮概况 */
    @ApiModelProperty(value = "本轮回话")
    private String message;

    /** 跑完那一刻的各区当日看板（只报当轮格局，旁的事一概不上屏） */
    @ApiModelProperty(value = "各区当日看板")
    private List<RegionBoard> boards;

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }

    public boolean isInWindow() {
        return inWindow;
    }

    public void setInWindow(boolean inWindow) {
        this.inWindow = inWindow;
    }

    public int getNudged() {
        return nudged;
    }

    public void setNudged(int nudged) {
        this.nudged = nudged;
    }

    public int getReminded() {
        return reminded;
    }

    public void setReminded(int reminded) {
        this.reminded = reminded;
    }

    public int getManual() {
        return manual;
    }

    public void setManual(int manual) {
        this.manual = manual;
    }

    public int getHung() {
        return hung;
    }

    public void setHung(int hung) {
        this.hung = hung;
    }

    public List<HangItem> getHangs() {
        return hangs;
    }

    public void setHangs(List<HangItem> hangs) {
        this.hangs = hangs;
    }

    public int getResumed() {
        return resumed;
    }

    public void setResumed(int resumed) {
        this.resumed = resumed;
    }

    public int getFaultBlocked() {
        return faultBlocked;
    }

    public void setFaultBlocked(int faultBlocked) {
        this.faultBlocked = faultBlocked;
    }

    public boolean isEmpty() {
        return empty;
    }

    public void setEmpty(boolean empty) {
        this.empty = empty;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<RegionBoard> getBoards() {
        return boards;
    }

    public void setBoards(List<RegionBoard> boards) {
        this.boards = boards;
    }
}
