package com.workorder.task;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分区表自动维护任务（W-39）
 *
 * <p>每月 1 日凌晨 01:00 执行，为三张按月分区表（approval_log / sys_security_audit_log /
 * work_order_comment）创建下个月分区，避免数据涌入 DEFAULT 分区。
 *
 * <p>多实例防护：复用 PG 事务级 advisory lock（pg_advisory_xact_lock），事务提交后自动释放；
 * 分区 SQL 全部幂等（to_regclass 检测后按需 CREATE TABLE）。分区表清单与命名约定与
 * V7/V12 迁移保持一致。
 *
 * @author KLord
 */
@Component
public class PartitionMaintenanceTask {

  private static final Logger logger = LoggerFactory.getLogger(PartitionMaintenanceTask.class);

  /** W-39：分区维护任务全局分布式锁 key（多实例互斥，避免并发重复建分区） */
  private static final long PARTITION_MAINTENANCE_LOCK_KEY = 7000000002L;

  /** 按月分区的分区表清单（与 V7/V12 迁移保持一致） */
  private static final String[] PARTITIONED_TABLES = {
      "approval_log", "sys_security_audit_log", "work_order_comment"
  };

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 每月 1 日凌晨 01:00 执行，创建下个月分区（SQL 幂等，重复执行跳过已存在分区）。 */
  @Scheduled(cron = "0 0 1 1 * ?")
  @Transactional(rollbackFor = Exception.class)
  public void ensureNextMonthPartitions() {
    // 多实例互斥：事务级咨询锁，事务提交/回滚后自动释放
    jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(?)", PARTITION_MAINTENANCE_LOCK_KEY);

    LocalDate nextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(1);
    String startTs = nextMonth.format(DateTimeFormatter.ISO_LOCAL_DATE);
    String endTs = nextMonth.plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
    String monthLabel = nextMonth.format(DateTimeFormatter.ofPattern("yyyyMM"));

    for (String table : PARTITIONED_TABLES) {
      String partitionName = table + "_" + monthLabel;
      Boolean exists =
          jdbcTemplate.queryForObject(
              "SELECT to_regclass(?) IS NOT NULL", Boolean.class, partitionName);
      if (Boolean.TRUE.equals(exists)) {
        logger.info("[分区维护] 分区 {} 已存在，跳过", partitionName);
        continue;
      }
      // 分区名/表名/日期均来自受控常量与系统时钟，无外部输入，安全拼接
      String ddl =
          String.format(
              "CREATE TABLE %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
              partitionName, table, startTs, endTs);
      jdbcTemplate.execute(ddl);
      logger.info("[分区维护] 已创建分区 {}（{} ~ {}）", partitionName, startTs, endTs);
    }
  }
}
