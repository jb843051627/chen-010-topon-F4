package com.fc.v2.model.custom;

import java.io.Serializable;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 本轮新挂起一条：单号、所属区、挂起缘由。
 *
 * @author fuce
 * @date 2026-09-16
 */
@ApiModel(value = "HangItem", description = "本轮新挂起一条")
public class HangItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "条目主键")
    private Long id;

    @ApiModelProperty(value = "派发单号")
    private String itemNo;

    @ApiModelProperty(value = "所属区")
    private String region;

    @ApiModelProperty(value = "挂起缘由")
    private String reason;

    public HangItem() {
    }

    public HangItem(Long id, String itemNo, String region, String reason) {
        this.id = id;
        this.itemNo = itemNo;
        this.region = region;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemNo() {
        return itemNo;
    }

    public void setItemNo(String itemNo) {
        this.itemNo = itemNo;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
