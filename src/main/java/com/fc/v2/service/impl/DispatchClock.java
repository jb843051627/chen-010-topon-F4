package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * 派工摊派的唯一一把尺：窗口、日终、启口线、轮距全在这里算，挑单与落派都只准用这一处。
 *
 * <p>口径（与 doc/schema/topon.sql 注释逐字一致）：
 * <ul>
 *   <li>一律按北京墙钟（GMT+8）比时刻，不随部署机时区漂移；</li>
 *   <li>外业只认 [08:30,17:30]，两端都算窗内；</li>
 *   <li>应检之期记到当天最后一刻；早年只填日子（00:00:00）的迁入条目抬到 23:59:59，
 *       新老单同一个说法；</li>
 *   <li>提前 amount 个自然日启口：启口线 = 有效应检时刻往回 amount 天，压线也算该派；
 *       压到应检当天当然算该派，只有一种说法；</li>
 *   <li>提示隔一轮再提：调度按 30 分钟一轮，隔一轮即相距 60 分钟；留半轮容差，
 *       相距不足一个半轮（45 分钟）不许再出声，防止同一轮/邻轮翻旧账。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-16
 */
final class DispatchClock {

    static final TimeZone ZONE = TimeZone.getTimeZone("GMT+8");
    static final String WALL = "yyyy-MM-dd HH:mm:ss";
    static final String DAY = "yyyy-MM-dd";

    /** 一轮的间隔（与 t_sys_quartz_job 种子 cron 对齐：0 0/30 8-17 * * ?） */
    static final long ROUND_GAP_MS = 30L * 60L * 1000L;
    /** 隔一轮 = 两个轮距；留半轮容差 */
    static final long REPEAT_GATE_MS = ROUND_GAP_MS + ROUND_GAP_MS / 2L;

    static final int WINDOW_FROM_HH = 8;
    static final int WINDOW_FROM_MM = 30;
    static final int WINDOW_TO_HH = 17;
    static final int WINDOW_TO_MM = 30;

    private DispatchClock() {
    }

    static SimpleDateFormat wallFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat(WALL);
        sdf.setTimeZone(ZONE);
        sdf.setLenient(false);
        return sdf;
    }

    static SimpleDateFormat dayFormat() {
        SimpleDateFormat sdf = new SimpleDateFormat(DAY);
        sdf.setTimeZone(ZONE);
        sdf.setLenient(false);
        return sdf;
    }

    /** 测试/种子便捷：按北京墙钟解析 "yyyy-MM-dd HH:mm:ss" */
    static Date wall(String s) {
        try {
            return wallFormat().parse(s);
        } catch (ParseException e) {
            throw new IllegalArgumentException("坏时刻: " + s, e);
        }
    }

    static String wall(Date d) {
        return wallFormat().format(d);
    }

    static String day(Date d) {
        return dayFormat().format(d);
    }

    private static Calendar calendar(Date d) {
        Calendar c = Calendar.getInstance(ZONE);
        c.setLenient(false);
        c.setTime(d);
        return c;
    }

    /** at 是否落在外业时段 [08:30,17:30]（两端那一秒都算窗内） */
    static boolean inWindow(Date at) {
        if (at == null) {
            return false;
        }
        long t = at.getTime();
        return t >= windowBound(at, WINDOW_FROM_HH, WINDOW_FROM_MM)
                && t <= windowBound(at, WINDOW_TO_HH, WINDOW_TO_MM);
    }

    /** at 所在北京日的 hh:mm:00.000 墙钟时刻 */
    private static long windowBound(Date at, int hh, int mm) {
        Calendar c = calendar(at);
        c.set(Calendar.HOUR_OF_DAY, hh);
        c.set(Calendar.MINUTE, mm);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 有效应检时刻：只填了日子（北京墙钟 00:00:00）的老条目抬到当日最后一刻；
     * 其余原样。null 原样返回。
     */
    static Date effectiveDue(Date dueAt) {
        if (dueAt == null) {
            return null;
        }
        Calendar c = calendar(dueAt);
        boolean midnight = c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0
                && c.get(Calendar.SECOND) == 0 && c.get(Calendar.MILLISECOND) == 0;
        if (!midnight) {
            return dueAt;
        }
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    /**
     * 唯一的“到没到该派”说法：余下自然日 = 应检日（北京日）− 当日（北京日），
     * 余下自然日 ≤ 提前天数即该派。压到应检当天余下 0 日，无论提前量多少都算该派；
     * 启口那一天一进外业窗就算（窗本身另有 inWindow 闸着）。
     * 老条目只填日子先经 {@link #effectiveDue} 归一，新老同一个说法。
     */
    static boolean isOpen(Date at, Date dueAt, BigDecimal amount) {
        if (at == null || dueAt == null || amount == null) {
            return false;
        }
        // 提前天数不许为负；负的是坏口径，交挂起处理，不在这里偷偷放开
        if (amount.signum() < 0) {
            return false;
        }
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(
                localDate(at), localDate(effectiveDue(dueAt)));
        return BigDecimal.valueOf(remainingDays).compareTo(amount) <= 0;
    }

    private static java.time.LocalDate localDate(Date d) {
        return d.toInstant().atZone(ZONE.toZoneId()).toLocalDate();
    }

    /** 隔一轮 gate：没提过可提；距上一遍至少一个半轮（45 分钟）才可再提 */
    static boolean cadenceReady(Date at, Date lastNotifyAt) {
        if (lastNotifyAt == null) {
            return true;
        }
        return at.getTime() - lastNotifyAt.getTime() >= REPEAT_GATE_MS;
    }
}
