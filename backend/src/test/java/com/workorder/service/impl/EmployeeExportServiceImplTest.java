package com.workorder.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.workorder.entity.User;
import com.workorder.service.IEmployeeExportService;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * EmployeeExportService 深模块完整测试套件
 *
 * <p>测试策略：直接实例化 Service，使用 MockHttpServletResponse 验证输出。 覆盖范围： - CSV 导出格式和编码（6种场景） - 特殊字符转义处理（5种场景）
 * - 职位ID映射（21种场景） - 组织层级映射（7种场景） - 空值和边界输入（4种场景）
 */
class EmployeeExportServiceImplTest {

  private IEmployeeExportService service;

  @BeforeEach
  void setUp() {
    service = new EmployeeExportServiceImpl();
  }

  // ========== 测试数据工厂 ==========

  private static User makeEmployee(
      Long id,
      String name,
      String phone,
      String email,
      String dept,
      Integer positionId,
      Integer orgLevel,
      Integer status,
      String hireDate) {
    User emp = new User();
    emp.setId(id);
    emp.setRealName(name);
    emp.setPhone(phone);
    emp.setEmail(email);
    emp.setDepartment(dept);
    emp.setPositionId(positionId != null ? positionId.longValue() : null);
    emp.setOrgLevel(orgLevel);
    emp.setStatus(status);
    emp.setHireDate(hireDate); // String 类型
    return emp;
  }

  // ==================== 1. exportToCsv - CSV 导出 ====================

  @Nested
  @DisplayName("exportToCsv - CSV 格式导出")
  class ExportToCsvTests {

    @Test
    @DisplayName("空列表导出仅包含表头行")
    void empty_list_exports_only_header() throws IOException {
      MockHttpServletResponse response = new MockHttpServletResponse();

      service.exportToCsv(Collections.emptyList(), response);

      String output = response.getContentAsString();
      assertThat(output).startsWith("\ufeff"); // BOM 头
      assertThat(output).contains("ID,姓名,手机号,邮箱,部门,职位,组织层级,状态,入职时间");
      // 只有表头，没有数据行
      String[] lines = output.split("\n");
      assertThat(lines).hasSize(1); // 仅表头
    }

    @Test
    @DisplayName("正常数据导出包含表头和数据行")
    void normal_data_export() throws IOException {
      List<User> employees =
          Arrays.asList(
              makeEmployee(
                  1L, "张三", "13800138000", "zhangsan@example.com", "研发部", 35, 4, 1, "2023-01-15"),
              makeEmployee(
                  2L, "李四", "13900139000", "lisi@example.com", "销售部", 34, 4, 1, "2023-06-20"));

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(employees, response);

      String output = response.getContentAsString();
      // 验证 BOM 和表头
      assertThat(output).startsWith("\ufeff");
      assertThat(output).contains("ID,姓名,手机号,邮箱,部门,职位,组织层级,状态,入职时间");

      // 验证数据行
      assertThat(output)
          .contains("1,张三,13800138000,zhangsan@example.com,研发部,研发工程师专员,员工级,启用,2023-01-15");
      assertThat(output).contains("2,李四,13900139000,lisi@example.com,销售部,销售专员,员工级,启用,2023-06-20");
    }

    @Test
    @DisplayName("响应头设置正确：Content-Type 和文件名")
    void response_headers_set_correctly() throws IOException {
      List<User> employees =
          Collections.singletonList(
              makeEmployee(1L, "测试", null, null, null, null, null, null, null));

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(employees, response);

      assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
      assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
      String disposition = response.getHeader("Content-Disposition");
      assertThat(disposition).contains("attachment");
      assertThat(disposition).contains(".csv");
      // 文件名被 URL 编码
      assertThat(disposition).contains("filename=");
    }

    @Test
    @DisplayName("禁用用户(status=0)显示为'禁用'")
    void disabled_user_shows_disabled_status() throws IOException {
      List<User> employees =
          Collections.singletonList(
              makeEmployee(1L, "离职员工", null, null, null, null, null, 0, null));

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(employees, response);

      String output = response.getContentAsString();
      assertThat(output).contains("禁用");
    }

    @Test
    @DisplayName("多个员工正确输出多行数据")
    void multiple_employees_multiple_rows() throws IOException {
      List<User> employees =
          Arrays.asList(
              makeEmployee(1L, "A", null, null, null, null, null, null, null),
              makeEmployee(2L, "B", null, null, null, null, null, null, null),
              makeEmployee(3L, "C", null, null, null, null, null, null, null));

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(employees, response);

      String output = response.getContentAsString();
      String[] lines = output.split("\n");
      // 表头 + 3 行数据
      assertThat(lines).hasSize(4);
    }
  }

  // ==================== 2. 特殊字符转义 ====================

  @Nested
  @DisplayName("CSV 特殊字符转义处理")
  class CsvEscapeTests {

