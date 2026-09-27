package com.fc.v2.model.custom;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 定期检验派发——一轮周期执行的交代（scheduling-job 形状）
 *
 * <p>每轮跑完给一句准话：空轮是「本轮无件」，不是故障；非作业时段是「按下不表」，等下一轮。
 * 看板只报当轮格局（各区已派/未派/挂起），旁的事一概不上。</p>
 *
 * @author fuce
 * @date 2026-09-26
 */
@ApiModel(value = "ToponCheckRound", description = "定期检验派发一轮执行的交代")
public class ToponCheckRound implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 本轮无件（空轮，非故障） */
    public static final String NOTE_EMPTY = "本轮无件";
    /** 非作业时段，按下不表（等下一轮，非故障） */
    public static final String NOTE_OUT_OF_WINDOW = "非作业时段，本轮按下不表";
    /** 本轮有落手 */
    public static final String NOTE_DONE = "本轮已办结";

    /** 轮次（墙钟轮次桶，同一桶内重复触发不重复落派） */
    @ApiModelProperty(value = "轮次")
    private Long round;

    /** 本轮墙钟时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "本轮墙钟时刻")
    private Date at;

    /** 本轮交代：本轮无件 / 非作业时段按下不表 / 本轮已办结 */
    @ApiModelProperty(value = "本轮交代")
    private String note;

    /** 本轮派出条数 */
    @ApiModelProperty(value = "本轮派出条数")
    private int dispatched;

    /** 本轮提遍条数 */
    @ApiModelProperty(value = "本轮提遍条数")
    private int reminded;

    /** 本轮挂起条数 */
    @ApiModelProperty(value = "本轮挂起条数")
    private int suspended;

    /** 本轮转人工条数 */
    @ApiModelProperty(value = "本轮转人工条数")
    private int manual;

    /** 当轮看板：各区已派/未派/挂起 */
    @ApiModelProperty(value = "当轮看板")
    private List<Board> board = new ArrayList<Board>();

    public Long getRound() {
        return round;
    }

    public void setRound(Long round) {
        this.round = round;
    }

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getDispatched() {
        return dispatched;
    }

    public void setDispatched(int dispatched) {
        this.dispatched = dispatched;
    }

    public int getReminded() {
        return reminded;
    }

    public void setReminded(int reminded) {
        this.reminded = reminded;
    }

    public int getSuspended() {
        return suspended;
    }

    public void setSuspended(int suspended) {
        this.suspended = suspended;
    }

    public int getManual() {
        return manual;
    }

    public void setManual(int manual) {
        this.manual = manual;
    }

    public List<Board> getBoard() {
        return board;
    }

    public void setBoard(List<Board> board) {
        this.board = board;
    }

    /**
     * 当轮看板一行：一个区的已派/未派/挂起
     */
    @ApiModel(value = "ToponCheckRoundBoard", description = "当轮看板一行")
    public static class Board implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 所属区（库里落空的归「未落区」） */
        @ApiModelProperty(value = "所属区")
        private String areaName;

        /** 已派（含已派待接、已接） */
        @ApiModelProperty(value = "已派")
        private long dispatched;

        /** 未派（待派） */
        @ApiModelProperty(value = "未派")
        private long pending;

        /** 挂起（含派不出去、转人工） */
        @ApiModelProperty(value = "挂起")
        private long suspended;

        public String getAreaName() {
            return areaName;
        }

        public void setAreaName(String areaName) {
            this.areaName = areaName;
        }

        public long getDispatched() {
            return dispatched;
        }

        public void setDispatched(long dispatched) {
            this.dispatched = dispatched;
        }

        public long getPending() {
            return pending;
        }

        public void setPending(long pending) {
            this.pending = pending;
        }

        public long getSuspended() {
            return suspended;
        }

        public void setSuspended(long suspended) {
            this.suspended = suspended;
        }
    }
}
