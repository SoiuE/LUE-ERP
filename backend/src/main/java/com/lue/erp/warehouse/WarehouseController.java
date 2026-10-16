package com.lue.erp.warehouse;

import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final AuthService auth;

    public WarehouseController(JdbcTemplate db, AuthService auth) { this.db = db; this.auth = auth; }

    @GetMapping("/packages")
    public ApiResponse<Map<String,Object>> packages(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String q,@AuthenticationPrincipal AuthUser user){packageView(user);return ApiResponse.ok(stockList(page,pageSize,q));}
    @GetMapping("/packages/flows")
    public ApiResponse<Map<String,Object>> packageFlows(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) Long materialId,@AuthenticationPrincipal AuthUser user){packageView(user);return ApiResponse.ok(flowList(page,pageSize));}
    @PostMapping("/packages/operations") @Transactional
    public ApiResponse<Map<String,Object>> packageOperation(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user){packageManage(user);String op=operation(body);long material=number(body,"materialId");String category=db.query("SELECT category FROM package_materials WHERE id=? AND is_deleted=FALSE",rs->rs.next()?rs.getString(1):null,material);if(category==null)throw new IllegalArgumentException("包材不存在");BigDecimal qty=operationQuantity(body,op);String reason=text(body.get("reason"));if((op.equals("COUNT")||op.equals("ADJUST"))&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("盘点和调整必须填写原因");db.update("INSERT IGNORE INTO package_stocks(material_id) VALUES (?)",material);BigDecimal before=db.queryForObject("SELECT available_qty FROM package_stocks WHERE material_id=? FOR UPDATE",BigDecimal.class,material);BigDecimal delta=switch(op){case "INBOUND"->qty;case "OUTBOUND"->qty.negate();case "COUNT"->qty.subtract(before);case "ADJUST"->qty;default->throw new IllegalArgumentException("不支持的包材操作");};String type=switch(op){case "INBOUND"->"MANUAL_INBOUND";case "OUTBOUND"->"MANUAL_OUTBOUND";case "COUNT"->"STOCK_COUNT";default->"ADJUSTMENT";};db.update("UPDATE package_stocks SET available_qty=available_qty+? WHERE material_id=?",delta,material);BigDecimal after=db.queryForObject("SELECT available_qty FROM package_stocks WHERE material_id=?",BigDecimal.class,material);db.update("INSERT INTO package_flows(material_id,material_category,flow_type,quantity_delta,available_before,available_after,reference_type,reason,operator_id) VALUES (?,?,?,?,?,?, 'MANUAL',?,?)",material,category,type,delta,before,after,reason,user.id());boolean shortStock=after.signum()<0;if(shortStock)db.update("INSERT INTO exceptions(exception_no,exception_type,status,title,resolution_note,created_at) VALUES (?,'PACKAGE_SHORTAGE','OPEN',?,?,CURRENT_TIMESTAMP)",nextCode("EXCEPTION","EX"),"包材库存不足："+material,"操作后库存："+after+"；原因："+Objects.toString(reason,""));return ApiResponse.ok(Map.of("availableQty",after,"warning",shortStock?"包材库存不足，已记入异常中心":""));}

    private Map<String,Object> stockList(int page,int size,String q){int p=Math.max(1,page),s=Math.min(100,Math.max(1,size));String where=" WHERE m.is_deleted=FALSE";List<Object>a=new ArrayList<>();if(q!=null&&!q.isBlank()){where+=" AND (m.material_code LIKE ? OR m.material_name LIKE ?)";a.add("%"+q.trim()+"%");a.add("%"+q.trim()+"%");}Long total=db.queryForObject("SELECT COUNT(*) FROM package_materials m LEFT JOIN package_stocks st ON st.material_id=m.id"+where,Long.class,a.toArray());a.add(s);a.add((p-1)*s);List<Map<String,Object>>items=db.queryForList("SELECT m.id,m.material_code code,m.material_name name,m.category,m.unit,COALESCE(st.available_qty,0) availableQty,m.safety_stock safetyStock FROM package_materials m LEFT JOIN package_stocks st ON st.material_id=m.id"+where+" ORDER BY code LIMIT ? OFFSET ?",a.toArray());return Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s);}
    private Map<String,Object> flowList(int page,int size){int p=Math.max(1,page),s=Math.min(100,Math.max(1,size));String from=" FROM package_flows f JOIN package_materials m ON m.id=f.material_id";Long total=db.queryForObject("SELECT COUNT(*)"+from,Long.class);List<Map<String,Object>>items=db.queryForList("SELECT f.id,m.material_code code,m.material_name name,f.material_category category,f.flow_type flowType,f.quantity_delta quantityDelta,f.available_before availableBefore,f.available_after availableAfter,f.reason,f.reference_type referenceType,f.reference_id referenceId,f.created_at createdAt"+from+" ORDER BY f.id DESC LIMIT ? OFFSET ?",s,(p-1)*s);return Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s);}
    private String nextCode(String key,String prefix){LocalDate d=LocalDate.now(ZONE);db.update("INSERT INTO master_code_sequences(sequence_key,sequence_date,sequence_value) VALUES (?,?,1) ON DUPLICATE KEY UPDATE sequence_value=IF(sequence_date=VALUES(sequence_date),sequence_value+1,1),sequence_date=VALUES(sequence_date)",key,d);Long n=db.queryForObject("SELECT sequence_value FROM master_code_sequences WHERE sequence_key=? FOR UPDATE",Long.class,key);return prefix+d.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format("%06d",n);}
    private void packageView(AuthUser u){requireAny(u,"menu.package-stocks");}
    private void packageManage(AuthUser u){packageView(u);requireAny(u,"master.data.manage");}
    private void requireAny(AuthUser u,String permission){if(!auth.hasPermission(u.id(),permission)&&!auth.hasPermission(u.id(),"menu.mobile"))throw new org.springframework.security.access.AccessDeniedException("没有执行此操作的权限");}
    private static String operation(Map<String,Object>b){String op=Objects.toString(b.get("operation"),"").toUpperCase(Locale.ROOT);if(!Set.of("INBOUND","OUTBOUND","COUNT","ADJUST").contains(op))throw new IllegalArgumentException("请选择正确的业务操作");return op;}
    private static BigDecimal operationQuantity(Map<String,Object>b,String op){try{BigDecimal value=new BigDecimal(Objects.toString(b.get("quantity")));if((op.equals("INBOUND")||op.equals("OUTBOUND"))&&value.signum()<=0)throw new Exception();if(op.equals("COUNT")&&value.signum()<0)throw new Exception();if(op.equals("ADJUST")&&value.signum()==0)throw new Exception();return value;}catch(Exception e){throw new IllegalArgumentException("数量格式不正确或不符合当前操作要求");}}
    private static long number(Map<String,Object>b,String key){try{return Long.parseLong(Objects.toString(b.get(key)));}catch(Exception e){throw new IllegalArgumentException(key+" 必填");}}
    private static String text(Object x){return x==null?null:x.toString().trim();}
}
