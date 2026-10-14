package com.lue.erp.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ImportCenterService {
    public static final int MAX_ROWS = 20_000;
    private static final Set<String> IMPORT_TYPES = Set.of("sku", "shop_sku_mapping");
    private static final Map<String,List<String>> HEADERS = Map.of(
        "sku", List.of("internal_sku", "product_name", "spu", "name_zh", "name_en", "specification", "color", "size", "barcode", "category", "supplier_code", "purchase_price", "supply_price", "weight", "length", "width", "height", "default_product_packaging_plan", "default_shipping_material_plan", "image_url", "status", "note"),
        "shop_sku_mapping", List.of("shop_identifier", "platform_sku", "platform_product_id", "platform_product_name", "internal_sku", "default_order_mode", "status")
    );
    private static final Map<String,Map<String,String>> HEADER_LABELS = Map.of(
        "sku", Map.ofEntries(
            Map.entry("internal_sku","内部 SKU"),Map.entry("product_name","商品名称"),Map.entry("spu","SPU"),Map.entry("name_zh","中文名称"),Map.entry("name_en","英文名称"),
            Map.entry("specification","规格"),Map.entry("color","颜色"),Map.entry("size","尺码"),Map.entry("barcode","条码"),Map.entry("category","品类"),Map.entry("supplier_code","供应商编码"),
            Map.entry("purchase_price","采购价（元）"),Map.entry("supply_price","供货价（元）"),Map.entry("weight","重量"),Map.entry("length","长度"),Map.entry("width","宽度"),Map.entry("height","高度"),
            Map.entry("default_product_packaging_plan","默认产品包装方案（JSON）"),Map.entry("default_shipping_material_plan","默认发货打包材料方案（JSON）"),Map.entry("image_url","商品图片链接"),Map.entry("status","状态"),Map.entry("note","备注")
        ),
        "shop_sku_mapping", Map.of(
            "shop_identifier","店铺标识","platform_sku","平台 SKU","platform_product_id","平台商品 ID","platform_product_name","平台商品名称","internal_sku","内部 SKU","default_order_mode","默认订单模式","status","状态"
        )
    );
    private static final List<String> SKU_FIELDS = List.of("spu", "product_name", "name_zh", "name_en", "specification", "color", "size", "barcode", "category", "supplier_id", "purchase_price", "supply_price", "weight", "length", "width", "height", "default_product_packaging_plan", "default_shipping_material_plan", "image_url", "status", "note");
    private static final List<String> DECIMAL_FIELDS = List.of("purchase_price", "supply_price", "weight", "length", "width", "height");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactionTemplate;
    @Value("${lue.import.storage-dir:./data/imports}") private String storageDir;

    public ImportCenterService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc; this.mapper=mapper; this.transactionTemplate=new TransactionTemplate(transactionManager);
    }

    public Map<String,Object> list(int page, int pageSize) {
        int safePage=Math.max(page,1), safeSize=Math.min(100,Math.max(1,pageSize));
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE is_deleted=FALSE",Long.class);
        List<Map<String,Object>> items=jdbc.queryForList("SELECT b.id,b.batch_no AS batchNo,b.import_type AS importType,b.original_filename AS originalFilename,b.status,b.force_reimport AS forceReimport,b.total_rows AS totalRows,b.valid_rows AS validRows,b.error_rows AS errorRows,b.imported_rows AS importedRows,b.error_message AS errorMessage,b.created_at AS createdAt,u.display_name AS createdBy FROM import_batches b LEFT JOIN users u ON u.id=b.created_by WHERE b.is_deleted=FALSE ORDER BY b.id DESC LIMIT ? OFFSET ?",safeSize,(safePage-1)*safeSize);
        return Map.of("items",items,"page",safePage,"pageSize",safeSize,"total",total==null?0L:total);
    }

    @Transactional
    public Map<String,Object> upload(String importType, boolean forceReimport, MultipartFile file, AuthUser actor, HttpServletRequest request) {
        requireType(importType);
        if(file==null||file.isEmpty()) throw new IllegalArgumentException("请选择 .xlsx 文件");
        String original=Path.of(Objects.requireNonNullElse(file.getOriginalFilename(),"upload.xlsx")).getFileName().toString();
        if(!original.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("仅支持 .xlsx 文件，不支持 .xls");
        if(file.getSize()>50L*1024*1024) throw new IllegalArgumentException("文件最大支持 50 MB");
        Parsed parsed;
        try(InputStream input=file.getInputStream()) { parsed=parseXlsx(input,importType); }
        catch(IOException e) { throw new IllegalArgumentException("无法读取 Excel 文件："+e.getMessage()); }
        String sha=sha256(file);
        if(!forceReimport) {
            Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE import_type=? AND file_sha256=? AND status='SUCCESS' AND is_deleted=FALSE",Integer.class,importType,sha);
            if(duplicate!=null&&duplicate>0) throw new IllegalArgumentException("该文件已成功导入；如需覆盖重导，请勾选“强制重导入”后重新上传");
        }
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO import_batch_sequences(sequence_date,sequence_value) VALUES (?,1) ON DUPLICATE KEY UPDATE sequence_value=sequence_value+1",today);
        Long sequence=jdbc.queryForObject("SELECT sequence_value FROM import_batch_sequences WHERE sequence_date=? FOR UPDATE",Long.class,today);
        String batchNo="IM"+today.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format(Locale.ROOT,"%08d",sequence);
        Path savedPath=saveFile(batchNo,original,file);
        List<RowCheck> checked=new ArrayList<>();
        Set<String> keys=new HashSet<>();
        for(ParsedRow row:parsed.rows) {
            RowCheck check=validateRow(importType,row.number,new LinkedHashMap<>(row.values),forceReimport);
            String key="sku".equals(importType)?str(row.values.get("internal_sku")):str(row.values.get("shop_identifier"))+"\u0000"+str(row.values.get("platform_sku"));
            if(key!=null&&!key.equals("null")&&!key.contains("null\u0000null")&&!keys.add(key.toUpperCase(Locale.ROOT)))check.errors.add("本文件中存在重复的唯一键");
            checked.add(check);
        }
        long errorCount=checked.stream().filter(c->!c.errors.isEmpty()).count();
        String status=errorCount==0?"PENDING":"FAILED";
        try {
            jdbc.update("INSERT INTO import_batches(batch_no,import_type,original_filename,stored_file_path,file_sha256,force_reimport,status,total_rows,valid_rows,error_rows,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",batchNo,importType,original,savedPath.toString(),sha,forceReimport,status,checked.size(),checked.size()-(int)errorCount,(int)errorCount,actor.id());
            Long batchId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            for(int i=0;i<parsed.rows.size();i++) {
                ParsedRow row=parsed.rows.get(i); RowCheck check=checked.get(i);
                jdbc.update("INSERT INTO raw_import_rows(batch_id,source_row_number,raw_data,normalized_data,validation_errors,row_status) VALUES (?,?,CAST(? AS JSON),CAST(? AS JSON),?,?)",batchId,row.number,json(row.values),json(check.normalized),check.errors.isEmpty()?null:json(check.errors),check.errors.isEmpty()?"VALID":"ERROR");
            }
            audit(actor,request,"import.upload",batchId,Map.of("batchNo",batchNo,"importType",importType,"totalRows",checked.size(),"errorRows",errorCount,"forceReimport",forceReimport));
            return detail(batchId,1,50);
        } catch(RuntimeException e) { try{Files.deleteIfExists(savedPath);}catch(IOException ignored){} throw e; }
    }

    public Map<String,Object> detail(long id,int page,int pageSize) {
        Map<String,Object> batch=jdbc.queryForMap("SELECT id,batch_no AS batchNo,import_type AS importType,original_filename AS originalFilename,status,force_reimport AS forceReimport,total_rows AS totalRows,valid_rows AS validRows,error_rows AS errorRows,imported_rows AS importedRows,error_message AS errorMessage,created_at AS createdAt FROM import_batches WHERE id=? AND is_deleted=FALSE",id);
        int safePage=Math.max(1,page),safeSize=Math.min(100,Math.max(1,pageSize));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,source_row_number AS rowNumber,raw_data AS rawData,validation_errors AS validationErrors,row_status AS rowStatus,imported_record_id AS importedRecordId FROM raw_import_rows WHERE batch_id=? AND is_deleted=FALSE ORDER BY source_row_number LIMIT ? OFFSET ?",id,safeSize,(safePage-1)*safeSize);
        Long previewTotal=jdbc.queryForObject("SELECT COUNT(*) FROM raw_import_rows WHERE batch_id=? AND is_deleted=FALSE",Long.class,id);
        batch.put("rows",rows);
        batch.put("previewTotal",previewTotal);
        batch.put("previewPage",safePage);
        batch.put("previewPageSize",safeSize);
        return batch;
    }

    public byte[] originalFile(long id) {
        String stored=jdbc.queryForObject("SELECT stored_file_path FROM import_batches WHERE id=? AND is_deleted=FALSE",String.class,id);
        try { return Files.readAllBytes(Path.of(stored)); } catch(IOException e) { throw new IllegalArgumentException("原始文件不存在或无法读取"); }
    }

    public byte[] failuresCsv(long id) {
        Map<String,Object> batch=jdbc.queryForMap("SELECT batch_no AS batchNo,import_type AS importType,error_message AS errorMessage FROM import_batches WHERE id=? AND is_deleted=FALSE",id);
        List<Map<String,Object>> errors=jdbc.queryForList("SELECT source_row_number AS rowNumber,raw_data AS rawData,validation_errors AS validationErrors FROM raw_import_rows WHERE batch_id=? AND row_status='ERROR' AND is_deleted=FALSE ORDER BY source_row_number",id);
        StringBuilder csv=new StringBuilder("\uFEFF行号,原始数据,失败原因\r\n");
        for(Map<String,Object> row:errors) csv.append(csv(row.get("rowNumber"))).append(',').append(csv(row.get("rawData"))).append(',').append(csv(row.get("validationErrors"))).append("\r\n");
        if(errors.isEmpty()&&batch.get("errorMessage")!=null) csv.append("0,,").append(csv(batch.get("errorMessage"))).append("\r\n");
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] template(String type) {
        requireType(type);
        try(XSSFWorkbook workbook=new XSSFWorkbook(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            Sheet sheet=workbook.createSheet(type.equals("sku")?"SKU":"店铺SKU映射");
            Row header=sheet.createRow(0); CellStyle style=workbook.createCellStyle(); Font font=workbook.createFont();font.setBold(true);style.setFont(font);
            List<String> headers=HEADERS.get(type);
            for(int i=0;i<headers.size();i++){String label=HEADER_LABELS.get(type).get(headers.get(i));Cell cell=header.createCell(i);cell.setCellValue(label);cell.setCellStyle(style);sheet.setColumnWidth(i,Math.min(48*256,Math.max(18*256,label.length()*256+6*256)));}
            sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,headers.size()-1));
            Row sample=sheet.createRow(1);
            Map<String,String> samples=type.equals("sku")?Map.of("internal_sku","SKU-EXAMPLE-001","product_name","示例商品","purchase_price","12.5000","supply_price","18.0000","status","ACTIVE"):
                Map.of("shop_identifier","店铺账号标识","platform_sku","平台SKU-001","internal_sku","SKU-EXAMPLE-001","default_order_mode","JIT","status","ACTIVE");
            for(int i=0;i<headers.size();i++)sample.createCell(i).setCellValue(samples.getOrDefault(headers.get(i),""));
            Sheet guide=workbook.createSheet("填写说明");
            String[][] instructions=templateInstructions(type);
            for(int r=0;r<instructions.length;r++){Row row=guide.createRow(r);for(int c=0;c<instructions[r].length;c++){Cell cell=row.createCell(c);cell.setCellValue(instructions[r][c]);if(r==0)cell.setCellStyle(style);}}
            guide.setColumnWidth(0,30*256);guide.setColumnWidth(1,16*256);guide.setColumnWidth(2,72*256);guide.setColumnWidth(3,30*256);guide.createFreezePane(0,1);guide.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,instructions.length-1,0,3));
            workbook.write(output);return output.toByteArray();
        } catch(IOException e){throw new IllegalStateException("模板生成失败",e);}
    }

    public String templateFilename(String type){requireType(type);return type.equals("sku")?"sku_import_template.xlsx":"shop_sku_mapping_template.xlsx";}

    public Map<String,Object> commit(long id, AuthUser actor, HttpServletRequest request) {
        Map<String,Object> batch=jdbc.queryForMap("SELECT id,batch_no AS batchNo,import_type AS importType,status,force_reimport AS forceReimport,error_rows AS errorRows FROM import_batches WHERE id=? AND is_deleted=FALSE",id);
        if("SUCCESS".equals(batch.get("status"))) throw new IllegalArgumentException("该批次已导入成功");
        if(!"PENDING".equals(batch.get("status"))) throw new IllegalArgumentException("批次存在校验错误，请下载失败明细并修正后重新上传");
        try {
            Integer rows=transactionTemplate.execute(status->{
                List<Map<String,Object>> rawRows=jdbc.queryForList("SELECT id,source_row_number AS rowNumber,normalized_data AS rawData FROM raw_import_rows WHERE batch_id=? AND row_status='VALID' AND is_deleted=FALSE ORDER BY source_row_number",id);
                for(Map<String,Object> raw:rawRows) {
                    Map<String,Object> values=parseRaw(raw.get("rawData"));
                    Long recordId="sku".equals(batch.get("importType"))?writeSku(values,(boolean)batch.get("forceReimport")):writeMapping(values,(boolean)batch.get("forceReimport"));
                    jdbc.update("UPDATE raw_import_rows SET row_status='IMPORTED',imported_record_id=? WHERE id=?",recordId,raw.get("id"));
                }
                jdbc.update("UPDATE import_batches SET status='SUCCESS',imported_rows=?,error_message=NULL WHERE id=?",rawRows.size(),id);
                audit(actor,request,"import.commit",id,Map.of("batchNo",batch.get("batchNo"),"importType",batch.get("importType"),"importedRows",rawRows.size()));
                return rawRows.size();
            });
            return Map.of("batchId",id,"batchNo",batch.get("batchNo"),"status","SUCCESS","importedRows",rows==null?0:rows);
        } catch(RuntimeException e) {
            String message=rootMessage(e);
            jdbc.update("UPDATE import_batches SET status='FAILED',error_rows=GREATEST(error_rows,1),error_message=? WHERE id=?",message,id);
            jdbc.update("INSERT INTO raw_import_rows(batch_id,source_row_number,raw_data,validation_errors,row_status) VALUES (?,0,JSON_OBJECT(),JSON_ARRAY(?),'ERROR') ON DUPLICATE KEY UPDATE validation_errors=JSON_ARRAY(?),row_status='ERROR'",id,message,message);
            audit(actor,request,"import.commit_failed",id,Map.of("error",message));
            throw new IllegalArgumentException("导入事务已回滚，正式数据未写入："+message);
        }
    }

    private Parsed parseXlsx(InputStream input,String type) {
        try(XSSFWorkbook workbook=new XSSFWorkbook(input)) {
            Sheet sheet=workbook.getNumberOfSheets()==0?null:workbook.getSheetAt(0);
            if(sheet==null||sheet.getPhysicalNumberOfRows()==0)throw new IllegalArgumentException("Excel 工作表为空");
            DataFormatter formatter=new DataFormatter(Locale.ROOT); FormulaEvaluator evaluator=workbook.getCreationHelper().createFormulaEvaluator();
            Row headerRow=sheet.getRow(sheet.getFirstRowNum()); if(headerRow==null)throw new IllegalArgumentException("缺少表头行");
            Map<Integer,String> columns=new LinkedHashMap<>(); Set<String> found=new HashSet<>();
            for(int c=headerRow.getFirstCellNum();c<headerRow.getLastCellNum();c++) {Cell cell=headerRow.getCell(c);if(cell==null)continue;String name=formatter.formatCellValue(cell,evaluator).trim();if(name.isEmpty())continue;String field=resolveHeader(type,name);if(field==null)throw new IllegalArgumentException("模板包含未知列："+name);if(!found.add(field))throw new IllegalArgumentException("模板列名重复："+name);columns.put(c,field);}
            for(String required:type.equals("sku")?List.of("internal_sku","product_name"):List.of("shop_identifier","platform_sku","internal_sku"))if(!found.contains(required))throw new IllegalArgumentException("模板缺少必填列："+required);
            List<ParsedRow> rows=new ArrayList<>();
            for(int r=headerRow.getRowNum()+1;r<=sheet.getLastRowNum();r++) {
                Row row=sheet.getRow(r); if(row==null)continue; Map<String,Object> values=new LinkedHashMap<>(); boolean nonempty=false;
                for(Map.Entry<Integer,String> col:columns.entrySet()) {Cell cell=row.getCell(col.getKey());String value=cell==null?"":formatter.formatCellValue(cell,evaluator).trim();if(!value.isEmpty())nonempty=true;values.put(col.getValue(),value.isEmpty()?null:value);}
                if(nonempty){rows.add(new ParsedRow(r+1,values));if(rows.size()>MAX_ROWS)throw new IllegalArgumentException("单个文件最多支持 20000 行，当前文件超过上限");}
            }
            if(rows.isEmpty())throw new IllegalArgumentException("没有可导入的数据行");
            return new Parsed(rows);
        } catch(org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e){throw new IllegalArgumentException("文件内容不是有效的 .xlsx 工作簿");}
        catch(IOException e){throw new IllegalArgumentException("Excel 文件损坏或无法读取");}
    }

    private RowCheck validateRow(String type,int row,Map<String,Object> values,boolean force) {
        List<String> errors=new ArrayList<>();
        if("sku".equals(type)) validateSku(values,force,errors);
        else validateMapping(values,force,errors);
        return new RowCheck(row,errors,values);
    }

    private void validateSku(Map<String,Object> v,boolean force,List<String> errors) {
        required(v,"internal_sku","内部 SKU",errors);required(v,"product_name","商品名称",errors);
        String code=str(v.get("internal_sku"));
        if(code!=null){v.put("internal_sku",code);if(code.length()>100)errors.add("内部 SKU 最长 100 个字符");}
        Map<String,Integer> textLimits=Map.ofEntries(Map.entry("product_name",255),Map.entry("spu",100),Map.entry("name_zh",255),Map.entry("name_en",255),Map.entry("specification",255),Map.entry("color",100),Map.entry("size",100),Map.entry("barcode",100),Map.entry("category",160),Map.entry("image_url",1000),Map.entry("note",1000));
        for(Map.Entry<String,Integer> limit:textLimits.entrySet())if(v.get(limit.getKey())!=null&&str(v.get(limit.getKey())).length()>limit.getValue())errors.add(HEADER_LABELS.get("sku").get(limit.getKey())+"最长 "+limit.getValue()+" 个字符");
        if(code!=null&&!force&&count("SELECT COUNT(*) FROM skus WHERE internal_sku=?",code)>0)errors.add("内部 SKU 已存在；如需覆盖请使用强制重导入");
        if(v.get("status")!=null){String status=str(v.get("status")).toUpperCase(Locale.ROOT);v.put("status",status);if(!Set.of("ACTIVE","INACTIVE").contains(status))errors.add("status 只能为 ACTIVE 或 INACTIVE");}
        if(v.get("supplier_code")!=null&&count("SELECT COUNT(*) FROM suppliers WHERE supplier_code=? AND status='ACTIVE' AND is_deleted=FALSE",str(v.get("supplier_code")).toUpperCase(Locale.ROOT))==0)errors.add("供应商编码不存在或已停用");
        for(String field:DECIMAL_FIELDS)if(v.get(field)!=null)try{BigDecimal n=new BigDecimal(str(v.get(field)));if(n.signum()<0||n.precision()>18||n.scale()>4)errors.add(field+" 必须为非负 decimal(18,4)");else v.put(field,n.toPlainString());}catch(NumberFormatException e){errors.add(field+" 必须为数字");}
        validatePlan(v,"default_product_packaging_plan","PRODUCT_PACKAGING",errors);validatePlan(v,"default_shipping_material_plan","SHIPPING_MATERIAL",errors);
    }

    private void validatePlan(Map<String,Object> v,String field,String category,List<String> errors) {
        if(v.get(field)==null)return;
        try {var root=mapper.readTree(str(v.get(field)));if(!root.isArray()){errors.add(field+" 必须是 JSON 数组");return;}for(var item:root){if(!item.isObject()||!item.hasNonNull("materialCode")){errors.add(field+" 每项必须有 materialCode");continue;}Integer found=jdbc.queryForObject("SELECT COUNT(*) FROM package_materials WHERE material_code=? AND category=? AND status='ACTIVE' AND is_deleted=FALSE",Integer.class,item.get("materialCode").asText(),category);if(found==null||found==0)errors.add(field+" 包含未启用或分类不符的包材："+item.get("materialCode").asText());if(item.has("quantity")){try{if(!item.get("quantity").isNumber()||item.get("quantity").decimalValue().signum()<=0)errors.add(field+" 的 quantity 必须大于 0");}catch(Exception e){errors.add(field+" 的 quantity 必须大于 0");}}}}
        catch(Exception e){errors.add(field+" 必须是有效 JSON 数组");}
    }

    private void validateMapping(Map<String,Object> v,boolean force,List<String> errors) {
        for(String field:List.of("shop_identifier","platform_sku","internal_sku"))required(v,field,field,errors);
        if(v.get("shop_identifier")!=null&&str(v.get("shop_identifier")).length()>160)errors.add("店铺标识最长 160 个字符");
        if(v.get("platform_sku")!=null&&str(v.get("platform_sku")).length()>160)errors.add("平台 SKU 最长 160 个字符");
        if(v.get("platform_product_id")!=null&&str(v.get("platform_product_id")).length()>160)errors.add("平台商品 ID 最长 160 个字符");
        if(v.get("platform_product_name")!=null&&str(v.get("platform_product_name")).length()>255)errors.add("平台商品名称最长 255 个字符");
        if(v.get("shop_identifier")!=null&&count("SELECT COUNT(*) FROM shops WHERE shop_identifier=? AND status='ACTIVE' AND is_deleted=FALSE",str(v.get("shop_identifier")))==0)errors.add("店铺标识不存在或已停用");
        if(v.get("internal_sku")!=null&&count("SELECT COUNT(*) FROM skus WHERE internal_sku=? AND status='ACTIVE' AND is_deleted=FALSE",str(v.get("internal_sku")))==0)errors.add("内部 SKU 不存在或已停用");
        String mode=v.get("default_order_mode")==null?"JIT":str(v.get("default_order_mode")).toUpperCase(Locale.ROOT);v.put("default_order_mode",mode);
        if(!Set.of("JIT","WAREHOUSE").contains(mode))errors.add("default_order_mode 只能为 JIT 或 WAREHOUSE");
        String status=v.get("status")==null?"ACTIVE":str(v.get("status")).toUpperCase(Locale.ROOT);v.put("status",status);
        if(!Set.of("ACTIVE","INACTIVE").contains(status))errors.add("status 只能为 ACTIVE 或 INACTIVE");
        if(v.get("shop_identifier")!=null&&v.get("platform_sku")!=null&&!force&&count("SELECT COUNT(*) FROM shop_sku_mappings m JOIN shops sh ON sh.id=m.shop_id WHERE sh.shop_identifier=? AND m.platform_sku=? AND m.status='ACTIVE' AND m.is_deleted=FALSE",str(v.get("shop_identifier")),str(v.get("platform_sku")))>0)errors.add("该店铺的平台 SKU 已有有效映射；如需更新请使用强制重导入");
    }

    private Long writeSku(Map<String,Object> v,boolean force) {
        String code=str(v.get("internal_sku"));Long id=jdbc.query("SELECT id FROM skus WHERE internal_sku=?",rs->rs.next()?rs.getLong(1):null,code);
        if(id!=null&&!force)throw new IllegalArgumentException("内部 SKU 已存在："+code);
        Map<String,Object> values=new LinkedHashMap<>();for(String field:SKU_FIELDS)if(v.containsKey(field))values.put(field,v.get(field));
        values.putIfAbsent("purchase_price","0");values.putIfAbsent("supply_price","0");values.putIfAbsent("status","ACTIVE");
        Object supplierId=null;if(v.get("supplier_code")!=null)supplierId=jdbc.queryForObject("SELECT id FROM suppliers WHERE supplier_code=? AND status='ACTIVE' AND is_deleted=FALSE",Long.class,str(v.get("supplier_code")).toUpperCase(Locale.ROOT));
        values.put("supplier_id",supplierId);
        if(id==null){List<String> cols=new ArrayList<>(values.keySet());String placeholders=String.join(",",cols.stream().map(c->isJsonField(c)?"CAST(? AS JSON)":"?").toList());String sql="INSERT INTO skus(internal_sku,"+String.join(",",cols)+") VALUES (?,"+placeholders+")";jdbc.update(sql,prepend(code,cols.stream().map(values::get).toArray()));id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
        else {List<String> cols=new ArrayList<>(values.keySet());String sql="UPDATE skus SET "+String.join(",",cols.stream().map(c->c+(isJsonField(c)?"=CAST(? AS JSON)":"=?")).toList())+",is_deleted=FALSE WHERE id=?";List<Object> args=new ArrayList<>();cols.forEach(c->args.add(values.get(c)));args.add(id);jdbc.update(sql,args.toArray());}
        return id;
    }

    private Long writeMapping(Map<String,Object> v,boolean force) {
        Long shop=jdbc.queryForObject("SELECT id FROM shops WHERE shop_identifier=? AND status='ACTIVE' AND is_deleted=FALSE",Long.class,str(v.get("shop_identifier")));
        Long sku=jdbc.queryForObject("SELECT id FROM skus WHERE internal_sku=? AND status='ACTIVE' AND is_deleted=FALSE",Long.class,str(v.get("internal_sku")));
        Long id=jdbc.query("SELECT id FROM shop_sku_mappings WHERE shop_id=? AND platform_sku=? AND status='ACTIVE' AND is_deleted=FALSE",rs->rs.next()?rs.getLong(1):null,shop,str(v.get("platform_sku")));
        String mode=str(v.get("default_order_mode"));String status=str(v.get("status"));
        if(id!=null&&!force)throw new IllegalArgumentException("店铺平台 SKU 已有有效映射："+v.get("platform_sku"));
        if(id==null){jdbc.update("INSERT INTO shop_sku_mappings(shop_id,platform_sku,platform_product_id,platform_product_name,sku_id,default_order_mode,status) VALUES (?,?,?,?,?,?,?)",shop,v.get("platform_sku"),v.get("platform_product_id"),v.get("platform_product_name"),sku,mode,status);id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
        else jdbc.update("UPDATE shop_sku_mappings SET platform_product_id=?,platform_product_name=?,sku_id=?,default_order_mode=?,status=? WHERE id=?",v.get("platform_product_id"),v.get("platform_product_name"),sku,mode,status,id);
        return id;
    }

    private Map<String,Object> parseRaw(Object raw) {try{return mapper.readValue(String.valueOf(raw),Map.class);}catch(JsonProcessingException e){throw new IllegalArgumentException("原始行数据损坏");}}
    private Path saveFile(String batchNo,String filename,MultipartFile file) {
        try{Path dir=Path.of(storageDir).toAbsolutePath().normalize().resolve(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());Files.createDirectories(dir);Path target=dir.resolve(batchNo+"_"+filename).normalize();if(!target.startsWith(dir))throw new IllegalArgumentException("文件名无效");file.transferTo(target);return target;}
        catch(IOException e){throw new IllegalArgumentException("原始文件保存失败："+e.getMessage());}
    }
    private String sha256(MultipartFile file){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(file.getBytes());return HexFormat.of().formatHex(hash);}catch(Exception e){throw new IllegalStateException("文件摘要计算失败",e);}}
    private long count(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n;}
    private static void required(Map<String,Object> map,String field,String label,List<String> errors){if(map.get(field)==null||str(map.get(field)).isBlank())errors.add(label+"不能为空");}
    private static String str(Object value){return value==null?null:String.valueOf(value).trim();}
    private static Object[] prepend(Object first,Object[] rest){Object[] out=new Object[rest.length+1];out[0]=first;System.arraycopy(rest,0,out,1,rest.length);return out;}
    private static boolean isJsonField(String field){return "default_product_packaging_plan".equals(field)||"default_shipping_material_plan".equals(field);}
    private static String resolveHeader(String type,String label){if(HEADERS.get(type).contains(label))return label;for(Map.Entry<String,String> entry:HEADER_LABELS.get(type).entrySet())if(entry.getValue().equals(label))return entry.getKey();return null;}
    private static String[][] templateInstructions(String type){
        if("sku".equals(type))return new String[][]{
            {"字段（模板表头）","是否必填","填写规则","示例"},
            {"内部 SKU","是","最长 100 个字符；全局唯一。已存在的编码需勾选强制重导入才可覆盖。","ABC-001"},
            {"商品名称","是","最长 255 个字符。","蓝色收纳盒"},
            {"SPU","否","最长 100 个字符。","SPU-001"},
            {"中文名称 / 英文名称","否","各自最长 255 个字符。","收纳盒 / Storage box"},
            {"规格","否","最长 255 个字符。","大号"},
            {"颜色 / 尺码 / 条码","否","颜色、尺码、条码各自最长 100 个字符。","蓝 / XL / 690000000001"},
            {"品类","否","最长 160 个字符。","家居收纳"},
            {"供应商编码","否","必须匹配系统中已启用的供应商编码。","SUP20261013000001"},
            {"采购价（元）/ 供货价（元）","否","人民币非负数，decimal(18,4)，最多 4 位小数；留空时按 0 导入。","12.5000"},
            {"重量 / 长度 / 宽度 / 高度","否","非负数，decimal(18,4)，最多 4 位小数。","0.2500"},
            {"默认产品包装方案（JSON）","否","JSON 数组；每项需有 materialCode，可选 quantity 且须大于 0；包材须为启用的产品包装分类。","[{\"materialCode\":\"MAT-001\",\"quantity\":1}]"},
            {"默认发货打包材料方案（JSON）","否","JSON 数组；每项需有 materialCode，可选 quantity 且须大于 0；包材须为启用的发货打包材料分类。","[{\"materialCode\":\"MAT-002\",\"quantity\":1}]"},
            {"商品图片链接","否","最长 1000 个字符。","https://example.com/item.jpg"},
            {"状态","否","仅允许 ACTIVE（启用）或 INACTIVE（停用）；留空默认 ACTIVE。","ACTIVE"},
            {"备注","否","最长 1000 个字符。","新款"}
        };
        return new String[][]{
            {"字段（模板表头）","是否必填","填写规则","示例"},
            {"店铺标识","是","必须与系统中已启用店铺的标识完全一致，最长 160 个字符；建议按文本填写以保留前导零。","MY-SHOP-01"},
            {"平台 SKU","是","最长 160 个字符。同一店铺下只能有一个有效映射。","PLATFORM-001"},
            {"平台商品 ID","否","最长 160 个字符。","ITEM-001"},
            {"平台商品名称","否","最长 255 个字符。","示例商品"},
            {"内部 SKU","是","必须匹配系统中已启用的内部 SKU。","ABC-001"},
            {"默认订单模式","否","仅允许 JIT 或 WAREHOUSE；留空默认 JIT。","JIT"},
            {"状态","否","仅允许 ACTIVE（启用）或 INACTIVE（停用）；留空默认 ACTIVE。","ACTIVE"}
        };
    }
    private static String csv(Object raw){String value=Objects.toString(raw,"");if(value.startsWith("=")||value.startsWith("+")||value.startsWith("-")||value.startsWith("@"))value="'"+value;return "\""+value.replace("\"","\"\"").replace("\r"," ").replace("\n"," ")+"\"";}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String rootMessage(Throwable e){Throwable root=e;while(root.getCause()!=null)root=root.getCause();return Objects.requireNonNullElse(root.getMessage(),e.getMessage());}
    private static void requireType(String type){if(!IMPORT_TYPES.contains(type))throw new IllegalArgumentException("当前只支持 sku、shop_sku_mapping 两种导入类型");}
    private void audit(AuthUser actor,HttpServletRequest req,String action,long id,Map<String,Object> detail){try{jdbc.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,'import_batch',?,CAST(? AS JSON),?,TRUE)",actor.id(),actor.username(),action,String.valueOf(id),mapper.writeValueAsString(detail),req.getRemoteAddr());}catch(JsonProcessingException e){throw new IllegalStateException(e);}}

    private record Parsed(List<ParsedRow> rows){}
    private record ParsedRow(int number,Map<String,Object> values){}
    private static class RowCheck { final int number; final List<String> errors; final Map<String,Object> normalized; RowCheck(int number,List<String> errors,Map<String,Object> normalized){this.number=number;this.errors=errors;this.normalized=normalized;} }
}
