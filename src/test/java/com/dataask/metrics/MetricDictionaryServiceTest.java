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
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_metric_definitions WHERE ads_table=? AND ads_field=?",
            Integer.class,"ads.sales","amount")).isEqualTo(1);
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
