package com.lue.erp.warehouse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/mobile")
public class MobileWarehouseController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final WarehouseController warehouse;
    @Value("${lue.warehouse.photo-dir:./data/warehouse-attachments}") private String photoDir;

    public MobileWarehouseController(JdbcTemplate db,AuthService auth,ObjectMapper json,WarehouseController warehouse){this.db=db;this.auth=auth;this.json=json;this.warehouse=warehouse;}

    @GetMapping("/dashboard")
    public ApiResponse<Map<String,Object>> dashboard(@AuthenticationPrincipal AuthUser user){mobile(user);
        return ApiResponse.ok(Map.of(
            "pendingConfirm",scalar("SELECT COUNT(*) FROM purchase_orders WHERE status='DRAFT'"),
            "pendingReceipt",scalar("SELECT COUNT(*) FROM purchase_orders WHERE status IN ('CONFIRMED','PARTIALLY_RECEIVED')"),
            "inboundToday",decimal("SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_flows WHERE flow_type IN ('PURCHASE_INBOUND','MANUAL_INBOUND') AND DATE(created_at)=CURRENT_DATE"),
            "outboundToday",decimal("SELECT COALESCE(SUM(ABS(quantity_delta)),0) FROM inventory_flows WHERE flow_type IN ('JIT_OUTBOUND','STOCKING_OUTBOUND','MANUAL_OUTBOUND') AND DATE(created_at)=CURRENT_DATE"),
            "openExceptions",scalar("SELECT COUNT(*) FROM exceptions WHERE status='OPEN'"),
            "negativeStocks",scalar("SELECT COUNT(*) FROM inventory_stocks WHERE available_qty<0")
        ));
    }

    @GetMapping("/purchases")
    public ApiResponse<Map<String,Object>> purchases(@RequestParam(defaultValue="confirm") String stage,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int pageSize,@AuthenticationPrincipal AuthUser user){mobile(user);
        int p=Math.max(page,1),s=Math.min(100,Math.max(1,pageSize));StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object>args=new ArrayList<>();
        if("confirm".equalsIgnoreCase(stage))where.append(" AND po.status='DRAFT'");else if("receipt".equalsIgnoreCase(stage))where.append(" AND po.status IN ('CONFIRMED','PARTIALLY_RECEIVED')");
        if(q!=null&&!q.isBlank()){where.append(" AND (po.po_no LIKE ? OR po.tracking_no LIKE ? OR po.order_no LIKE ? OR sp.supplier_name LIKE ? OR EXISTS (SELECT 1 FROM purchase_order_items i JOIN skus sk ON sk.id=i.sku_id WHERE i.purchase_order_id=po.id AND (sk.internal_sku LIKE ? OR sk.barcode LIKE ?)) )");for(int i=0;i<6;i++)args.add("%"+q.trim()+"%");}
        String from=" FROM purchase_orders po JOIN suppliers sp ON sp.id=po.supplier_id";Long total=db.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());args.add(s);args.add((p-1)*s);
        List<Map<String,Object>>items=db.queryForList("SELECT po.id,po.po_no poNo,po.tracking_no trackingNo,po.order_no orderNo,sp.supplier_name supplierName,po.status,po.is_abnormal isAbnormal,po.abnormal_reason abnormalReason,po.expected_arrival_date expectedArrivalDate,(SELECT COUNT(*) FROM purchase_order_items i WHERE i.purchase_order_id=po.id) itemCount FROM purchase_orders po JOIN suppliers sp ON sp.id=po.supplier_id"+where+" ORDER BY po.updated_at DESC LIMIT ? OFFSET ?",args.toArray());
        return ApiResponse.ok(Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s));
    }

    @GetMapping("/purchases/{id}")
    public ApiResponse<Map<String,Object>> purchase(@PathVariable long id,@AuthenticationPrincipal AuthUser user){mobile(user);return warehouse.purchase(id,user);}

    @GetMapping("/skus")
    public ApiResponse<Map<String,Object>> skus(@RequestParam(required=false) String q,@RequestParam(defaultValue="50") int limit,@AuthenticationPrincipal AuthUser user){mobile(user);
        int size=Math.min(100,Math.max(1,limit));String where=" WHERE s.status='ACTIVE' AND s.is_deleted=FALSE";List<Object>args=new ArrayList<>();if(q!=null&&!q.isBlank()){where+=" AND (s.internal_sku LIKE ? OR s.barcode LIKE ? OR s.product_name LIKE ? OR s.name_zh LIKE ?)";for(int i=0;i<4;i++)args.add("%"+q.trim()+"%");}args.add(size);
        return ApiResponse.ok(Map.of("items",db.queryForList("SELECT s.id,s.internal_sku internalSku,s.product_name productName,s.barcode,s.image_url imageUrl,COALESCE(st.available_qty,0) availableQty FROM skus s LEFT JOIN inventory_stocks st ON st.sku_id=s.id"+where+" ORDER BY s.internal_sku LIMIT ?",args.toArray())));
    }

    @GetMapping("/packages")
    public ApiResponse<Map<String,Object>> packages(@RequestParam(required=false) String q,@AuthenticationPrincipal AuthUser user){mobile(user);return warehouse.packages(1,100,q,user);}

    @PostMapping("/purchases/{id}/confirm") @Transactional
    public ApiResponse<Map<String,Object>> confirm(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){mobile(user);ApiResponse<Map<String,Object>> result=warehouse.confirmPurchase(id,body,user);return audited("purchase.confirm", "purchase_order", id, body, user, request, result.data());}

    @PostMapping("/purchases/{id}/abnormal") @Transactional
    public ApiResponse<Map<String,Object>> markAbnormal(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){mobile(user);ApiResponse<Map<String,Object>> result=warehouse.markPurchaseAbnormal(id,body,user);return audited("purchase.abnormal", "purchase_order", id, body, user, request, result.data());}

    @PostMapping("/purchases/{id}/abnormal/resolve") @Transactional
    public ApiResponse<Map<String,Object>> resolveAbnormal(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){mobile(user);ApiResponse<Map<String,Object>> result=warehouse.resolvePurchaseAbnormal(id,body,user);return audited("purchase.abnormal.resolve", "purchase_order", id, body, user, request, result.data());}

    @PostMapping("/purchases/{id}/receipts") @Transactional
    public ApiResponse<Map<String,Object>> receive(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){mobile(user);ApiResponse<Map<String,Object>> result=warehouse.receivePurchase(id,body,user);return audited("purchase.receipt", "purchase_order", id, body, user, request, result.data());}

    @PostMapping("/inventory/operations") @Transactional
    public ApiResponse<Map<String,Object>> inventory(@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){mobile(user);ApiResponse<Map<String,Object>> result=warehouse.inventoryOperation(body,user);return audited("inventory."+Objects.toString(body.get("operation"),"operation").toLowerCase(Locale.ROOT),"sku",Objects.toString(body.get("skuId"),""),body,user,request,result.data());}

    @PostMapping("/packages/operations") @Transactional
    public ApiResponse<Map<String,Object>> packages(@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthUser user,HttpServletRequest request){mobile(user);ApiResponse<Map<String,Object>> result=warehouse.packageOperation(body,user);return audited("package."+Objects.toString(body.get("operation"),"operation").toLowerCase(Locale.ROOT),"package_material",Objects.toString(body.get("materialId"),""),body,user,request,result.data());}

    @PostMapping(value="/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> upload(@RequestPart("file") MultipartFile file,@AuthenticationPrincipal AuthUser user){mobile(user);
        if(file==null||file.isEmpty())throw new IllegalArgumentException("请选择现场照片");if(file.getSize()>10L*1024*1024)throw new IllegalArgumentException("照片不能超过 10 MB");
        String content=Objects.toString(file.getContentType(),"").toLowerCase(Locale.ROOT);String ext=switch(content){case "image/jpeg","image/jpg"->".jpg";case "image/png"->".png";case "image/webp"->".webp";case "image/heic","image/heif"->".heic";default->throw new IllegalArgumentException("仅支持 JPEG、PNG、WebP 或 HEIC 图片");};
        String stored=UUID.randomUUID()+ext;Path root=Path.of(photoDir).toAbsolutePath().normalize();try{Files.createDirectories(root);Path dest=root.resolve(stored).normalize();if(!dest.startsWith(root))throw new IllegalArgumentException("照片文件名无效");file.transferTo(dest);String original=Path.of(Objects.requireNonNullElse(file.getOriginalFilename(),"warehouse-photo" )).getFileName().toString();db.update("INSERT INTO warehouse_operation_attachments(uploaded_by,original_name,stored_file_name,content_type,file_size) VALUES (?,?,?,?,?)",user.id(),original,stored,content,file.getSize());Long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);return ApiResponse.ok(Map.of("id",id,"name",original,"contentType",content,"size",file.getSize()));}catch(IOException e){throw new IllegalStateException("照片保存失败",e);}
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<ByteArrayResource> attachment(@PathVariable long id,@AuthenticationPrincipal AuthUser user){mobile(user);Map<String,Object>row=db.queryForMap("SELECT stored_file_name storedFileName,content_type contentType,original_name originalName FROM warehouse_operation_attachments WHERE id=?",id);Path root=Path.of(photoDir).toAbsolutePath().normalize();Path file=root.resolve(String.valueOf(row.get("storedFileName"))).normalize();if(!file.startsWith(root)||!Files.isRegularFile(file))throw new IllegalArgumentException("照片附件不存在");try{MediaType type=MediaType.parseMediaType(String.valueOf(row.get("contentType")));return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(String.valueOf(row.get("originalName"))).build().toString()).body(new ByteArrayResource(Files.readAllBytes(file)));}catch(IOException e){throw new IllegalStateException("照片读取失败",e);}}

    private ApiResponse<Map<String,Object>> audited(String action,String resource,Object resourceId,Map<String,Object>input,AuthUser user,HttpServletRequest req,Map<String,Object>result){try{db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,TRUE)",user.id(),user.username(),"mobile.warehouse."+action,resource,String.valueOf(resourceId),json.writeValueAsString(Map.of("request",safeInput(input),"result",result)),req.getRemoteAddr());Long logId=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);Object ids=input.get("attachmentIds");if(ids instanceof Collection<?> collection&&!collection.isEmpty()){String marks=String.join(",",Collections.nCopies(collection.size(),"?"));List<Object>args=new ArrayList<>(collection);args.add(user.id());db.update("UPDATE warehouse_operation_attachments SET operation_log_id=? WHERE id IN ("+marks+") AND uploaded_by=? AND operation_log_id IS NULL",prepend(logId,args).toArray());}return ApiResponse.ok(result);}catch(JsonProcessingException e){throw new IllegalStateException("操作记录写入失败",e);}}
    private Object safeInput(Map<String,Object>body){Map<String,Object>copy=new LinkedHashMap<>(body);copy.remove("photo");return copy;}
    private static List<Object> prepend(Object first,List<Object>rest){List<Object>all=new ArrayList<>();all.add(first);all.addAll(rest);return all;}
    private void mobile(AuthUser user){auth.requirePermission(user.id(),"menu.mobile");}
    private Long scalar(String sql){return db.queryForObject(sql,Long.class);}
    private Object decimal(String sql){return db.queryForObject(sql,java.math.BigDecimal.class);}
}
