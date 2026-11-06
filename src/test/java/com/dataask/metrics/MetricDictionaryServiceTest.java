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
