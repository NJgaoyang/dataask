package com.dataask.metrics;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MetricDictionaryServiceTest {
    private final MetricDictionaryService service =
        new MetricDictionaryService(mock(JdbcTemplate.class));

    private MockMultipartFile workbook(String definition) throws Exception {
        try (XSSFWorkbook w = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet s = w.createSheet("营业指标");
            Row header = s.createRow(1);
            header.createCell(0).setCellValue("指标名称");
            header.createCell(1).setCellValue("统计口径");
            header.createCell(2).setCellValue("来源表");
            header.createCell(3).setCellValue("指标别名");
            Row first = s.createRow(2);
            first.createCell(0).setCellValue("营业额");
            first.createCell(1).setCellValue(definition);
            first.createCell(2).setCellValue("ads.sales_city_di");
            first.createCell(3).setCellValue("收入，营收");
            w.write(output);
            return new MockMultipartFile("file","metrics.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
        }
    }

    @Test void autoParseSkipsExistingOrDuplicateFieldsWithoutReplacingManualDefinitions() {
        var jdbc=new JdbcTemplate(com.dataask.audit.AuditTestDatabase.create());
        var live=new MetricDictionaryService(jdbc);
        var amount=new MetricDictionaryService.MetricEdit("销售额","","草稿建议",
            "ads.sales","amount","","city_id","主键维度");
        var amountTwice=new MetricDictionaryService.MetricEdit("销售额重复","","草稿建议",
            "ads.sales","amount","","city_id","主键维度");
        Map<String,Object> first=live.importAutoSuggestions(
            java.util.List.of(amount,amountTwice),"admin","00000000-0000-0000-0000-000000000001");
        assertThat(first.get("count")).isEqualTo(1);
        assertThat(first.get("skipped")).isEqualTo(1);
        Map<String,Object> second=live.importAutoSuggestions(java.util.List.of(amount),"admin","00000000-0000-0000-0000-000000000001");
        assertThat(second.get("count")).isEqualTo(0);
        var otherSource=new MetricDictionaryService.MetricEdit("另一数据源销售额","","待确认",
            "ads.sales","amount","","city_id","另一数据源");
        var third=live.importAutoSuggestions(java.util.List.of(otherSource),"admin",
            "00000000-0000-0000-0000-000000000002");
        assertThat(third.get("count")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_metric_definitions WHERE ads_table=? AND ads_field=?",
            Integer.class,"ads.sales","amount")).isEqualTo(2);
        jdbc.update("INSERT INTO da_app_settings(setting_key,setting_value) VALUES(?,?)",
            "sr.activeSource","00000000-0000-0000-0000-000000000001");
        String firstPrompt=live.promptForQuestion("销售额",java.util.Set.of("ads.sales"));
        assertThat(firstPrompt).contains("销售额").doesNotContain("另一数据源销售额");
        jdbc.update("UPDATE da_app_settings SET setting_value=? WHERE setting_key='sr.activeSource'",
            "00000000-0000-0000-0000-000000000002");
        String secondPrompt=live.promptForQuestion("另一数据源销售额",java.util.Set.of("ads.sales"));
        assertThat(secondPrompt).contains("另一数据源销售额");
    }

    @Test void complexMetricRequiresAuthorizationOfEverySourceTableAndCanBeUpdated() {
        var jdbc=new JdbcTemplate(com.dataask.audit.AuditTestDatabase.create());
        var live=new MetricDictionaryService(jdbc);
        var metric=new MetricDictionaryService.MetricEdit("净营业额","净收入","订单金额减已退金额",
            "","","pay_dt","city_id","");
        var formula=new MetricDictionaryService.MetricCalculation(
            "ads.orders_di, ads.refunds_di","order_id, paid_amount, refund_amount",
            "SUM(paid_amount)-SUM(refund_amount)","order_id 关联；退款先按订单汇总");
        live.createMetric(metric,"admin",formula);
        Long id=jdbc.queryForObject("SELECT MAX(id) FROM da_metric_definitions",Long.class);
        assertThat(id).isNotNull();
        assertThat(live.promptForQuestion("净营业额",java.util.Set.of("ads.orders_di")))
            .isBlank();
        String hint=live.promptForQuestion("净营业额",
            java.util.Set.of("ads.orders_di","ads.refunds_di"));
        assertThat(hint).contains("净营业额","ads.orders_di","ads.refunds_di",
            "SUM(paid_amount)-SUM(refund_amount)","order_id 关联");
        assertThat(jdbc.queryForObject(
            "SELECT source_tables FROM da_metric_calculations WHERE metric_id=?",
            String.class,id)).contains("ads.refunds_di");

        live.updateMetric(id,metric,new MetricDictionaryService.MetricCalculation(
            "ads.orders_di","paid_amount","SUM(paid_amount)",""));
        assertThat(live.promptForQuestion("净营业额",java.util.Set.of("ads.orders_di")))
            .contains("SUM(paid_amount)");
        assertThat(live.promptForQuestion("净营业额",java.util.Set.of("ads.refunds_di")))
            .isBlank();
    }

    @Test void excelImportsAndExportsMultiTableCalculationMetadata() throws Exception {
        var jdbc=new JdbcTemplate(com.dataask.audit.AuditTestDatabase.create());
        var live=new MetricDictionaryService(jdbc);
        MockMultipartFile file;
        try(XSSFWorkbook workbook=new XSSFWorkbook();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            Sheet sheet=workbook.createSheet("复杂指标");
            Row head=sheet.createRow(0);
            String[] labels={"指标名称","指标口径","关联来源表","参与字段","计算表达式","表关联规则"};
            for(int i=0;i<labels.length;i++)head.createCell(i).setCellValue(labels[i]);
            Row row=sheet.createRow(1);
            row.createCell(0).setCellValue("净营业额");
            row.createCell(1).setCellValue("支付金额扣减已退金额");
            row.createCell(2).setCellValue("ads.orders_di,ads.refunds_di");
            row.createCell(3).setCellValue("paid_amount,refund_amount");
            row.createCell(4).setCellValue("SUM(paid_amount)-SUM(refund_amount)");
            row.createCell(5).setCellValue("order_id 关联");
            workbook.write(bytes);
            file=new MockMultipartFile("file","metrics.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.toByteArray());
        }
        var preview=live.preview(file);
        assertThat(preview.samples().getFirst().sourceTables()).contains("ads.refunds_di");
        live.importWorkbook(file,"admin");
        var exported=live.exportMetrics().getFirst();
        assertThat(exported.calculationExpression()).isEqualTo("SUM(paid_amount)-SUM(refund_amount)");
        assertThat(exported.joinRules()).contains("order_id");
        assertThat(live.promptForQuestion("净营业额",java.util.Set.of("ads.orders_di"))).isBlank();
    }

    @Test void invalidMultiTableIdentifierIsRejected() {
        var jdbc=new JdbcTemplate(com.dataask.audit.AuditTestDatabase.create());
        var live=new MetricDictionaryService(jdbc);
        var metric=new MetricDictionaryService.MetricEdit("金额","","仅用于测试","","","","","");
        assertThatThrownBy(()->live.createMetric(metric,"admin",
            new MetricDictionaryService.MetricCalculation("other table;DROP TABLE users","",
                "SUM(amount)","")))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM da_metric_definitions",
            Integer.class)).isZero();
    }

    @Test void previewsChineseMetricHeaders() throws Exception {
        var data = service.preview(workbook("支付完成金额，按照业务日期统计"));
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.samples()).hasSize(1);
        assertThat(data.samples().getFirst().metricName()).isEqualTo("营业额");
        assertThat(data.samples().getFirst().aliases()).contains("营收");
        assertThat(data.samples().getFirst().excelRow()).isEqualTo(3);
    }

    @Test void refusesBlankMetricDefinition() throws Exception {
        assertThatThrownBy(() -> service.preview(workbook("")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void rejectsUnsupportedFileExtension() {
        assertThatThrownBy(() -> service.preview(new MockMultipartFile(
            "file", "metrics.txt", "text/plain", "unsafe".getBytes())))
            .isInstanceOf(ResponseStatusException.class);
    }
}
