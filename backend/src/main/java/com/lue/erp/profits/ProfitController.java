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
    private final SettlementImportService settlements;
    private final ProfitMatchService matching;
    private final ProfitReportService reports;

    public ProfitController(JdbcTemplate db, AuthService auth, ObjectMapper json, ProfitCalculationService calculation, ProfitImportService imports, SettlementImportService settlements, ProfitMatchService matching, ProfitReportService reports) {
        this.db = db; this.auth = auth; this.json = json; this.calculation = calculation; this.imports = imports; this.settlements=settlements;this.matching=matching;this.reports = reports;
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

    @PostMapping("/recalculate")
    public ApiResponse<Map<String,Object>> recalculate(@RequestBody Map<String,Object> body, @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        view(user); auth.requirePermission(user.id(), "button.profits.recalculate");
        LocalDate from = date(Objects.toString(body.get("dateFrom"), null));
        LocalDate to = date(Objects.toString(body.get("dateTo"), null));
        if (from == null || to == null) throw new IllegalArgumentException("请填写重算开始日期和结束日期");
        Long shop=body.get("shopId")==null?null:Long.valueOf(body.get("shopId").toString());Long sku=body.get("skuId")==null?null:Long.valueOf(body.get("skuId").toString());
        Map<String,Object> result = calculation.recalculate(from, to,"MANUAL",user.id(),shop,sku);
        audit(user, request, "profit.recalculate", from + ".." + to, Map.of("dateFrom", from.toString(), "dateTo", to.toString(), "rows", result.get("rows")));
        return ApiResponse.ok(result);
    }

    @PostMapping(value="/imports/{type}", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> upload(@PathVariable String type, @RequestParam(defaultValue="false") boolean forceReimport,
        @RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        view(user); auth.requirePermission(user.id(), "button.profits.import");
        if("settlement".equals(type)){Map<String,Object> result=settlements.upload(file,user);String from=Objects.toString(result.get("dateFrom"),"");if(!from.isBlank()&&!Boolean.TRUE.equals(result.get("duplicate"))){Map<String,Object> recalc=calculation.recalculate(LocalDate.parse(from),LocalDate.parse(result.get("dateTo").toString()),"SETTLEMENT_IMPORT",user.id(),null,null);result=new LinkedHashMap<>(result);result.put("recalculatedRows",recalc.get("rows"));}return ApiResponse.ok(result);}
        return ApiResponse.ok(imports.upload(type, forceReimport, file, user, request));
    }

    @PostMapping(value="/settlements/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> previewSettlement(@RequestPart("file") MultipartFile file,@AuthenticationPrincipal AuthUser user){view(user);auth.requirePermission(user.id(),"button.profits.import");return ApiResponse.ok(settlements.preview(file));}

    @GetMapping("/settlements")
    public ApiResponse<Map<String,Object>> settlementLines(@RequestParam(required=false)String settlementFrom,@RequestParam(required=false)String settlementTo,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int pageSize,@AuthenticationPrincipal AuthUser user){view(user);int p=Math.max(1,page),size=Math.min(100,Math.max(1,pageSize));StringBuilder where=new StringBuilder(" WHERE s.is_deleted=FALSE");List<Object> args=new ArrayList<>();if(settlementFrom!=null&&!settlementFrom.isBlank()){where.append(" AND s.settlement_date>=?");args.add(LocalDate.parse(settlementFrom));}if(settlementTo!=null&&!settlementTo.isBlank()){where.append(" AND s.settlement_date<=?");args.add(LocalDate.parse(settlementTo));}if(status!=null&&!status.isBlank()){where.append(" AND s.match_status=?");args.add(status);}Long total=db.queryForObject("SELECT COUNT(*) FROM settlement_summaries s"+where,Long.class,args.toArray());args.add(size);args.add((p-1)*size);List<Map<String,Object>> lines=db.queryForList("SELECT s.id,s.import_batch_id batchId,b.batch_no batchNo,s.source_row_number sourceRow,s.platform_bill_no platformBillNo,s.platform_line_no platformLineNo,s.platform_order_line_no platformOrderLineNo,s.platform_order_no platformOrderNo,s.platform_sku platformSku,s.settlement_date settlementDate,s.platform_fee platformFee,s.settlement_amount cashAmount,s.adjustment_type adjustmentType,s.adjustment_amount adjustmentAmount,s.settlement_complete settlementComplete,s.match_status matchStatus,s.match_reason matchReason,s.order_summary_id orderId,o.order_date orderDate,sh.shop_name shopName FROM settlement_summaries s JOIN import_batches b ON b.id=s.import_batch_id LEFT JOIN order_summaries o ON o.id=s.order_summary_id LEFT JOIN shops sh ON sh.id=s.shop_id"+where+" ORDER BY s.settlement_date DESC,s.id DESC LIMIT ? OFFSET ?",args.toArray());return ApiResponse.ok(Map.of("items",lines,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",size));}

    @GetMapping("/settlements/{id}/candidates")
    public ApiResponse<Map<String,Object>> settlementCandidates(@PathVariable long id,@AuthenticationPrincipal AuthUser user){view(user);Map<String,Object> s=db.queryForMap("SELECT shop_id shopId,platform_order_no orderNo,platform_sku platformSku,sku_id skuId,order_mode orderMode FROM settlement_summaries WHERE id=? AND is_deleted=FALSE",id);return ApiResponse.ok(Map.of("items",db.queryForList("SELECT id orderId,platform_order_no platformOrderNo,platform_line_no platformLineNo,platform_sku platformSku,order_date orderDate,quantity,sales_amount salesRevenue FROM order_summaries WHERE shop_id=? AND platform_sku=? AND sku_id=? AND order_mode=? AND is_deleted=FALSE AND (? IS NULL OR platform_order_no=?) ORDER BY order_date DESC,id DESC LIMIT 50",s.get("shopId"),s.get("platformSku"),s.get("skuId"),s.get("orderMode"),s.get("orderNo"),s.get("orderNo"))));}

    @PostMapping("/settlements/{id}/match")
    public ApiResponse<Map<String,Object>> repairSettlement(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){view(user);auth.requirePermission(user.id(),"button.profits.import");long order=Long.parseLong(String.valueOf(body.get("orderId")));LocalDate day=matching.repairSettlement(id,order,user.id());Map<String,Object> result=calculation.recalculate(day,day,"MATCH_REPAIR",user.id(),null,null);audit(user,request,"profit.settlement.match",String.valueOf(id),Map.of("orderId",order,"runId",result.get("runId")));return ApiResponse.ok(Map.of("matched",true,"orderId",order,"recalculated",result.get("rows")));}

    @PostMapping("/settlements/{id}/adjustment-type")
    public ApiResponse<Map<String,Object>> mapSettlementAdjustment(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){view(user);auth.requirePermission(user.id(),"button.profits.import");String type=Objects.toString(body.get("adjustmentType"),"");LocalDate day=matching.mapSettlementAdjustment(id,type);Map<String,Object> result=new LinkedHashMap<>();result.put("matched",day!=null);if(day!=null){Map<String,Object> recalc=calculation.recalculate(day,day,"MATCH_REPAIR",user.id(),null,null);result.put("recalculated",recalc.get("rows"));}audit(user,request,"profit.settlement.adjustment.map",String.valueOf(id),Map.of("adjustmentType",type,"matched",day!=null));return ApiResponse.ok(result);}

    @PostMapping("/fulfillment-items/{id}/match")
    public ApiResponse<Map<String,Object>> repairFulfillment(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){view(user);auth.requirePermission(user.id(),"button.profits.recalculate");long order=Long.parseLong(String.valueOf(body.get("orderId")));LocalDate day=matching.repairFulfillment(id,order,user.id());Map<String,Object> result=calculation.recalculate(day,day,"MATCH_REPAIR",user.id(),null,null);audit(user,request,"profit.fulfillment.match",String.valueOf(id),Map.of("orderId",order,"runId",result.get("runId")));return ApiResponse.ok(Map.of("matched",true,"orderId",order,"recalculated",result.get("rows")));}

    @GetMapping("/order-facts")
    public ApiResponse<Map<String,Object>> orderFacts(@RequestParam(required=false) String dateFrom,@RequestParam(required=false) String dateTo,@RequestParam(required=false) Long shopId,@RequestParam(required=false) Long skuId,@RequestParam(required=false) String orderMode,@RequestParam(defaultValue="1")int page,@AuthenticationPrincipal AuthUser user){view(user);if(dateFrom==null||dateTo==null)throw new IllegalArgumentException("订单利润下钻需指定日期范围");LocalDate from=LocalDate.parse(dateFrom),to=LocalDate.parse(dateTo);if(to.isBefore(from)||to.isAfter(from.plusYears(2)))throw new IllegalArgumentException("订单利润下钻范围无效");int p=Math.max(1,page);StringBuilder where=new StringBuilder(" WHERE f.order_date BETWEEN ? AND ?");List<Object> args=new ArrayList<>(List.of(from,to));if(shopId!=null){where.append(" AND f.shop_id=?");args.add(shopId);}if(skuId!=null){where.append(" AND f.sku_id=?");args.add(skuId);}if(orderMode!=null&&!orderMode.isBlank()){where.append(" AND f.order_mode=?");args.add(orderMode);}Long total=db.queryForObject("SELECT COUNT(*) FROM order_profit_facts f"+where,Long.class,args.toArray());args.add(100);args.add((p-1)*100);List<Map<String,Object>> items=db.queryForList("SELECT f.order_summary_id orderId,f.order_date orderDate,o.platform_order_no platformOrderNo,o.platform_line_no platformLineNo,o.platform_sku platformSku,f.sales_qty salesQty,f.sales_revenue salesRevenue,f.purchase_cost purchaseCost,f.package_cost packageCost,f.gross_profit grossProfit,f.cost_basis costBasis,f.settlement_status settlementStatus,f.fulfilled_qty fulfilledQty,f.settlement_cash_amount settlementCashAmount,f.settlement_difference settlementDifference FROM order_profit_facts f JOIN order_summaries o ON o.id=f.order_summary_id"+where+" ORDER BY f.order_summary_id LIMIT ? OFFSET ?",args.toArray());return ApiResponse.ok(Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p));}

    @GetMapping("/orders/{id}/trace")
    public ApiResponse<Map<String,Object>> trace(@PathVariable long id,@AuthenticationPrincipal AuthUser user){view(user);Map<String,Object> result=new LinkedHashMap<>();result.put("order",db.queryForMap("SELECT o.id,o.platform_order_no platformOrderNo,o.platform_line_no platformLineNo,o.platform_sku platformSku,o.order_date orderDate,o.quantity,o.sales_amount salesRevenue,f.cost_basis costBasis,f.settlement_status settlementStatus,f.purchase_cost purchaseCost,f.package_cost packageCost,f.settlement_difference settlementDifference,f.settlement_cash_amount settlementCashAmount FROM order_summaries o JOIN order_profit_facts f ON f.order_summary_id=o.id WHERE o.id=?",id));
        result.put("fulfillments",db.queryForList("SELECT i.id fulfillmentItemId,d.id documentId,d.document_no documentNo,d.source_document_id sourceDocumentId,x.id dispatchId,x.dispatch_no dispatchNo,x.handed_off_at shippedAt,di.composition_version_id compositionVersionId,di.shipped_qty shippedQty FROM fulfillment_order_matches m JOIN fulfillment_document_items i ON i.id=m.fulfillment_item_id JOIN fulfillment_documents d ON d.id=i.document_id JOIN fulfillment_dispatch_items di ON di.document_item_id=i.id JOIN fulfillment_dispatches x ON x.id=di.dispatch_id WHERE m.order_summary_id=? AND x.status='HANDED_OFF' ORDER BY x.id",id));
        result.put("goodsOutbounds",db.queryForList("SELECT sd.id stockDocumentId,sd.fulfillment_dispatch_id dispatchId,sd.fulfillment_document_item_id fulfillmentItemId,g.goods_code goodsCode,l.id locationId,l.location_code locationCode,sd.quantity,sd.unit_cost_snapshot unitCostSnapshot,sd.cost_amount_snapshot costAmountSnapshot,sd.created_at shippedAt,sd.warehouse_id warehouseId,sd.warehouse_goods_id goodsId FROM fulfillment_order_matches m JOIN warehouse_goods_stock_documents sd ON sd.fulfillment_document_item_id=m.fulfillment_item_id AND sd.document_type='FULFILLMENT_OUTBOUND' JOIN warehouse_goods g ON g.id=sd.warehouse_goods_id JOIN warehouse_locations l ON l.id=sd.source_location_id WHERE m.order_summary_id=? ORDER BY sd.id",id));
        result.put("purchaseCostSources",db.queryForList("SELECT cl.id layerId,cl.warehouse_goods_id goodsId,cl.quantity receiptQty,cl.actual_unit_price actualUnitPrice,cl.amount,pr.receipt_no receiptNo,pr.received_at receivedAt FROM warehouse_goods_cost_layers cl JOIN purchase_receipts pr ON pr.id=cl.purchase_receipt_id WHERE EXISTS(SELECT 1 FROM fulfillment_order_matches m JOIN warehouse_goods_stock_documents sd ON sd.fulfillment_document_item_id=m.fulfillment_item_id WHERE m.order_summary_id=? AND sd.document_type='FULFILLMENT_OUTBOUND' AND sd.warehouse_id=cl.warehouse_id AND sd.warehouse_goods_id=cl.warehouse_goods_id AND pr.received_at<=sd.created_at) ORDER BY cl.id DESC LIMIT 200",id));
        result.put("packageFlows",db.queryForList("SELECT p.id packageDocumentId,p.fulfillment_dispatch_id dispatchId,p.fulfillment_document_item_id fulfillmentItemId,m.material_code materialCode,l.id locationId,l.location_code locationCode,p.quantity,p.unit_cost_snapshot unitCostSnapshot,p.cost_amount_snapshot costAmountSnapshot,p.created_at consumedAt FROM fulfillment_order_matches x JOIN package_location_documents p ON p.fulfillment_document_item_id=x.fulfillment_item_id AND p.document_type='FULFILLMENT_CONSUMPTION' JOIN package_materials m ON m.id=p.material_id JOIN warehouse_locations l ON l.id=p.location_id WHERE x.order_summary_id=? ORDER BY p.id",id));
        result.put("settlements",db.queryForList("SELECT id,platform_bill_no platformBillNo,platform_line_no platformLineNo,settlement_date settlementDate,platform_fee platformFee,settlement_amount cashAmount,adjustment_type adjustmentType,adjustment_amount adjustmentAmount,settlement_complete settlementComplete,match_status matchStatus FROM settlement_summaries WHERE order_summary_id=? AND is_deleted=FALSE ORDER BY settlement_date,id",id));return ApiResponse.ok(result);}

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
        keys.addAll(List.of("salesQty", "salesRevenue", "platformFee", "purchaseCost", "packageCost", "refundLoss", "otherCost", "grossProfit", "grossMargin", "actualCostOrders", "mixedCostOrders", "legacyCostOrders", "costUnmatchedOrders", "estimatedOrders", "partialSettlementOrders", "settledOrders", "settlementDifference"));
        return keys;
    }
    private static String label(String key) { return switch (key) {
        case "reportDate" -> "日期"; case "reportMonth" -> "月份"; case "shopName" -> "店铺"; case "internalSku" -> "内部 SKU"; case "productName" -> "商品名称"; case "orderMode" -> "订单模式";
        case "salesQty" -> "销售数量"; case "salesRevenue" -> "销售收入（元）"; case "platformFee" -> "平台费用（元）"; case "purchaseCost" -> "采购成本（元）"; case "packageCost" -> "包材成本（元）"; case "refundLoss" -> "退款售后损失（元）"; case "otherCost" -> "其他分摊费用（元）"; case "grossProfit" -> "SKU 毛利（元）"; case "grossMargin" -> "SKU 毛利率"; case "actualCostOrders" -> "实际成本订单";case "mixedCostOrders" -> "混合暂估订单";case "legacyCostOrders" -> "旧口径订单";case "costUnmatchedOrders" -> "成本待匹配订单";case "estimatedOrders" -> "暂估订单";case "partialSettlementOrders" -> "部分结算订单";case "settledOrders" -> "已结算订单";case "settlementDifference" -> "结算利润差额";default -> key;
    }; }
}
