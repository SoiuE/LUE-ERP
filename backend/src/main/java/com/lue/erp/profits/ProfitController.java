package com.lue.erp.profits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/profits")
public class ProfitController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final ProfitCalculationService calculation;
    private final ProfitImportService imports;
    private final ProfitReportService reports;

    public ProfitController(JdbcTemplate db, AuthService auth, ObjectMapper json, ProfitCalculationService calculation, ProfitImportService imports, ProfitReportService reports) {
        this.db = db; this.auth = auth; this.json = json; this.calculation = calculation; this.imports = imports; this.reports = reports;
    }

    @GetMapping("/options")
    public ApiResponse<Map<String,Object>> options(@AuthenticationPrincipal AuthUser user) {
        view(user);
        return ApiResponse.ok(Map.of(
            "shops", db.queryForList("SELECT id,shop_name shopName FROM shops WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY shop_name"),
            "skus", db.queryForList("SELECT id,internal_sku internalSku,product_name productName FROM skus WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY internal_sku LIMIT 5000")
        ));
    }

    @GetMapping("/report")
    public ApiResponse<Map<String,Object>> report(@RequestParam(defaultValue="daily") String dimension,
        @RequestParam(required=false) String dateFrom, @RequestParam(required=false) String dateTo,
        @RequestParam(required=false) Long shopId, @RequestParam(required=false) Long skuId,
        @RequestParam(required=false) String orderMode, @RequestParam(required=false) String q,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
        @AuthenticationPrincipal AuthUser user) {
        view(user);
        return ApiResponse.ok(reports.list(dimension, date(dateFrom), date(dateTo), shopId, skuId, orderMode, q, page, pageSize));
    }

    @Transactional
    @PostMapping("/recalculate")
    public ApiResponse<Map<String,Object>> recalculate(@RequestBody Map<String,Object> body, @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        view(user); auth.requirePermission(user.id(), "button.profits.recalculate");
        LocalDate from = date(Objects.toString(body.get("dateFrom"), null));
        LocalDate to = date(Objects.toString(body.get("dateTo"), null));
        if (from == null || to == null) throw new IllegalArgumentException("请填写重算开始日期和结束日期");
        Map<String,Object> result = calculation.recalculate(from, to);
        audit(user, request, "profit.recalculate", from + ".." + to, Map.of("dateFrom", from.toString(), "dateTo", to.toString(), "rows", result.get("rows")));
        return ApiResponse.ok(result);
    }

    @PostMapping(value="/imports/{type}", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> upload(@PathVariable String type, @RequestParam(defaultValue="false") boolean forceReimport,
        @RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        view(user); auth.requirePermission(user.id(), "button.profits.import");
        return ApiResponse.ok(imports.upload(type, forceReimport, file, user, request));
    }

    @GetMapping(value="/templates/{type}", produces=XLSX)
    public ResponseEntity<byte[]> template(@PathVariable String type, @AuthenticationPrincipal AuthUser user) {
        view(user); byte[] bytes = imports.template(type);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + type + "_profit_import_template.xlsx").contentType(MediaType.parseMediaType(XLSX)).body(bytes);
    }

    @GetMapping(value="/export", produces=XLSX)
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue="daily") String dimension,
        @RequestParam(required=false) String dateFrom, @RequestParam(required=false) String dateTo,
        @RequestParam(required=false) Long shopId, @RequestParam(required=false) Long skuId,
        @RequestParam(required=false) String orderMode, @RequestParam(required=false) String q,
        @AuthenticationPrincipal AuthUser user) {
        view(user);
        List<Map<String,Object>> rows = reports.export(dimension, date(dateFrom), date(dateTo), shopId, skuId, orderMode, q);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("利润报表");
            List<String> fields = fields(dimension); CellStyle header = workbook.createCellStyle(); Font font = workbook.createFont(); font.setBold(true); header.setFont(font);
            Row first = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++) { Cell c = first.createCell(i); c.setCellValue(label(fields.get(i))); c.setCellStyle(header); sheet.setColumnWidth(i, 20 * 256); }
            int line = 1;
            for (Map<String,Object> row : rows) { Row data = sheet.createRow(line++); for (int i = 0; i < fields.size(); i++) { Object value = row.get(fields.get(i)); if (value instanceof Number n) data.createCell(i).setCellValue(n.doubleValue()); else data.createCell(i).setCellValue(Objects.toString(value, "")); } }
            sheet.createFreezePane(0, 1); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size()), 0, fields.size()-1));
            workbook.write(out);
            String filename = URLEncoder.encode("SKU利润报表_" + dimension + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename).contentType(MediaType.parseMediaType(XLSX)).body(out.toByteArray());
        } catch (Exception e) { throw new IllegalStateException("利润报表 Excel 生成失败", e); }
    }

    private void view(AuthUser user) { auth.requirePermission(user.id(), "menu.profits"); }
    private void audit(AuthUser user, HttpServletRequest request, String action, String id, Map<String,Object> detail) {
        try { db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,'sku_profit_daily',?,CAST(? AS JSON),?,TRUE)", user.id(), user.username(), action, id, json.writeValueAsString(detail), request.getRemoteAddr()); }
        catch (JsonProcessingException e) { throw new IllegalStateException("利润重算日志写入失败", e); }
    }
    private static LocalDate date(String value) { return value == null || value.isBlank() ? null : LocalDate.parse(value); }
    private static List<String> fields(String dimension) {
        List<String> keys = new ArrayList<>(switch (dimension) {
            case "daily" -> List.of("reportDate", "shopName", "internalSku", "productName", "orderMode");
            case "monthly" -> List.of("reportMonth", "shopName", "internalSku", "productName", "orderMode");
            case "shop" -> List.of("shopName");
            case "mode" -> List.of("orderMode");
            default -> throw new IllegalArgumentException("利润报表维度无效");
        });
        keys.addAll(List.of("salesQty", "salesRevenue", "platformFee", "purchaseCost", "packageCost", "refundLoss", "otherCost", "grossProfit", "grossMargin"));
        return keys;
    }
    private static String label(String key) { return switch (key) {
        case "reportDate" -> "日期"; case "reportMonth" -> "月份"; case "shopName" -> "店铺"; case "internalSku" -> "内部 SKU"; case "productName" -> "商品名称"; case "orderMode" -> "订单模式";
        case "salesQty" -> "销售数量"; case "salesRevenue" -> "销售收入（元）"; case "platformFee" -> "平台费用（元）"; case "purchaseCost" -> "采购成本（元）"; case "packageCost" -> "包材成本（元）"; case "refundLoss" -> "退款售后损失（元）"; case "otherCost" -> "其他分摊费用（元）"; case "grossProfit" -> "SKU 毛利（元）"; case "grossMargin" -> "SKU 毛利率"; default -> key;
    }; }
}