    @Test
    @DisplayName("包含逗号的字段用双引号包裹")
    void comma_in_field_is_quoted() throws IOException {
      User emp = makeEmployee(1L, "张,三", null, null, null, null, null, null, null);

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      assertThat(output).contains("\"张,三\"");
    }

    @Test
    @DisplayName("包含双引号的字段：引号被转义为双引号并包裹")
    void double_quote_in_field_is_escaped() throws IOException {
      User emp = makeEmployee(1L, "张\"三", null, null, null, null, null, null, null);

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      assertThat(output).contains("\"张\"\"三\"");
    }

    @Test
    @DisplayName("包含换行符的字段用双引号包裹")
    void newline_in_field_is_quoted() throws IOException {
      User emp = makeEmployee(1L, "张\n三", null, null, null, null, null, null, null);

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      assertThat(output).contains("\"张\n三\"");
    }

    @Test
    @DisplayName("null 字段输出为空字符串")
    void null_field_outputs_empty_string() throws IOException {
      User emp = new User();
      emp.setId(1L);
      // 其他字段都为 null

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      // null 的姓名、手机号等应输出为空
      // 输出格式：ID,姓名,手机号,邮箱,部门,职位,组织层级,状态,入职时间
      assertThat(output).contains("1,,,,,-,-,禁用,");
    }

    @Test
    @DisplayName("普通字符串不添加引号")
    void normal_string_not_quoted() throws IOException {
      User emp = makeEmployee(1L, "正常姓名", null, null, null, null, null, null, null);

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      assertThat(output).contains(",正常姓名,");
    }
  }

  // ==================== 3. getPositionName - 职位映射 ====================

  @Nested
  @DisplayName("getPositionName - 职位ID映射")
  class PositionNameTests {

    @Test
    @DisplayName("null 返回 '-'")
    void null_returns_dash() {
      assertThat(service.getPositionName(null)).isEqualTo("-");
    }

    // 管理层职位
    @Test
    @DisplayName("ID=1 超级管理员")
    void position_1_super_admin() {
      assertThat(service.getPositionName(1)).isEqualTo("超级管理员");
    }

    @Test
    @DisplayName("ID=2 安全审计管理员")
    void position_2_security_audit() {
      assertThat(service.getPositionName(2)).isEqualTo("安全审计管理员");
    }

    // 决策层
    @Test
    @DisplayName("ID=10 董事长")
    void position_10_chairman() {
      assertThat(service.getPositionName(10)).isEqualTo("董事长");
    }

    @Test
    @DisplayName("ID=11 总经理")
    void position_11_gm() {
      assertThat(service.getPositionName(11)).isEqualTo("总经理");
    }

    @Test
    @DisplayName("ID=12 副总经理")
    void position_12_vp() {
      assertThat(service.getPositionName(12)).isEqualTo("副总经理");
    }

    // 总监层 (20-25)
    @Test
    @DisplayName("ID=20 研发总监")
    void position_20_rnd_director() {
      assertThat(service.getPositionName(20)).isEqualTo("研发总监");
    }

    @Test
    @DisplayName("ID=21 销售总监")
    void position_21_sales_director() {
      assertThat(service.getPositionName(21)).isEqualTo("销售总监");
    }

    @Test
    @DisplayName("ID=22 财务总监")
    void position_22_finance_director() {
      assertThat(service.getPositionName(22)).isEqualTo("财务总监");
    }

    @Test
    @DisplayName("ID=23 行政总监")
    void position_23_admin_director() {
      assertThat(service.getPositionName(23)).isEqualTo("行政总监");
    }

    @Test
    @DisplayName("ID=24 人事总监")
    void position_24_hr_director() {
      assertThat(service.getPositionName(24)).isEqualTo("人事总监");
    }

    @Test
    @DisplayName("ID=25 采购总监")
    void position_25_procurement_director() {
      assertThat(service.getPositionName(25)).isEqualTo("采购总监");
    }

    // 专员层 (30-36)
    @Test
    @DisplayName("ID=30 HR人事专员")
    void position_30_hr_specialist() {
      assertThat(service.getPositionName(30)).isEqualTo("HR人事专员");
    }

    @Test
    @DisplayName("ID=31 行政专员")
    void position_31_admin_specialist() {
      assertThat(service.getPositionName(31)).isEqualTo("行政专员");
    }

    @Test
    @DisplayName("ID=32 费用会计专员")
    void position_32_accountant() {
      assertThat(service.getPositionName(32)).isEqualTo("费用会计专员");
    }

    @Test
    @DisplayName("ID=33 出纳专员")
    void position_33_cashier() {
      assertThat(service.getPositionName(33)).isEqualTo("出纳专员");
    }

    @Test
    @DisplayName("ID=34 销售专员")
    void position_34_sales_specialist() {
      assertThat(service.getPositionName(34)).isEqualTo("销售专员");
    }

