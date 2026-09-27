package com.fc.v2.model.custom;

import java.io.Serializable;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 当轮看板一行：某区当日的已派 / 未派 / 挂起。
 * 只报当轮格局——每轮跑完由服务回话带出，页面原样摊成柱状，不另作他算。
 *
 * @author fuce
 * @date 2026-09-16
 */
@ApiModel(value = "RegionBoard", description = "当轮区看板一行")
public class RegionBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属区（区名为空的老单归到「未分区」一栏，不丢不并） */
    @ApiModelProperty(value = "所属区")
    private String region;

    /** 当日已派（养护员已接话）条数 */
    @ApiModelProperty(value = "当日已派")
    private int dispatched;

    /** 当日未派（还在轮转 / 待提示 / 待人工）条数 */
    @ApiModelProperty(value = "当日未派")
    private int pending;

    /** 当日挂起条数 */
    @ApiModelProperty(value = "当日挂起")
    private int hung;

    public RegionBoard() {
    }

    public RegionBoard(String region, int dispatched, int pending, int hung) {
        this.region = region;
        this.dispatched = dispatched;
        this.pending = pending;
        this.hung = hung;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getDispatched() {
        return dispatched;
    }

    public void setDispatched(int dispatched) {
        this.dispatched = dispatched;
    }

    public int getPending() {
        return pending;
    }

    public void setPending(int pending) {
        this.pending = pending;
    }

    public int getHung() {
        return hung;
    }

    public void setHung(int hung) {
        this.hung = hung;
    }
}
