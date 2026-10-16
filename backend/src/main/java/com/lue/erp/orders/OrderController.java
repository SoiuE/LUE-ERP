package com.lue.erp.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import com.lue.erp.profits.ProfitCalculationService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;import java.nio.file.*;import java.security.MessageDigest;import java.time.*;import java.time.format.DateTimeFormatter;import java.util.*;

@RestController @RequestMapping("/api/orders")
public class OrderController {
 private static final Map<String,String> ORDER_HEADERS=Map.ofEntries(
     Map.entry("店铺标识","shop_identifier"),Map.entry("平台订单号","platform_order_no"),Map.entry("平台明细号","platform_line_no"),Map.entry("订单明细号","platform_line_no"),Map.entry("平台 SKU","platform_sku"),Map.entry("平台SKU","platform_sku"),
     Map.entry("订单模式","order_mode"),Map.entry("数量","quantity"),Map.entry("销售收入（元）","sales_amount"),Map.entry("销售收入","sales_amount"),
     Map.entry("退款金额（元）","refund_amount"),Map.entry("退款金额","refund_amount"),Map.entry("平台费用（元）","platform_fee"),Map.entry("平台费用","platform_fee"),Map.entry("订单日期","order_date"));
 private final JdbcTemplate db; private final AuthService auth; private final ObjectMapper json; private final ProfitCalculationService profitCalculation;
 public OrderController(JdbcTemplate db,AuthService auth,ObjectMapper json,ProfitCalculationService profitCalculation){this.db=db;this.auth=auth;this.json=json;this.profitCalculation=profitCalculation;}
 @GetMapping(value="/template",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
 public org.springframework.http.ResponseEntity<byte[]> template(@AuthenticationPrincipal AuthUser user){
  view(user);
  try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
   Sheet sh=wb.createSheet("订单销量");
   String[] headers={"店铺标识","平台订单号","平台明细号","平台 SKU","订单模式","数量","销售收入（元）","退款金额（元）","平台费用（元）","订单日期"};
   String[] values={"替换为店铺标识","","","平台SKU-001","JIT","1","100.00","0.00","0.00","2026-10-13"};
   Row head=sh.createRow(0);CellStyle headerStyle=wb.createCellStyle();Font font=wb.createFont();font.setBold(true);headerStyle.setFont(font);
   for(int i=0;i<headers.length;i++){Cell cell=head.createCell(i);cell.setCellValue(headers[i]);cell.setCellStyle(headerStyle);sh.setColumnWidth(i,Math.max(18,headers[i].length()*2+4)*256);}
   Row sample=sh.createRow(1);for(int i=0;i<values.length;i++)sample.createCell(i).setCellValue(values[i]);
   sh.createFreezePane(0,1);sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,headers.length-1));
   Sheet guide=wb.createSheet("填写说明");String[][] notes={{"表头","填写说明"},{"店铺标识","必填，填写系统中的店铺账号标识"},{"平台订单号","可空；无订单号时按店铺、平台 SKU、日期和订单模式去重"},{"平台 SKU","必填，用于匹配店铺 SKU 映射"},{"订单模式","填写 JIT 或 WAREHOUSE，留空默认为 JIT"},{"数量","必填，填写销量数量"},{"销售收入（元）","必填，人民币金额"},{"退款金额（元）","选填，人民币金额，留空按 0 处理"},{"平台费用（元）","选填，人民币金额，留空按 0 处理"},{"订单日期","必填，格式 YYYY-MM-DD"},{"示例行","第二行仅供参考，导入前请修改或删除"}};
   for(int r=0;r<notes.length;r++){Row noteRow=guide.createRow(r);for(int c=0;c<notes[r].length;c++)noteRow.createCell(c).setCellValue(notes[r][c]);}
   guide.setColumnWidth(0,24*256);guide.setColumnWidth(1,80*256);guide.createFreezePane(0,1);
   wb.write(out);return org.springframework.http.ResponseEntity.ok().header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=order_summary_import_template.xlsx").body(out.toByteArray());
  }catch(Exception e){throw new IllegalStateException("模板生成失败",e);}
 }
 @GetMapping public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)Long shopId,@RequestParam(required=false)String orderMode,@RequestParam(required=false)String q,@RequestParam(required=false)String dateFrom,@RequestParam(required=false)String dateTo,@AuthenticationPrincipal AuthUser user){view(user);int p=Math.max(1,page),s=Math.min(100,Math.max(1,pageSize));StringBuilder w=new StringBuilder(" WHERE o.is_deleted=FALSE");List<Object>a=new ArrayList<>();if(shopId!=null){w.append(" AND o.shop_id=?");a.add(shopId);}if(orderMode!=null&&!orderMode.isBlank()){w.append(" AND o.order_mode=?");a.add(orderMode);}if(q!=null&&!q.isBlank()){w.append(" AND (o.platform_sku LIKE ? OR COALESCE(sk.internal_sku,'') LIKE ? OR COALESCE(o.platform_order_no,'') LIKE ?)");for(int i=0;i<3;i++)a.add("%"+q.trim()+"%");}if(dateFrom!=null&&!dateFrom.isBlank()){w.append(" AND o.order_date>=?");a.add(dateFrom);}if(dateTo!=null&&!dateTo.isBlank()){w.append(" AND o.order_date<=?");a.add(dateTo);}String from=" FROM order_summaries o JOIN shops sh ON sh.id=o.shop_id LEFT JOIN skus sk ON sk.id=o.sku_id";Long total=db.queryForObject("SELECT COUNT(*)"+from+w,Long.class,a.toArray());a.add(s);a.add((p-1)*s);List<Map<String,Object>>items=db.queryForList("SELECT o.id,o.import_batch_id batchId,sh.shop_name shopName,o.platform_order_no platformOrderNo,o.platform_line_no platformLineNo,o.platform_sku platformSku,sk.internal_sku internalSku,o.order_mode orderMode,o.quantity,o.sales_amount salesAmount,o.refund_amount refundAmount,o.platform_fee platformFee,o.order_date orderDate"+from+w+" ORDER BY o.order_date DESC,o.id DESC LIMIT ? OFFSET ?",a.toArray());return ApiResponse.ok(Map.of("items",items,"total",total==null?0:total,"page",p,"pageSize",s));}
 @Transactional
 @PostMapping(value="/import",consumes="multipart/form-data") public ApiResponse<Map<String,Object>> upload(@RequestPart MultipartFile file,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){auth.requirePermission(user.id(),"menu.imports");auth.requirePermission(user.id(),"button.imports.manage");if(file==null||file.isEmpty()||!Objects.requireNonNullElse(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new IllegalArgumentException("请选择 .xlsx 文件");try{List<Map<String,Object>> rows=parse(file);if(rows.size()>20000)throw new IllegalArgumentException("单文件最多 20,000 行");String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));Integer dup=db.queryForObject("SELECT COUNT(*) FROM import_batches WHERE import_type='order' AND file_sha256=? AND status='SUCCESS' AND is_deleted=FALSE",Integer.class,sha);if(dup!=null&&dup>0)throw new IllegalArgumentException("该订单文件已成功导入");LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));db.update("INSERT INTO import_batch_sequences(sequence_date,sequence_value) VALUES (?,1) ON DUPLICATE KEY UPDATE sequence_value=sequence_value+1",today);Long seq=db.queryForObject("SELECT sequence_value FROM import_batch_sequences WHERE sequence_date=? FOR UPDATE",Long.class,today);String no="IM"+today.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format("%08d",seq);Path dir=Path.of("./data/imports",today.toString());Files.createDirectories(dir);Path saved=dir.resolve(no+"_"+Path.of(file.getOriginalFilename()).getFileName());Files.copy(file.getInputStream(),saved,StandardCopyOption.REPLACE_EXISTING);db.update("INSERT INTO import_batches(batch_no,import_type,original_filename,stored_file_path,file_sha256,status,total_rows,valid_rows,created_by) VALUES (?, 'order',?,?,?,'SUCCESS',?,?,?)",no,file.getOriginalFilename(),saved.toString(),sha,rows.size(),rows.size(),user.id());Long batch=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);int imported=0,unmatched=0;for(int i=0;i<rows.size();i++){Map<String,Object> r=rows.get(i);String raw=json.writeValueAsString(r);Long shop=db.query("SELECT id FROM shops WHERE shop_identifier=? AND status='ACTIVE' AND is_deleted=FALSE",rs->rs.next()?rs.getLong(1):null,String.valueOf(r.get("shop_identifier")));if(shop==null)throw new IllegalArgumentException("店铺不存在："+r.get("shop_identifier"));Long sku=db.query("SELECT m.sku_id FROM shop_sku_mappings m WHERE m.shop_id=? AND m.platform_sku=? AND m.status='ACTIVE' AND m.is_deleted=FALSE",rs->rs.next()?rs.getLong(1):null,shop,r.get("platform_sku"));String mode=String.valueOf(r.getOrDefault("order_mode","JIT")).toUpperCase(Locale.ROOT);if(mode.isBlank()||mode.equals("NULL"))mode="JIT";String order=String.valueOf(r.getOrDefault("platform_order_no",""));if(order.equals("null"))order="";String key=order.isBlank()?shop+"|"+r.get("platform_sku")+"|"+r.get("order_date")+"|"+mode:shop+"|"+order+"|"+r.get("platform_sku");String bk=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));db.update("INSERT INTO order_summaries(import_batch_id,shop_id,platform_order_no,platform_line_no,platform_sku,sku_id,order_mode,quantity,sales_amount,refund_amount,platform_fee,order_date,business_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE import_batch_id=VALUES(import_batch_id),platform_line_no=COALESCE(VALUES(platform_line_no),platform_line_no),sku_id=VALUES(sku_id),quantity=VALUES(quantity),sales_amount=VALUES(sales_amount),refund_amount=VALUES(refund_amount),platform_fee=VALUES(platform_fee)",batch,shop,order.isBlank()?null:order,Objects.toString(r.get("platform_line_no"),"").isBlank()?null:r.get("platform_line_no"),r.get("platform_sku"),sku,mode,r.get("quantity"),r.get("sales_amount"),r.get("refund_amount"),r.get("platform_fee"),r.get("order_date"),bk);Long oid=db.queryForObject("SELECT id FROM order_summaries WHERE shop_id=? AND business_key=?",Long.class,shop,bk);db.update("INSERT INTO raw_import_rows(batch_id,source_row_number,raw_data,normalized_data,row_status,imported_record_id) VALUES (?,?,CAST(? AS JSON),CAST(? AS JSON),'IMPORTED',?)",batch,i+2,raw,raw,oid);if(sku==null){unmatched++;db.update("INSERT INTO exceptions(exception_no,exception_type,shop_id,platform_sku,order_summary_id,raw_data,title) VALUES (?,?,?,?,?,CAST(? AS JSON),?)",exceptionNo(),"SKU_UNMATCHED",shop,r.get("platform_sku"),oid,raw,"订单 SKU 未匹配："+r.get("platform_sku"));}imported++;}db.update("UPDATE import_batches SET imported_rows=? WHERE id=?",imported,batch);if(!rows.isEmpty()){LocalDate min=rows.stream().map(r->(LocalDate)r.get("order_date")).min(LocalDate::compareTo).orElseThrow();LocalDate max=rows.stream().map(r->(LocalDate)r.get("order_date")).max(LocalDate::compareTo).orElseThrow();profitCalculation.recalculate(min,max,"ORDER_IMPORT",user.id(),null,null);}return ApiResponse.ok(Map.of("batchNo",no,"totalRows",rows.size(),"importedRows",imported,"unmatchedRows",unmatched));}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("订单导入失败："+e.getMessage());}}
 private List<Map<String,Object>> parse(MultipartFile file)throws Exception{
  try(XSSFWorkbook wb=new XSSFWorkbook(file.getInputStream())){
   Sheet sh=wb.getSheetAt(0);DataFormatter f=new DataFormatter(Locale.ROOT);Row h=sh.getRow(0);if(h==null)throw new IllegalArgumentException("缺少表头行");
   Map<String,Integer> ix=new LinkedHashMap<>();
   for(int c=0;c<h.getLastCellNum();c++){
    String label=f.formatCellValue(h.getCell(c)).trim();String key=ORDER_HEADERS.getOrDefault(label,label);
    if(!key.isBlank()&&ix.putIfAbsent(key,c)!=null)throw new IllegalArgumentException("表头重复："+label);
   }
   for(String required:List.of("shop_identifier","platform_sku","quantity","sales_amount","order_date"))if(!ix.containsKey(required))throw new IllegalArgumentException("缺少必填表头："+required);
   List<Map<String,Object>>out=new ArrayList<>();
   for(int n=1;n<=sh.getLastRowNum();n++){
    Row row=sh.getRow(n);if(row==null)continue;Map<String,Object>v=new LinkedHashMap<>();
    for(String col:ix.keySet())v.put(col,f.formatCellValue(row.getCell(ix.get(col))).trim());
    if(String.valueOf(v.get("shop_identifier")).isBlank()||String.valueOf(v.get("shop_identifier")).startsWith("替换为"))continue;
    String mode=String.valueOf(v.getOrDefault("order_mode","JIT")).toUpperCase(Locale.ROOT);if(mode.isBlank())mode="JIT";if(!Set.of("JIT","WAREHOUSE").contains(mode))throw new IllegalArgumentException("第"+(n+1)+"行订单模式无效");v.put("order_mode",mode);
    for(String col:List.of("quantity","sales_amount","refund_amount","platform_fee")){String x=String.valueOf(v.getOrDefault(col,"0"));if(x.isBlank())x="0";v.put(col,new java.math.BigDecimal(x));}
    String dt=String.valueOf(v.get("order_date"));v.put("order_date",LocalDate.parse(dt.length()>10?dt.substring(0,10):dt));
    if(String.valueOf(v.get("platform_sku")).isBlank())throw new IllegalArgumentException("第"+(n+1)+"行平台 SKU 不能为空");out.add(v);
   }
   return out;
  }
 }
 private String exceptionNo(){return "EX"+LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE)+String.format("%06d",System.nanoTime()%1000000);}
 private void view(AuthUser u){auth.requirePermission(u.id(),"menu.imports");}
}