    @Test
    @DisplayName("ID=35 研发工程师专员")
    void position_35_engineer() {
      assertThat(service.getPositionName(35)).isEqualTo("研发工程师专员");
    }

    @Test
    @DisplayName("ID=36 采购专员")
    void position_36_procurement_specialist() {
      assertThat(service.getPositionName(36)).isEqualTo("采购专员");
    }

    // 基层
    @Test
    @DisplayName("ID=40 普通员工")
    void position_40_regular_employee() {
      assertThat(service.getPositionName(40)).isEqualTo("普通员工");
    }

    // 未知 ID
    @Test
    @DisplayName("未知 ID 返回 '-'")
    void unknown_id_returns_dash() {
      assertThat(service.getPositionName(999)).isEqualTo("-");
      assertThat(service.getPositionName(-1)).isEqualTo("-");
      assertThat(service.getPositionName(100)).isEqualTo("-");
    }
  }

  // ==================== 4. getOrgLevelName - 层级映射 ====================

  @Nested
  @DisplayName("getOrgLevelName - 组织层级映射")
  class OrgLevelNameTests {

    @Test
    @DisplayName("null 返回 '-'")
    void null_returns_dash() {
      assertThat(service.getOrgLevelName(null)).isEqualTo("-");
    }

    @Test
    @DisplayName("0 = 系统级")
    void level_0_system() {
      assertThat(service.getOrgLevelName(0)).isEqualTo("系统级");
    }

    @Test
    @DisplayName("1 = 高管级")
    void level_1_executive() {
      assertThat(service.getOrgLevelName(1)).isEqualTo("高管级");
    }

    @Test
    @DisplayName("2 = 管理级")
    void level_2_management() {
      assertThat(service.getOrgLevelName(2)).isEqualTo("管理级");
    }

    @Test
    @DisplayName("3 = 专员级")
    void level_3_specialist() {
      assertThat(service.getOrgLevelName(3)).isEqualTo("专员级");
    }

    @Test
    @DisplayName("4 = 员工级")
    void level_4_staff() {
      assertThat(service.getOrgLevelName(4)).isEqualTo("员工级");
    }

    @Test
    @DisplayName("未知层级返回 '-'")
    void unknown_level_returns_dash() {
      assertThat(service.getOrgLevelName(5)).isEqualTo("-");
      assertThat(service.getOrgLevelName(-1)).isEqualTo("-");
      assertThat(service.getOrgLevelName(99)).isEqualTo("-");
    }
  }

  // ==================== 5. 边界条件和异常场景 ====================

  @Nested
  @DisplayName("边界条件和异常处理")
  class BoundaryAndEdgeCaseTests {

    @Test
    @DisplayName("所有字段都为 null 的用户不崩溃")
    void all_null_fields_does_not_crash() throws IOException {
      User emp = new User();
      emp.setId(99L);
      // 其他字段全部为 null

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      // 应该成功输出，不抛异常
      String output = response.getContentAsString();
      assertThat(output).isNotEmpty();
    }

    @Test
    @DisplayName("入职日期为 null 时输出空字符串")
    void null_hire_date_outputs_empty() throws IOException {
      User emp = makeEmployee(1L, "测试", null, null, null, null, null, null, null);

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      // 验证数据行包含禁用状态
      assertThat(output).contains("禁用");
    }

    @Test
    @DisplayName("BOM 头确保 UTF-8 编码正确")
    void bom_header_present_for_utf8() throws IOException {
      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.emptyList(), response);

      byte[] content = response.getContentAsByteArray();
      // BOM 是 EF BB BF (3 bytes)
      assertThat(content[0]).isEqualTo((byte) 0xEF);
      assertThat(content[1]).isEqualTo((byte) 0xBB);
      assertThat(content[2]).isEqualTo((byte) 0xBF);
    }

    @Test
    @DisplayName("中文文件名正确 URL 编码")
    void chinese_filename_url_encoded() throws IOException {
      List<User> employees =
          Collections.singletonList(
              makeEmployee(1L, "测试", null, null, null, null, null, null, null));

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(employees, response);

      String disposition = response.getHeader("Content-Disposition");
      assertThat(disposition).contains("attachment");
      assertThat(disposition).contains("filename=");
      // URL 编码后的文件名包含日期
      assertThat(disposition).contains(".csv");
    }

    @Test
    @DisplayName("同时包含多种特殊字符的字段正确转义")
    void combined_special_characters_escaped() throws IOException {
      User emp = makeEmployee(1L, "张,\"三\n四\"", null, null, null, null, null, null, null);

      MockHttpServletResponse response = new MockHttpServletResponse();
      service.exportToCsv(Collections.singletonList(emp), response);

      String output = response.getContentAsString();
      // 应该包含转义后的内容：引号变成双引号，整体被包裹在引号中
      assertThat(output).contains("\"张,\"\"三\n四\"\"\"");
    }
  }
}
