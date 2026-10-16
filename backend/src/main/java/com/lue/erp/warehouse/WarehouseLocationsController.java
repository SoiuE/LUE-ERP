package com.lue.erp.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/warehouse-locations")
public class WarehouseLocationsController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;

    public WarehouseLocationsController(JdbcTemplate db, AuthService auth, ObjectMapper json) {
        this.db = db; this.auth = auth; this.json = json;
    }

    @GetMapping("/warehouses")
    public ApiResponse<Map<String,Object>> warehouses(@AuthenticationPrincipal AuthUser user) {
        lookupView(user);
        return ApiResponse.ok(Map.of("warehouses",db.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName,status,is_default isDefault FROM warehouses ORDER BY is_default DESC,warehouse_code")));
    }

    @GetMapping("/goods")
    public ApiResponse<Map<String,Object>> goods(@AuthenticationPrincipal AuthUser user) {
        lookupView(user);
        return ApiResponse.ok(Map.of("goods",db.queryForList("SELECT id,goods_code goodsCode,goods_name goodsName,unit,status FROM warehouse_goods WHERE is_deleted=FALSE ORDER BY goods_code LIMIT 1000")));
    }

    @GetMapping("/nodes")
    public ApiResponse<Map<String,Object>> nodes(@RequestParam long warehouseId,@AuthenticationPrincipal AuthUser user) {
        lookupView(user); warehouse(warehouseId,false);
        return ApiResponse.ok(Map.of("nodes",db.queryForList("SELECT n.id,n.parent_id parentId,n.node_kind nodeKind,n.node_code nodeCode,n.node_name nodeName FROM warehouse_location_nodes n WHERE n.warehouse_id=? ORDER BY FIELD(n.node_kind,'AREA','SHELF','LEVEL'),n.node_code",warehouseId)));
    }

    @PostMapping("/nodes") @Transactional
    public ApiResponse<Map<String,Object>> createNode(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user); long warehouseId=number(body.get("warehouseId"),"仓库"); warehouse(warehouseId,true);
        String kind=choice(body.get("nodeKind"),"层级","AREA","SHELF","LEVEL");
        Long parent=optionalNumber(body.get("parentId"));
        if("AREA".equals(kind) && parent!=null)throw new IllegalArgumentException("区域不能设置父级");
        if(!"AREA".equals(kind)){
            if(parent==null)throw new IllegalArgumentException("请先选择父级区域或货架");
            String expected="SHELF".equals(kind)?"AREA":"SHELF";
            String actual=db.query("SELECT node_kind FROM warehouse_location_nodes WHERE warehouse_id=? AND id=?",rs->rs.next()?rs.getString(1):null,warehouseId,parent);
            if(!expected.equals(actual))throw new IllegalArgumentException("层级关系错误："+kind+" 的父级必须是 "+expected);
        }
        String code=code(body.get("nodeCode"),40,"层级编码"), name=required(body.get("nodeName"),120,"层级名称");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (?,?,?,?,?)",warehouseId,parent,kind,code,name);
        long id=lastId(); audit(user,req,"location.node.create","warehouse_location_node",id,body);
        return ApiResponse.ok(Map.of("id",id));
    }

    @PutMapping("/nodes/{id}") @Transactional
    public ApiResponse<Map<String,Object>> updateNode(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user); Map<String,Object> before=db.queryForMap("SELECT id,warehouse_id warehouseId,parent_id parentId,node_kind nodeKind,node_code nodeCode,node_name nodeName FROM warehouse_location_nodes WHERE id=? FOR UPDATE",id);
        String code=code(body.get("nodeCode"),40,"层级编码"), name=required(body.get("nodeName"),120,"层级名称");
        db.update("UPDATE warehouse_location_nodes SET node_code=?,node_name=? WHERE id=?",code,name,id);
        audit(user,req,"location.node.update","warehouse_location_node",id,Map.of("before",before,"after",body));
        return ApiResponse.ok(Map.of("updated",true));
    }

    @GetMapping
    public ApiResponse<Map<String,Object>> locations(@RequestParam long warehouseId,@RequestParam(required=false) Long goodsId,
            @RequestParam(required=false) String q,@RequestParam(required=false) String status,@AuthenticationPrincipal AuthUser user) {
        lookupView(user); warehouse(warehouseId,false);
        StringBuilder sql=new StringBuilder("SELECT l.id,l.warehouse_id warehouseId,l.level_id levelId,l.location_code locationCode,l.slot_code slotCode,l.location_name locationName,l.scan_code scanCode,l.location_type locationType,l.status,l.note, a.node_code areaCode,a.node_name areaName,s.node_code shelfCode,s.node_name shelfName,v.node_code levelCode,v.node_name levelName FROM warehouse_locations l JOIN warehouse_location_nodes v ON v.id=l.level_id JOIN warehouse_location_nodes s ON s.id=v.parent_id JOIN warehouse_location_nodes a ON a.id=s.parent_id WHERE l.warehouse_id=?");
        List<Object> args=new ArrayList<>();args.add(warehouseId);
        if(goodsId!=null){sql.append(" AND EXISTS (SELECT 1 FROM warehouse_goods_location_assignments x WHERE x.location_id=l.id AND x.warehouse_goods_id=? AND x.status='ACTIVE')");args.add(goodsId);}
        if(q!=null&&!q.isBlank()){
            sql.append(" AND CONCAT_WS(' ',l.location_code,l.scan_code,l.location_name,l.slot_code,a.node_code,a.node_name,s.node_code,s.node_name,v.node_code,v.node_name,(SELECT w.warehouse_name FROM warehouses w WHERE w.id=l.warehouse_id),(SELECT w.warehouse_code FROM warehouses w WHERE w.id=l.warehouse_id),CONCAT_WS(' / ',(SELECT w.warehouse_name FROM warehouses w WHERE w.id=l.warehouse_id),COALESCE(NULLIF(a.node_name,''),a.node_code),COALESCE(NULLIF(s.node_name,''),s.node_code),COALESCE(NULLIF(v.node_name,''),v.node_code),CONCAT(l.slot_code,'位'))) LIKE ?");
            args.add("%"+q.trim()+"%");
        }
        if(status!=null&&!status.isBlank()){sql.append(" AND l.status=?");args.add(choice(status,"状态","ACTIVE","INACTIVE"));}
        sql.append(" ORDER BY l.location_code LIMIT 1000");
        return ApiResponse.ok(Map.of("locations",db.queryForList(sql.toString(),args.toArray())));
    }

    @PostMapping @Transactional
    public ApiResponse<Map<String,Object>> createLocation(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user); long warehouseId=number(body.get("warehouseId"),"仓库"),levelId=number(body.get("levelId"),"层");warehouse(warehouseId,true);level(warehouseId,levelId);
        String locationCode=code(body.get("locationCode"),80,"库位编码"),slotCode=code(body.get("slotCode"),40,"位编码");
        String type=choice(body.get("locationType"),"库位类型","STORAGE","PRIMARY_PICK","PENDING_PUTAWAY","TEMPORARY","QUARANTINE");
        db.update("INSERT INTO warehouse_locations(warehouse_id,level_id,location_code,slot_code,location_name,scan_code,location_type,note,created_by) VALUES (?,?,?,?,?,?,?,?,?)",warehouseId,levelId,locationCode,slotCode,optional(body.get("locationName"),120,"库位名称"),optional(body.get("scanCode"),100,"扫码编码"),type,optional(body.get("note"),1000,"备注"),user.id());
        long id=lastId();audit(user,req,"location.create","warehouse_location",id,body);return ApiResponse.ok(Map.of("id",id));
    }

    @PutMapping("/{id}") @Transactional
    public ApiResponse<Map<String,Object>> updateLocation(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user);Map<String,Object> before=lockedLocation(id);long warehouseId=((Number)before.get("warehouseId")).longValue();
        long levelId=number(body.get("levelId"),"层");level(warehouseId,levelId);
        String type=choice(body.get("locationType"),"库位类型","STORAGE","PRIMARY_PICK","PENDING_PUTAWAY","TEMPORARY","QUARANTINE");
        String slotCode=code(body.get("slotCode"),40,"位编码");
        if(levelId!=((Number)before.get("levelId")).longValue() || !slotCode.equals(before.get("slotCode"))){
            Long balances=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances WHERE location_id=? AND (available_qty<>0 OR locked_qty<>0 OR pending_putaway_qty<>0 OR abnormal_qty<>0)",Long.class,id);
            Long tasks=db.queryForObject("SELECT COUNT(*) FROM warehouse_location_task_claims WHERE location_id=? AND status='OPEN'",Long.class,id);
            if((balances!=null&&balances>0)||(tasks!=null&&tasks>0))throw new IllegalArgumentException("该库位有数量或未完成任务，不能变更实体层/位；请先通过业务单据处理库存并完成任务");
        }
        if(!"PRIMARY_PICK".equals(type) && !db.queryForList("SELECT id FROM warehouse_goods_location_assignments WHERE location_id=? AND status='ACTIVE' AND is_primary_pick=TRUE LIMIT 1",id).isEmpty())throw new IllegalArgumentException("库位仍是货品主拣货位，请先变更货品绑定");
        db.update("UPDATE warehouse_locations SET level_id=?,location_code=?,slot_code=?,location_name=?,scan_code=?,location_type=?,note=? WHERE id=?",levelId,code(body.get("locationCode"),80,"库位编码"),slotCode,optional(body.get("locationName"),120,"库位名称"),optional(body.get("scanCode"),100,"扫码编码"),type,optional(body.get("note"),1000,"备注"),id);
        audit(user,req,"location.update","warehouse_location",id,Map.of("before",before,"after",body));return ApiResponse.ok(Map.of("updated",true));
    }

    @PostMapping("/{id}/deactivate") @Transactional
    public ApiResponse<Map<String,Object>> deactivate(@PathVariable long id,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user);Map<String,Object> location=lockedLocation(id);if("INACTIVE".equals(location.get("status")))return ApiResponse.ok(Map.of("deactivated",true));
        Long occupied=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances WHERE location_id=? AND (available_qty<>0 OR locked_qty<>0 OR pending_putaway_qty<>0 OR abnormal_qty<>0)",Long.class,id);
        if(occupied!=null&&occupied>0)throw new IllegalArgumentException("该库位仍有库存或锁定/待上架/异常数量，请先通过有来源单据的移库或调整处理，再停用");
        Long tasks=db.queryForObject("SELECT COUNT(*) FROM warehouse_location_task_claims WHERE location_id=? AND status='OPEN'",Long.class,id);
        if(tasks!=null&&tasks>0)throw new IllegalArgumentException("该库位仍有未完成任务，请先完成或取消任务，再停用");
        Long bindings=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments WHERE location_id=? AND status='ACTIVE'",Long.class,id);
        if(bindings!=null&&bindings>0)throw new IllegalArgumentException("该库位仍绑定仓库货品，请先解除或改配货品库位，再停用");
        db.update("UPDATE warehouse_locations SET status='INACTIVE' WHERE id=?",id);
        audit(user,req,"location.deactivate","warehouse_location",id,location);return ApiResponse.ok(Map.of("deactivated",true));
    }

    @PostMapping("/{id}/activate") @Transactional
    public ApiResponse<Map<String,Object>> activate(@PathVariable long id,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user);Map<String,Object> location=lockedLocation(id);warehouse(((Number)location.get("warehouseId")).longValue(),true);
        db.update("UPDATE warehouse_locations SET status='ACTIVE' WHERE id=?",id);
        audit(user,req,"location.activate","warehouse_location",id,location);return ApiResponse.ok(Map.of("activated",true));
    }

    @GetMapping("/assignments")
    public ApiResponse<Map<String,Object>> assignments(@RequestParam long warehouseId,@RequestParam(required=false) Long goodsId,@AuthenticationPrincipal AuthUser user) {
        view(user);warehouse(warehouseId,false);
        String sql="SELECT x.id,x.warehouse_id warehouseId,x.warehouse_goods_id warehouseGoodsId,g.goods_code goodsCode,g.goods_name goodsName,x.location_id locationId,l.location_code locationCode,l.location_type locationType,x.is_primary_pick isPrimaryPick,x.status FROM warehouse_goods_location_assignments x JOIN warehouse_goods g ON g.id=x.warehouse_goods_id JOIN warehouse_locations l ON l.id=x.location_id WHERE x.warehouse_id=?"+(goodsId==null?"":" AND x.warehouse_goods_id=?")+" ORDER BY g.goods_code,x.is_primary_pick DESC,l.location_code LIMIT 1000";
        return ApiResponse.ok(Map.of("assignments",goodsId==null?db.queryForList(sql,warehouseId):db.queryForList(sql,warehouseId,goodsId)));
    }

    @PostMapping("/assignments") @Transactional
    public ApiResponse<Map<String,Object>> assign(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user);long warehouseId=number(body.get("warehouseId"),"仓库"),goodsId=number(body.get("warehouseGoodsId"),"货品"),locationId=number(body.get("locationId"),"库位");
        warehouse(warehouseId,true);good(goodsId);Map<String,Object> location=lockedLocation(locationId);
        if(((Number)location.get("warehouseId")).longValue()!=warehouseId||!"ACTIVE".equals(location.get("status")))throw new IllegalArgumentException("请选择当前仓库的启用库位");
        boolean primary=Boolean.TRUE.equals(body.get("isPrimaryPick"));
        if(primary && !"PRIMARY_PICK".equals(location.get("locationType")))throw new IllegalArgumentException("主拣货位必须使用主拣货类型库位");
        // Locking the good serializes competing primary-location changes; the DB unique key is the final guard.
        db.queryForMap("SELECT id FROM warehouse_goods WHERE id=? FOR UPDATE",goodsId);
        if(primary)db.update("UPDATE warehouse_goods_location_assignments SET is_primary_pick=FALSE WHERE warehouse_id=? AND warehouse_goods_id=? AND status='ACTIVE'",warehouseId,goodsId);
        Long existing=db.query("SELECT id FROM warehouse_goods_location_assignments WHERE warehouse_goods_id=? AND location_id=?",rs->rs.next()?rs.getLong(1):null,goodsId,locationId);
        long id;
        if(existing==null){db.update("INSERT INTO warehouse_goods_location_assignments(warehouse_id,warehouse_goods_id,location_id,is_primary_pick,created_by) VALUES (?,?,?,?,?)",warehouseId,goodsId,locationId,primary,user.id());id=lastId();}
        else{id=existing;db.update("UPDATE warehouse_goods_location_assignments SET status='ACTIVE',is_primary_pick=? WHERE id=?",primary,id);}
        audit(user,req,"location.assignment.save","warehouse_goods_location_assignment",id,body);return ApiResponse.ok(Map.of("id",id));
    }

    @PostMapping("/assignments/{id}/deactivate") @Transactional
    public ApiResponse<Map<String,Object>> unassign(@PathVariable long id,@AuthenticationPrincipal AuthUser user,HttpServletRequest req) {
        manage(user);Map<String,Object> before=db.queryForMap("SELECT id,warehouse_id warehouseId,warehouse_goods_id warehouseGoodsId,location_id locationId,status,is_primary_pick isPrimaryPick FROM warehouse_goods_location_assignments WHERE id=? FOR UPDATE",id);
        long locationId=((Number)before.get("locationId")).longValue(),goodsId=((Number)before.get("warehouseGoodsId")).longValue();
        Long occupied=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances WHERE warehouse_goods_id=? AND location_id=? AND (available_qty<>0 OR locked_qty<>0 OR pending_putaway_qty<>0 OR abnormal_qty<>0)",Long.class,goodsId,locationId);
        if(occupied!=null&&occupied>0)throw new IllegalArgumentException("该货品在此库位仍有数量，请先通过业务单据移库或调整，再解绑");
        db.update("UPDATE warehouse_goods_location_assignments SET status='INACTIVE',is_primary_pick=FALSE WHERE id=?",id);
        audit(user,req,"location.assignment.deactivate","warehouse_goods_location_assignment",id,before);return ApiResponse.ok(Map.of("deactivated",true));
    }

    @GetMapping("/stock-summary")
    public ApiResponse<Map<String,Object>> stockSummary(@RequestParam long warehouseId,@RequestParam(required=false) Long goodsId,@RequestParam(required=false) Long locationId,@AuthenticationPrincipal AuthUser user) {
        stockView(user);warehouse(warehouseId,false);
        String sql="SELECT g.id warehouseGoodsId,g.goods_code goodsCode,g.goods_name goodsName,g.unit,COUNT(b.id) balanceRows,MAX(p.id) pendingBalanceId,COALESCE(SUM(b.available_qty),0) availableQty,COALESCE(SUM(b.locked_qty),0) lockedQty,COALESCE(SUM(b.pending_putaway_qty),0) pendingPutawayQty,COALESCE(SUM(b.abnormal_qty),0) abnormalQty,COALESCE(SUM(CASE WHEN b.position_status='HISTORICAL_UNLOCATED' THEN b.available_qty+b.locked_qty+b.pending_putaway_qty+b.abnormal_qty ELSE 0 END),0) historicalUnlocatedQty,"+(locationId==null?"COALESCE(MAX(p.pending_inbound_qty),0) pendingInboundQty,COALESCE(MAX(p.pending_outbound_qty),0) pendingOutboundQty":"0 pendingInboundQty,0 pendingOutboundQty")+" FROM warehouse_goods g LEFT JOIN warehouse_goods_location_balances b ON b.warehouse_goods_id=g.id AND b.warehouse_id=?"+(locationId==null?"":" AND b.location_id=?")+" LEFT JOIN warehouse_goods_pending_balances p ON p.warehouse_goods_id=g.id AND p.warehouse_id=? WHERE g.is_deleted=FALSE"+(goodsId==null?"":" AND g.id=?")+(locationId==null?"":" AND b.id IS NOT NULL")+" GROUP BY g.id,g.goods_code,g.goods_name,g.unit ORDER BY g.goods_code LIMIT 1000";
        List<Object> args=new ArrayList<>();args.add(warehouseId);if(locationId!=null)args.add(locationId);args.add(warehouseId);if(goodsId!=null)args.add(goodsId);
        return ApiResponse.ok(Map.of("items",db.queryForList(sql,args.toArray()),"basis","LOCATION_AND_WAREHOUSE_PENDING_BALANCES","legacySkuStockIncluded",false));
    }

    @GetMapping("/stock-details")
    public ApiResponse<Map<String,Object>> stockDetails(@RequestParam long warehouseId,@RequestParam(required=false) Long goodsId,@RequestParam(required=false) Long locationId,@AuthenticationPrincipal AuthUser user) {
        stockView(user);warehouse(warehouseId,false);
        String sql="SELECT b.id,b.warehouse_goods_id warehouseGoodsId,g.goods_code goodsCode,g.goods_name goodsName,g.unit,b.location_id locationId,b.position_status positionStatus,CASE WHEN b.position_status='HISTORICAL_UNLOCATED' THEN '历史未定位' ELSE l.location_code END locationCode,l.location_type locationType,b.available_qty availableQty,b.locked_qty lockedQty,b.pending_putaway_qty pendingPutawayQty,b.abnormal_qty abnormalQty,b.updated_at updatedAt FROM warehouse_goods_location_balances b JOIN warehouse_goods g ON g.id=b.warehouse_goods_id LEFT JOIN warehouse_locations l ON l.id=b.location_id WHERE b.warehouse_id=?"+(goodsId==null?"":" AND b.warehouse_goods_id=?")+(locationId==null?"":" AND b.location_id=?")+" ORDER BY g.goods_code,b.position_status,b.location_id LIMIT 1000";
        List<Object> args=new ArrayList<>();args.add(warehouseId);if(goodsId!=null)args.add(goodsId);if(locationId!=null)args.add(locationId);
        return ApiResponse.ok(Map.of("items",db.queryForList(sql,args.toArray()),"basis","LOCATION_BALANCES_ONLY","legacySkuStockIncluded",false));
    }

    private Map<String,Object> lockedLocation(long id){return db.queryForMap("SELECT id,warehouse_id warehouseId,level_id levelId,location_code locationCode,slot_code slotCode,location_name locationName,scan_code scanCode,location_type locationType,status,note FROM warehouse_locations WHERE id=? FOR UPDATE",id);}
    private void warehouse(long id,boolean active){String status=db.query("SELECT status FROM warehouses WHERE id=?",rs->rs.next()?rs.getString(1):null,id);if(status==null || active&&!"ACTIVE".equals(status))throw new IllegalArgumentException("仓库不存在或已停用");}
    private void good(long id){String status=db.query("SELECT status FROM warehouse_goods WHERE id=? AND is_deleted=FALSE",rs->rs.next()?rs.getString(1):null,id);if(!"ACTIVE".equals(status))throw new IllegalArgumentException("请选择启用的仓库货品");}
    private void level(long warehouseId,long id){String kind=db.query("SELECT node_kind FROM warehouse_location_nodes WHERE warehouse_id=? AND id=?",rs->rs.next()?rs.getString(1):null,warehouseId,id);if(!"LEVEL".equals(kind))throw new IllegalArgumentException("请选择当前仓库的层");}
    private void view(AuthUser user){auth.requirePermission(user.id(),"warehouse.locations.view");}
    private void lookupView(AuthUser user){if(!auth.hasPermission(user.id(),"warehouse.location_stock.view"))view(user);}
    private void manage(AuthUser user){view(user);auth.requirePermission(user.id(),"button.locations.manage");}
    private void stockView(AuthUser user){auth.requirePermission(user.id(),"warehouse.location_stock.view");}
    private long lastId(){return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));}
    private static long number(Object value,String label){try{long id=Long.parseLong(String.valueOf(value));if(id>0)return id;}catch(Exception ignored){}throw new IllegalArgumentException("请选择有效"+label);}
    private static Long optionalNumber(Object value){return value==null||String.valueOf(value).isBlank()?null:number(value,"父级");}
    private static String required(Object value,int max,String label){String s=Objects.toString(value,"").trim();if(s.isEmpty()||s.length()>max)throw new IllegalArgumentException(label+"必填且不能超过 "+max+" 字符");return s;}
    private static String optional(Object value,int max,String label){String s=Objects.toString(value,"").trim();if(s.length()>max)throw new IllegalArgumentException(label+"不能超过 "+max+" 字符");return s.isEmpty()?null:s;}
    private static String code(Object value,int max,String label){return required(value,max,label).toUpperCase(Locale.ROOT);}
    private static String choice(Object value,String label,String... choices){String s=Objects.toString(value,"");if(Arrays.asList(choices).contains(s))return s;throw new IllegalArgumentException("无效的"+label);}
    private void audit(AuthUser user,HttpServletRequest req,String action,String resource,long id,Object detail){try{db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,TRUE)",user.id(),user.username(),action,resource,String.valueOf(id),json.writeValueAsString(detail),req.getRemoteAddr());}catch(Exception e){throw new IllegalStateException("操作日志写入失败",e);}}
}
