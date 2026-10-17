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
            "pendingConfirm",0,
            "pendingReceipt",0,
            "inboundToday",decimal("SELECT COALESCE(SUM(available_delta),0) FROM warehouse_goods_stock_flows WHERE flow_type='RECEIPT' AND DATE(created_at)=CURRENT_DATE"),
            "outboundToday",decimal("SELECT COALESCE(SUM(ABS(available_delta)),0) FROM warehouse_goods_stock_flows WHERE flow_type='OUTBOUND' AND DATE(created_at)=CURRENT_DATE"),
            "openExceptions",scalar("SELECT COUNT(*) FROM exceptions WHERE status='OPEN'"),
            "negativeStocks",scalar("SELECT COUNT(*) FROM warehouse_goods_location_balances WHERE available_qty<0")
        ));
    }

    @GetMapping("/packages")
    public ApiResponse<Map<String,Object>> packages(@RequestParam(required=false) String q,@AuthenticationPrincipal AuthUser user){mobile(user);return warehouse.packages(1,100,q,user);}

    @GetMapping("/skus")
    public ApiResponse<Map<String,Object>> skus(@RequestParam(required=false) String q,@AuthenticationPrincipal AuthUser user) {
        mobile(user);
        String term = Objects.toString(q, "").trim();
        if (term.length() > 100) throw new IllegalArgumentException("搜索词最多 100 个字符");
        if (term.isBlank()) return ApiResponse.ok(Map.of("items", List.of()));
        String like = "%" + term + "%";
        List<Map<String,Object>> rows = db.queryForList("SELECT s.id,s.internal_sku internalSku,s.product_name productName,s.specification,s.barcode,s.status FROM skus s WHERE s.is_deleted=FALSE AND (s.internal_sku LIKE ? OR s.product_name LIKE ? OR s.barcode LIKE ?) ORDER BY s.internal_sku LIMIT 20", like, like, like);
        return ApiResponse.ok(Map.of("items", rows));
    }

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
