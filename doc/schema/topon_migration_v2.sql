-- topon 派工摊派 v2 迁移（chen-010）
-- 旧库 t_topon_check_task 已存在时执行；新装库由 topon.sql 直接建出，勿跑本文件。
-- 各语句幂等说明：MySQL 8 的 ALTER TABLE 不支持 IF NOT EXISTS，重复执行报 1060/1054 属正常，
-- 逐句跳过即可；末句 UPDATE 可任意重复，结果不变。

ALTER TABLE t_topon_check_task
    ADD COLUMN region_name varchar(128) DEFAULT NULL COMMENT '所属区' AFTER amount,
    ADD COLUMN duty_segment varchar(128) DEFAULT NULL COMMENT '养护责任段(空着即挂起)' AFTER region_name,
    ADD COLUMN owner_id bigint DEFAULT NULL COMMENT '原定养护员用户id(人已离册即挂起)' AFTER duty_segment,
    ADD COLUMN notify_count int DEFAULT 0 COMMENT '已向接单人提示遍数(封顶3遍)' AFTER owner_id,
    ADD COLUMN day_notify_count int DEFAULT 0 COMMENT '最近一个提示日里已提示几声(第三声闸为毛病)' AFTER notify_count,
    ADD COLUMN last_notify_at datetime DEFAULT NULL COMMENT '最近一遍提示的时刻(隔一轮再提的尺)' AFTER day_notify_count,
    ADD COLUMN acknowledged_at datetime DEFAULT NULL COMMENT '养护员接话时刻(空=还压在接单人手上)' AFTER last_notify_at,
    ADD COLUMN hang_reason varchar(500) DEFAULT NULL COMMENT '挂起缘由' AFTER acknowledged_at;

-- 老条目 status=2 的旧义是“派不出去”的瞬时失败；新义为“提满三声转人工”。
-- 旧代码从未真正落过该值的在途单（仅异常分支写），统一清回待派，重新进摊派轮次。
UPDATE t_topon_check_task SET status = 0 WHERE status IS NULL OR status = 2;
UPDATE t_topon_check_task SET notify_count = 0 WHERE notify_count IS NULL;

-- 早年从到检表迁进来的条目只填了日子（00:00:00），统一抬到当日最后一刻；
-- 此后挑单、落派都按“最后一刻往回数”，新老单同一把尺，不许各判各的。
UPDATE t_topon_check_task
SET due_at = CONVERT(CONCAT(DATE(due_at), ' 23:59:59'), DATETIME)
WHERE due_at IS NOT NULL AND TIME(due_at) = '00:00:00';
