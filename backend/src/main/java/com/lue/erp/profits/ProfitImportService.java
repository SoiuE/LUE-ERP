package com.lue.erp.profits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ProfitImportService {
    private static final Map<String, String> SETTLEMENT_HEADERS = Map.ofEntries(
        Map.entry("店铺标识", "shop_identifier"), Map.entry("平台订单号", "platform_order_no"),
        Map.entry("平台 SKU", "platform_sku"), Map.entry("平台SKU", "platform_sku"),
        Map.entry("订单模式", "order_mode"), Map.entry("结算日期", "settlement_date"),
        Map.entry("平台费用（元）", "platform_fee"), Map.entry("平台费用", "platform_fee"),
        Map.entry("结算金额（元）", "settlement_amount"), Map.entry("结算金额", "settlement_amount")
    );
    private static final Map<String, String> COST_HEADERS = Map.ofEntries(
        Map.entry("店铺标识", "shop_identifier"), Map.entry("内部 SKU", "internal_sku"),
        Map.entry("费用日期", "cost_date"), Map.entry("日期", "cost_date"),
        Map.entry("费用金额（元）", "amount"), Map.entry("费用金额", "amount"), Map.entry("金额（元）", "amount"),
        Map.entry("备注", "note")
    );

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ProfitCalculationService calculation;
    @Value("${lue.import.storage-dir:./data/imports}") private String storageDir;

    public ProfitImportService(JdbcTemplate db, ObjectMapper json, ProfitCalculationService calculation) {
        this.db = db; this.json = json; this.calculation = calculation;
    }

    public byte[] template(String type) {
        if (!Set.of("settlement", "other_cost").contains(type)) throw new IllegalArgumentException("未知利润导入模板");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] headers = type.equals("settlement")
                ? new String[]{"店铺标识", "平台订单号", "平台 SKU", "订单模式", "结算日期", "平台费用（元）", "结算金额（元）"}
                : new String[]{"店铺标识", "内部 SKU", "费用日期", "费用金额（元）", "备注"};
            String[] example = type.equals("settlement")
                ? new String[]{"替换为店铺标识", "", "平台SKU-001", "JIT", "2026-10-13", "5.00", "95.00"}
                : new String[]{"替换为店铺标识", "SKU-001", "2026-10-13", "2.00", "营销分摊"};
            Sheet sheet = wb.createSheet("利润数据"); Row head = sheet.createRow(0); Row sample = sheet.createRow(1);
            CellStyle style = wb.createCellStyle(); Font font = wb.createFont(); font.setBold(true); style.setFont(font);
            for (int i = 0; i < headers.length; i++) { Cell c = head.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(style); sample.createCell(i).setCellValue(example[i]); sheet.setColumnWidth(i, 24 * 256); }
            sheet.createFreezePane(0, 1); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.length - 1));
            Sheet guide = wb.createSheet("填写说明");
            String[][] notes = type.equals("settlement") ? new String[][]{
                {"字段", "填写要求"}, {"店铺标识", "必填，填写系统中已启用的店铺标识"}, {"平台订单号", "选填"},
                {"平台 SKU", "必填，必须已有有效店铺 SKU 映射"}, {"订单模式", "选填；为空时采用映射默认模式；仅 JIT 或 WAREHOUSE"},
                {"结算日期", "必填，YYYY-MM-DD"}, {"平台费用（元）", "必填，人民币；允许负数冲正"}, {"结算金额（元）", "选填，人民币，仅保存供核对"},
                {"说明", "结算导入中的平台费用覆盖同日同店铺、SKU、订单模式的订单表平台费用"}
            } : new String[][]{
                {"字段", "填写要求"}, {"店铺标识", "必填，填写系统中已启用的店铺标识"}, {"内部 SKU", "必填，填写已启用的内部 SKU"},
                {"费用日期", "必填，YYYY-MM-DD"}, {"费用金额（元）", "必填，人民币非负数"}, {"备注", "选填"},
                {"说明", "其他费用按该店铺、SKU、日期各订单模式销售收入占比分摊；无收入时按销量，再无销量时均分"}
            };
            for (int r = 0; r < notes.length; r++) for (int c = 0; c < notes[r].length; c++) guide.createRow(r).createCell(c).setCellValue(notes[r][c]);
            guide.setColumnWidth(0, 26 * 256); guide.setColumnWidth(1, 100 * 256);
            wb.write(out); return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("利润导入模板生成失败", e); }
    }

    @Transactional
    public Map<String, Object> upload(String type, boolean force, MultipartFile file, AuthUser actor, HttpServletRequest request) {
        if (!Set.of("settlement", "other_cost").contains(type)) throw new IllegalArgumentException("导入类型无效");
        if (file == null || file.isEmpty() || !Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("请选择 .xlsx 文件");
        if (file.getSize() > 50L * 1024 * 1024) throw new IllegalArgumentException("文件最大支持 50 MB");
        List<ImportRow> rows = parse(type, file);
        if (rows.size() > 20_000) throw new IllegalArgumentException("单文件最多 20,000 行");
        if (rows.isEmpty()) throw new IllegalArgumentException("Excel 没有可导入的数据行");
        String hash = sha256(file);
        List<Long> previous = db.queryForList("SELECT id FROM import_batches WHERE import_type=? AND file_sha256=? AND status='SUCCESS' AND is_deleted=FALSE", Long.class, type, hash);
        if (!previous.isEmpty() && !force) throw new IllegalArgumentException("该文件已成功导入；如需替换本批次数据，请勾选强制重导入");
        if (!previous.isEmpty()) {
            String marks = String.join(",", Collections.nCopies(previous.size(), "?"));
            String table = type.equals("settlement") ? "settlement_summaries" : "other_cost_imports";
            db.update("UPDATE " + table + " SET is_deleted=TRUE WHERE import_batch_id IN (" + marks + ")", previous.toArray());
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        db.update("INSERT INTO import_batch_sequences(sequence_date,sequence_value) VALUES (?,1) ON DUPLICATE KEY UPDATE sequence_value=sequence_value+1", today);
        Long sequence = db.queryForObject("SELECT sequence_value FROM import_batch_sequences WHERE sequence_date=? FOR UPDATE", Long.class, today);
        String batchNo = "IM" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format(Locale.ROOT, "%08d", sequence);
        String original = Path.of(Objects.requireNonNullElse(file.getOriginalFilename(), "profit.xlsx")).getFileName().toString();
        Path stored = saveFile(batchNo, original, file);
        db.update("INSERT INTO import_batches(batch_no,import_type,original_filename,stored_file_path,file_sha256,force_reimport,status,total_rows,valid_rows,imported_rows,created_by) VALUES (?,?,?,?,?,?, 'SUCCESS',?,?,?,?)",
            batchNo, type, original, stored.toString(), hash, force, rows.size(), rows.size(), rows.size(), actor.id());
        Long batchId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        LocalDate min = rows.stream().map(ImportRow::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate max = rows.stream().map(ImportRow::date).max(LocalDate::compareTo).orElseThrow();
        for (ImportRow row : rows) {
            String raw = writeJson(row.raw()); String normalized = writeJson(row.normalized());
            db.update("INSERT INTO raw_import_rows(batch_id,source_row_number,raw_data,normalized_data,row_status,imported_record_id) VALUES (?,?,CAST(? AS JSON),CAST(? AS JSON),'IMPORTED',?)",
                batchId, row.rowNumber(), raw, normalized, null);
            if (type.equals("settlement")) {
                Map<String, Object> n = row.normalized();
                db.update("INSERT INTO settlement_summaries(import_batch_id,source_row_number,shop_id,platform_order_no,platform_sku,sku_id,order_mode,settlement_date,platform_fee,settlement_amount) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    batchId, row.rowNumber(), n.get("shopId"), n.get("platformOrderNo"), n.get("platformSku"), n.get("skuId"), n.get("orderMode"), n.get("date"), n.get("platformFee"), n.get("settlementAmount"));
            } else {
                Map<String, Object> n = row.normalized();
                db.update("INSERT INTO other_cost_imports(import_batch_id,source_row_number,shop_id,sku_id,cost_date,amount,note) VALUES (?,?,?,?,?,?,?)",
                    batchId, row.rowNumber(), n.get("shopId"), n.get("skuId"), n.get("date"), n.get("amount"), n.get("note"));
            }
        }
        Map<String, Object> recalc = calculation.recalculate(min, max);
        audit(actor, request, "profit.import." + type, batchId, Map.of("batchNo", batchNo, "rows", rows.size(), "forceReimport", force, "recalculated", recalc));
        return Map.of("batchNo", batchNo, "rows", rows.size(), "dateFrom", min.toString(), "dateTo", max.toString(), "recalculatedRows", recalc.get("rows"));
    }

    private List<ImportRow> parse(String type, MultipartFile file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0); DataFormatter formatter = new DataFormatter(Locale.ROOT); Row header = sheet.getRow(0);
            if (header == null) throw new IllegalArgumentException("缺少表头行");
            Map<String, Integer> columns = new LinkedHashMap<>(); Map<String, String> labels = type.equals("settlement") ? SETTLEMENT_HEADERS : COST_HEADERS;
            for (int i = 0; i < header.getLastCellNum(); i++) {
                String label = formatter.formatCellValue(header.getCell(i)).trim(); if (label.isBlank()) continue;
                String key = labels.getOrDefault(label, label);
                if (columns.putIfAbsent(key, i) != null) throw new IllegalArgumentException("表头重复：" + label);
            }
            List<String> required = type.equals("settlement")
                ? List.of("shop_identifier", "platform_sku", "settlement_date", "platform_fee")
                : List.of("shop_identifier", "internal_sku", "cost_date", "amount");
            for (String field : required) if (!columns.containsKey(field)) throw new IllegalArgumentException("缺少必填表头：" + field);
            List<ImportRow> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;
                Map<String, Object> raw = new LinkedHashMap<>(); for (var col : columns.entrySet()) raw.put(col.getKey(), formatter.formatCellValue(row.getCell(col.getValue())).trim());
                String shopIdentifier = text(raw.get("shop_identifier"));
                if (shopIdentifier.isBlank() || shopIdentifier.startsWith("替换为")) continue;
                Long shopId = lookup("SELECT id FROM shops WHERE shop_identifier=? AND status='ACTIVE' AND is_deleted=FALSE", shopIdentifier);
                if (shopId == null) throw rowError(i + 1, "店铺标识不存在或已停用：" + shopIdentifier);
                LocalDate day = parseDate(text(raw.get(type.equals("settlement") ? "settlement_date" : "cost_date")), i + 1);
                Map<String, Object> normalized = new LinkedHashMap<>(); normalized.put("shopId", shopId); normalized.put("date", day);
                if (type.equals("settlement")) {
                    String platformSku = text(raw.get("platform_sku"));
                    Map<String, Object> mapping = db.query("SELECT m.sku_id skuId,m.default_order_mode defaultMode FROM shop_sku_mappings m WHERE m.shop_id=? AND m.platform_sku=? AND m.status='ACTIVE' AND m.is_deleted=FALSE", rs -> rs.next() ? Map.of("skuId", rs.getLong("skuId"), "defaultMode", rs.getString("defaultMode")) : null, shopId, platformSku);
                    if (mapping == null) throw rowError(i + 1, "平台 SKU 没有有效内部映射：" + platformSku);
                    String mode = text(raw.get("order_mode")); if (mode.isBlank()) mode = String.valueOf(mapping.get("defaultMode")); mode = mode.toUpperCase(Locale.ROOT);
                    if (!Set.of("JIT", "WAREHOUSE").contains(mode)) throw rowError(i + 1, "订单模式只能是 JIT 或 WAREHOUSE");
                    normalized.put("skuId", mapping.get("skuId")); normalized.put("platformSku", platformSku); normalized.put("platformOrderNo", emptyNull(raw.get("platform_order_no"))); normalized.put("orderMode", mode);
                    normalized.put("platformFee", money(raw.get("platform_fee"), false, i + 1)); normalized.put("settlementAmount", money(raw.get("settlement_amount"), true, i + 1));
                } else {
                    String sku = text(raw.get("internal_sku")); Long skuId = lookup("SELECT id FROM skus WHERE internal_sku=? AND status='ACTIVE' AND is_deleted=FALSE", sku);
                    if (skuId == null) throw rowError(i + 1, "内部 SKU 不存在或已停用：" + sku);
                    BigDecimal amount = money(raw.get("amount"), false, i + 1);
                    if (amount.signum() < 0) throw rowError(i + 1, "其他分摊费用不能为负数");
                    normalized.put("skuId", skuId); normalized.put("amount", amount); normalized.put("note", emptyNull(raw.get("note")));
                }
                rows.add(new ImportRow(i + 1, raw, normalized, day));
                if (rows.size() > 20_000) throw new IllegalArgumentException("单文件最多 20,000 行");
            }
            return rows;
        } catch (IOException e) { throw new IllegalArgumentException("无法读取 Excel：" + e.getMessage()); }
    }

    private Long lookup(String sql, Object value) { return db.query(sql, rs -> rs.next() ? rs.getLong(1) : null, value); }
    private static BigDecimal money(Object value, boolean optional, int row) {
        String text = ProfitImportService.text(value); if (text.isBlank() && optional) return BigDecimal.ZERO;
        try { BigDecimal amount = new BigDecimal(text); if (amount.precision() > 18 || Math.max(0, amount.scale()) > 4) throw new NumberFormatException(); return amount; }
        catch (NumberFormatException e) { throw rowError(row, "金额必须是最多 4 位小数的人民币数字"); }
    }
    private static LocalDate parseDate(String value, int row) {
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyy/M/d"), DateTimeFormatter.ofPattern("yyyy/M/dd"), DateTimeFormatter.ofPattern("yyyy/MM/dd"))) {
            try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { }
        }
        throw rowError(row, "日期格式无效，请填写 YYYY-MM-DD");
    }
    private Path saveFile(String batchNo, String filename, MultipartFile file) {
        try { Path day = Path.of(storageDir).toAbsolutePath().normalize().resolve(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()); Files.createDirectories(day); Path target = day.resolve(batchNo + "_" + filename).normalize(); if (!target.startsWith(day)) throw new IllegalArgumentException("文件名无效"); file.transferTo(target); return target; }
        catch (IOException e) { throw new IllegalArgumentException("导入文件保存失败：" + e.getMessage()); }
    }
    private String sha256(MultipartFile file) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes())); } catch (Exception e) { throw new IllegalStateException("文件摘要计算失败", e); } }
    private String writeJson(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("导入行 JSON 序列化失败", e); } }
    private void audit(AuthUser actor, HttpServletRequest request, String action, long id, Map<String,Object> detail) { db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,'import_batch',?,CAST(? AS JSON),?,TRUE)", actor.id(), actor.username(), action, String.valueOf(id), writeJson(detail), request.getRemoteAddr()); }
    private static String text(Object value) { return Objects.toString(value, "").trim(); }
    private static Object emptyNull(Object value) { String s = text(value); return s.isBlank() ? null : s; }
    private static IllegalArgumentException rowError(int row, String message) { return new IllegalArgumentException("第 " + row + " 行：" + message); }
    private record ImportRow(int rowNumber, Map<String,Object> raw, Map<String,Object> normalized, LocalDate date) {}
}
