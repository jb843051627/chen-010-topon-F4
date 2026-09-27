-- topon 派工摊派 v2 上线种子（chen-010）
-- 幂等：INSERT IGNORE。固定主键，重复执行不重复播种。
-- 部署顺序：先跑 topon_migration_v2.sql（老库加列/归一），再跑本文件，然后重启应用——
-- QuartzSchedulerUtil 启动时会把这里的周期任务读进调度器。

-- 一、周期执行门：30 分钟一轮，8:00-17:30 触发（8:00 落在外业窗外，服务按下不表；
-- 8:30 起在窗内，17:30 是窗内最后一轮）。禁止并发(concurrent=1)，同一轮不重入。
-- invoke_target 经 JobInvokeUtil 反射薄转到服务层 runOnce()，判断全在服务里。
INSERT IGNORE INTO t_sys_quartz_job
  (id, job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
VALUES
  (700100000000000001, '派工摊派周期执行', 'TOPON',
   'checkDispatchTask.runRound()', '0 0/30 8-17 * * ?', '3', '1', 0,
   'seed', NOW(), '外业时段每30分钟一轮摊派；窗外由服务层按下不表');

-- 二、菜单：新建「地名界线」顶级目录，下挂「派工摊派看板」与「派工接办台」。
--     看板只认服务回话；接办台只有接话应答与挂起只读台账，没有新增/编辑/删除按钮权限。
INSERT IGNORE INTO t_sys_permission
  (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible,
   create_by, create_time, remark)
VALUES
  (700100000000000100, '地名界线', '地名与界线管理', '', 0, 0, '', 0,
   'layui-icon layui-icon-location', 90, 0, 'seed', NOW(), NULL),
  (700100000000000101, '派工摊派看板', '当轮摊派格局展示', '/toponCheckTask/view', 0,
   700100000000000100, 'topon:checkTask:view', 1, 'layui-icon layui-icon-template-1', 1, 0,
   'seed', NOW(), '只认服务回话；无手工新增编辑门'),
  (700100000000000102, '派工接办台', '养护员接话与挂起台账', '/toponCheckTask/inboxView', 0,
   700100000000000100, '', 1, 'layui-icon layui-icon-notice', 2, 0,
   'seed', NOW(), '只接话，无手工新增编辑门');

-- 三、授权：看板给管理员角色（488243256161730560）；接办台管理员与普通用户角色
--     （488289006124007424）都给——外业养护员要在这接话。
INSERT IGNORE INTO t_sys_permission_role
  (id, role_id, permission_id, create_by, create_time)
VALUES
  (700100000000000200, 488243256161730560, 700100000000000100, 'seed', NOW()),
  (700100000000000201, 488243256161730560, 700100000000000101, 'seed', NOW()),
  (700100000000000202, 488243256161730560, 700100000000000102, 'seed', NOW()),
  (700100000000000203, 488243256161730560, 700100000000000100, 'seed', NOW()),
  (700100000000000204, 488289006124007424, 700100000000000102, 'seed', NOW());
