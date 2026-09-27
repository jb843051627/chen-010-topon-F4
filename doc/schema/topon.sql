-- topon 地名与界线管理 -- schema (chen-010)
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_010

CREATE TABLE IF NOT EXISTS t_topon_bound_line (
  id bigint NOT NULL COMMENT '主键',
  rule_code varchar(64) DEFAULT NULL COMMENT '勘定基准代号',
  rule_name varchar(128) DEFAULT NULL COMMENT '勘定基准名称',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '一档桩间平距上限(米)',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '二档桩间平距上限(米)',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '三档桩间平距上限(米)',
  eff_start datetime DEFAULT NULL COMMENT '启用日',
  eff_end datetime DEFAULT NULL COMMENT '交棒日(不含)',
  priority int DEFAULT NULL COMMENT '取用顺位(数值越大越优先)',
  status int DEFAULT NULL COMMENT '线的情形 0当家 1已让位',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='界线勘定基准档线';

-- 派工摊派口径（服务层唯一一把尺，挑单与落派同谓词）：
--   1) due_at 永远表示「本轮应检那一天的最后一刻」(23:59:59)；早年只填日子(00:00:00)的
--      迁入条目由迁移段统一抬到当日 23:59:59，新老单同一个说法，不许各判各的。
--   2) 提前 amount 个自然日启口（amount 列即“自然日数”）：余下自然日 = 应检日 − 当日（皆按
--      北京自然日整日相减），余下自然日 ≤ amount 即该派；启口那个自然日一进外业窗就算，
--      压到应检当天余下 0 日照样算该派，只有一种说法。
--   3) 外业只认八时半到十七时半(含两端)，窗外即便过了启口线也按下不表，等下一轮。
--   4) status：0 待派　1 已派(已打 owner 记号；接没接话看 acknowledged_at)　2 派不出去
--      (提满三声转科里人工，系统住口)　3 挂起。
--   5) owner_id 是「派出去打的记号」：系统首轮挑中谁就记谁（照各人在手几条摊，手上松的先接），
--      之后隔轮再提只催同一人，不另派；早年迁入的老单可能带着已离册人的 id，这类挂起写明缘由。
--   6) 一张单一天里听见第三句重复的话算毛病：day_notify_count 记当日已几声，
--      调度重入/重复触发把第三声闸下，只计数不出声。
CREATE TABLE IF NOT EXISTS t_topon_check_task (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '定期检验派发单号',
  due_at datetime DEFAULT NULL COMMENT '本轮应检之期的日终截止时刻(当日23:59:59)',
  amount decimal(12,2) DEFAULT NULL COMMENT '单条提前派发的自然日数',
  region_name varchar(128) DEFAULT NULL COMMENT '所属区',
  duty_segment varchar(128) DEFAULT NULL COMMENT '养护责任段(空着即挂起)',
  owner_id bigint DEFAULT NULL COMMENT '摊派接走此单的养护员用户id(派出时打下的记号)',
  notify_count int DEFAULT 0 COMMENT '已向接单人提示遍数(封顶3遍)',
  day_notify_count int DEFAULT 0 COMMENT '最近一个提示日里已提示几声(第三声闸为毛病)',
  last_notify_at datetime DEFAULT NULL COMMENT '最近一遍提示的时刻(隔一轮再提的尺)',
  acknowledged_at datetime DEFAULT NULL COMMENT '养护员接话时刻(空=还压在接单人手上)',
  hang_reason varchar(500) DEFAULT NULL COMMENT '挂起缘由',
  status int DEFAULT NULL COMMENT '条目情形 0待派 1已派 2派不出去(转人工) 3挂起',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定期检验派发条目';

CREATE TABLE IF NOT EXISTS t_topon_district_doc (
  id bigint NOT NULL COMMENT '主键',
  site_no varchar(64) DEFAULT NULL COMMENT '乡镇街道区划代码',
  site_name varchar(128) DEFAULT NULL COMMENT '乡镇街道基础档案名称',
  site_type varchar(32) DEFAULT NULL COMMENT '所属地貌类',
  road_name varchar(128) DEFAULT NULL COMMENT '坐落(省—市—区县)',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '号段可编容量一档上限(个)',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '二档上限(个)',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '三档上限(个)',
  status int DEFAULT NULL COMMENT '档案情形 0在用 1已撤并',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='乡镇街道基础档案';

CREATE TABLE IF NOT EXISTS t_topon_marker_card (
  id bigint NOT NULL COMMENT '主键',
  biz_no varchar(64) DEFAULT NULL COMMENT '界桩标志补办事务卡号',
  stage int DEFAULT NULL COMMENT '当前格口 0..5',
  status int DEFAULT NULL COMMENT '卡的落定 0在办 1已成卷 2已封存',
  content varchar(255) DEFAULT NULL COMMENT '勘办记事',
  last_action varchar(64) DEFAULT NULL COMMENT '最近一次挪格动作',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='界桩标志补办事务卡';

CREATE TABLE IF NOT EXISTS t_topon_name_bill (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '命名更名核准单号',
  node_no int DEFAULT NULL COMMENT '当前所在层 0..3',
  sign_mode int DEFAULT NULL COMMENT '同层核准方式 0任一人 1两名点齐',
  need_count int DEFAULT NULL COMMENT '本层应落印人数',
  sign_count int DEFAULT NULL COMMENT '本层已落印人数',
  status int DEFAULT NULL COMMENT '报批情形 0在核 1已核讫 2已打回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='地名命名更名逐级核准单';

CREATE TABLE IF NOT EXISTS t_topon_plate_card (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '门楼牌号段卡号',
  site_id int DEFAULT NULL COMMENT '所属乡镇街道基础档案',
  site_no varchar(64) DEFAULT NULL COMMENT '乡镇街道区划代码',
  qty decimal(12,2) DEFAULT NULL COMMENT '单双侧可编号码总个数(个)',
  fine_amt decimal(12,2) DEFAULT NULL COMMENT '本年度新编号码个数(个)',
  grade_level int DEFAULT NULL COMMENT '号段容量档',
  status int DEFAULT NULL COMMENT '进展 0待核对 1已核对 2已定档',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门楼牌号段建档卡';

CREATE TABLE IF NOT EXISTS t_topon_submit_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '地名数据汇交册号',
  row_no int DEFAULT NULL COMMENT '原册内行次',
  item_code varchar(64) DEFAULT NULL COMMENT '地名条目代号',
  qty decimal(12,2) DEFAULT NULL COMMENT '本行填报地名条目数(条)',
  status int DEFAULT NULL COMMENT '行进展 0待销 1已入账 2挂驳回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='地名数据汇交核收行';

-- 初始档案数据（id=1 启用 / id=2 停用）
-- t_topon_district_doc 两条种子档案：id=0 在用、id=1 已撤并。
-- 号段可编容量上限取 40/200/600 个。
INSERT IGNORE INTO t_topon_district_doc (id, site_no, site_name, site_type, road_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES (0, 'XZ00', '临川市城东区明德街道办事处基础档案', '城镇街区', '江南省—临川市—城东区', 40.00, 200.00, 600.00, 0, 0, 'seed', NOW()),
       (1, 'XZ01', '旧临川市城西乡人民政府基础档案（已撤并）', '毗界地段', '江南省—临川市—城西区', 40.00, 200.00, 600.00, 1, 0, 'seed', NOW());

