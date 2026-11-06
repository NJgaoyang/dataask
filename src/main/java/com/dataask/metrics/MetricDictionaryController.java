package com.dataask.metrics;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/metrics")
public class MetricDictionaryController {
    private final MetricDictionaryService service;

    public MetricDictionaryController(MetricDictionaryService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String,Object> list() { return service.listImports(); }

    @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public MetricDictionaryService.WorkbookPreview preview(@RequestPart("file") MultipartFile file) {
        return service.preview(file);
    }

    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestPart("file") MultipartFile file, Authentication auth) {
        return service.importWorkbook(file,auth.getName());
    }

    @DeleteMapping("/batches/{id}")
    public Map<String,Object> delete(@PathVariable String id) {
        service.deleteBatch(id);
        return Map.of("success",true);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("指标口径");
            String[] headers={"指标名称","指标口径","指标别名","ADS表名","来源字段","时间字段","分析维度","备注"};
            Row row=sheet.createRow(0);
            CellStyle headerStyle=book.createCellStyle();
            Font font=book.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            for (int i=0;i<headers.length;i++) {
                Cell cell=row.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i,(i==1 ? 48 : 24)*256);
            }
            sheet.createFreezePane(0,1);
            Sheet notes=book.createSheet("填写说明");
            notes.createRow(0).createCell(0).setCellValue("第一列“指标名称”、第二列“指标口径”必填；其他列可留空。");
            notes.createRow(1).createCell(0).setCellValue("统计口径应写明去重、退款、日期范围、单位等计算原则。");
            notes.createRow(2).createCell(0).setCellValue("ADS表名若填写，须是系统管理中已开放的表（如 ads.ads_sales_di）。");
            notes.createRow(3).createCell(0).setCellValue("指标别名用中文逗号分隔；请不要上传敏感个人信息。");
            notes.setColumnWidth(0,95*256);
            book.write(output);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"dataask-metric-template.xlsx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(output.toByteArray());
        }
    }
}
