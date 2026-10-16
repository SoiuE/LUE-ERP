package com.lue.erp.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
public class WarehouseGoodsController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;

    public WarehouseGoodsController(JdbcTemplate db, AuthService auth, ObjectMapper json) {
        this.db = db; this.auth = auth; this.json = json;
    }

    @GetMapping("/api/skus/{skuId}/product-details")
    public ApiResponse<Map<String,Object>> productDetails(@PathVariable long skuId,@AuthenticationPrincipal AuthUser u) {
        auth.requirePermission(u.id(),"master.skus.view"); requireSku(skuId);
        Map<String,Object> result=db.queryForMap("SELECT id,internal_sku internalSku,product_name productName,spu,specification,barcode,status,note FROM skus WHERE id=? AND is_deleted=FALSE",skuId);
        if(auth.hasPermission(u.id(),"master.mappings.view"))result.put("mappings",db.queryForList("SELECT m.id,m.sku_id skuId,m.shop_id shopId,s.shop_name shopName,s.shop_identifier shopIdentifier,m.platform_sku platformSku,m.platform_product_id platformProductId,m.platform_product_name platformProductName,m.default_order_mode defaultOrderMode,m.status FROM shop_sku_mappings m JOIN shops s ON s.id=m.shop_id WHERE m.sku_id=? AND m.is_deleted=FALSE ORDER BY m.id DESC",skuId));
        return ApiResponse.ok(result);
    }

    @GetMapping("/api/warehouse-goods")
    public ApiResponse<Map<String,Object>> goods(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) String q,
            @RequestParam(required=false) String status, @AuthenticationPrincipal AuthUser user) {
        viewGoods(user);
        int p=Math.max(1,page), s=Math.min(100,Math.max(1,pageSize));
        String where=" WHERE g.is_deleted=FALSE"; List<Object> args=new ArrayList<>();
        if(q!=null&&!q.isBlank()) { where+=" AND (g.goods_code LIKE ? OR g.goods_name LIKE ? OR g.specification LIKE ? OR g.barcode LIKE ?)"; for(int i=0;i<4;i++)args.add("%"+q.trim()+"%"); }
        if(status!=null&&!status.isBlank()){where+=" AND g.status=?";args.add(status);}
        Long total=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods g"+where,Long.class,args.toArray());
        args.add(s);args.add((p-1)*s);
        List<Map<String,Object>> items=db.queryForList("SELECT g.id,g.goods_code goodsCode,g.goods_name goodsName,g.specification,g.barcode,g.unit,g.default_supplier_id defaultSupplierId,s.supplier_name supplierName,g.reference_purchase_price referencePurchasePrice,g.status,g.note,g.created_at createdAt,g.updated_at updatedAt FROM warehouse_goods g LEFT JOIN suppliers s ON s.id=g.default_supplier_id"+where+" ORDER BY g.goods_code LIMIT ? OFFSET ?",args.toArray());
        return ApiResponse.ok(Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s));
    }

    @GetMapping("/api/warehouse-goods/lookups")
    public ApiResponse<Map<String,Object>> lookups(@AuthenticationPrincipal AuthUser user) {
        viewGoods(user);
        return ApiResponse.ok(Map.of("suppliers",db.queryForList("SELECT id,supplier_code supplierCode,supplier_name supplierName FROM suppliers WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY supplier_name")));
    }

    @PostMapping("/api/warehouse-goods") @Transactional
    public ApiResponse<Map<String,Object>> createGoods(@RequestBody Map<String,Object> b,@AuthenticationPrincipal AuthUser u,HttpServletRequest req) {
        manageGoods(u); validateGoods(b); String code=required(b,"goodsCode").toUpperCase(Locale.ROOT),name=required(b,"goodsName"),unit=required(b,"unit");
        BigDecimal price=nonNegative(b.getOrDefault("referencePurchasePrice",BigDecimal.ZERO),"参考采购价");
        Long supplier=optionalLong(b.get("defaultSupplierId")); if(supplier!=null) requireSupplier(supplier);
        String spec=text(b.get("specification")),barcode=text(b.get("barcode")),note=text(b.get("note")); String status=status(b.get("status"));
        db.update("INSERT INTO warehouse_goods(goods_code,goods_name,specification,barcode,unit,default_supplier_id,reference_purchase_price,status,note,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",code,name,spec,barcode,unit,supplier,price,status,note,u.id());
        long id=lastId(); audit(u,req,"warehouse_goods.create","warehouse_goods",id,b); return ApiResponse.ok(Map.of("id",id));
    }

    @PutMapping("/api/warehouse-goods/{id}") @Transactional
    public ApiResponse<Map<String,Boolean>> updateGoods(@PathVariable long id,@RequestBody Map<String,Object> b,@AuthenticationPrincipal AuthUser u,HttpServletRequest req) {
        manageGoods(u); validateGoods(b); existsGoods(id); Map<String,Object> before=db.queryForMap("SELECT goods_code goodsCode,goods_name goodsName,specification,barcode,unit,default_supplier_id defaultSupplierId,reference_purchase_price referencePurchasePrice,status,note FROM warehouse_goods WHERE id=? FOR UPDATE",id);
        String code=required(b,"goodsCode").toUpperCase(Locale.ROOT),name=required(b,"goodsName"),unit=required(b,"unit");
        if(!unit.equals(before.get("unit"))&&!db.queryForList("SELECT id FROM sku_composition_items WHERE warehouse_goods_id=? LIMIT 1 FOR SHARE",id).isEmpty())throw new IllegalArgumentException("该货品已被组成清单引用，不能修改单位；请新建货品以保留历史用量口径");
        BigDecimal price=nonNegative(b.getOrDefault("referencePurchasePrice",BigDecimal.ZERO),"参考采购价");
        Long supplier=optionalLong(b.get("defaultSupplierId")); if(supplier!=null)requireSupplier(supplier);
        db.update("UPDATE warehouse_goods SET goods_code=?,goods_name=?,specification=?,barcode=?,unit=?,default_supplier_id=?,reference_purchase_price=?,status=?,note=? WHERE id=? AND is_deleted=FALSE",code,name,text(b.get("specification")),text(b.get("barcode")),unit,supplier,price,status(b.get("status")),text(b.get("note")),id);
        audit(u,req,"warehouse_goods.update","warehouse_goods",id,Map.of("before",before,"after",b)); return ApiResponse.ok(Map.of("updated",true));
    }

    @PostMapping("/api/warehouse-goods/{id}/deactivate") @Transactional
    public ApiResponse<Map<String,Boolean>> deactivateGoods(@PathVariable long id,@AuthenticationPrincipal AuthUser u,HttpServletRequest req) {
        manageGoods(u); existsGoods(id); db.update("UPDATE warehouse_goods SET status='INACTIVE' WHERE id=?",id);
        audit(u,req,"warehouse_goods.deactivate","warehouse_goods",id,Map.of("status","INACTIVE"));return ApiResponse.ok(Map.of("deactivated",true));
    }

    @GetMapping("/api/skus/{skuId}/compositions")
    public ApiResponse<Map<String,Object>> versions(@PathVariable long skuId,@AuthenticationPrincipal AuthUser u) {
        viewCompositions(u); requireSku(skuId);
        List<Map<String,Object>> versions=db.queryForList("SELECT id,sku_id skuId,version_no versionNo,status,effective_from effectiveFrom,effective_to effectiveTo,change_reason changeReason,created_by createdBy,created_at createdAt,deactivation_reason deactivationReason,deactivated_at deactivatedAt,deactivated_by deactivatedBy FROM sku_composition_versions WHERE sku_id=? ORDER BY version_no DESC",skuId);
        for(Map<String,Object> v:versions)v.put("items",compositionItems(((Number)v.get("id")).longValue()));
        return ApiResponse.ok(Map.of("versions",versions));
    }

    @PostMapping("/api/skus/{skuId}/compositions") @Transactional
    public ApiResponse<Map<String,Object>> createVersion(@PathVariable long skuId,@RequestBody Map<String,Object> b,@AuthenticationPrincipal AuthUser u,HttpServletRequest req) {
        manageCompositions(u); requireSku(skuId); String reason=required(b,"changeReason"); maxLength(reason,1000,"修改原因"); LocalDate from=date(b.get("effectiveFrom")),to=date(b.get("effectiveTo"));
        if(from!=null&&to!=null&&to.isBefore(from))throw new IllegalArgumentException("停用日期不能早于生效日期");
        List<Map<String,Object>> rows=maps(b.get("items")); if(rows.isEmpty())throw new IllegalArgumentException("组成清单至少需要一个主要货品");
        Integer primaryCount=0; for(Map<String,Object> row:rows)if(row.get("substituteForIndex")==null)primaryCount++;
        if(primaryCount==0)throw new IllegalArgumentException("组成清单至少需要一个主要货品");
        db.queryForMap("SELECT id FROM skus WHERE id=? FOR UPDATE",skuId);
        validateCompositionRows(rows);
        Integer version=db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM sku_composition_versions WHERE sku_id=? FOR UPDATE",Integer.class,skuId);
        String state=Objects.toString(b.getOrDefault("status","ACTIVE")); if(!Set.of("ACTIVE","INACTIVE").contains(state))throw new IllegalArgumentException("版本状态无效");
        if("ACTIVE".equals(state))ensureNoOverlap(skuId,from,to);
        db.update("INSERT INTO sku_composition_versions(sku_id,version_no,status,effective_from,effective_to,change_reason,created_by) VALUES (?,?,?,?,?,?,?)",skuId,version,state,from,to,reason,u.id()); long versionId=lastId();
        List<Long> itemIds=new ArrayList<>();
        for(int i=0;i<rows.size();i++) { Map<String,Object> row=rows.get(i); if(row.get("substituteForIndex")!=null)continue; itemIds.add(insertItem(versionId,row,null)); }
        for(int i=0;i<rows.size();i++) { Map<String,Object> row=rows.get(i); if(row.get("substituteForIndex")==null)continue; int parent=index(row.get("substituteForIndex")); if(parent<0||parent>=rows.size()||rows.get(parent).get("substituteForIndex")!=null)throw new IllegalArgumentException("替代货品必须关联有效的主要货品行"); insertItem(versionId,row,itemIds.get(primaryOrdinal(rows,parent))); }
        audit(u,req,"sku_composition.create_version","sku_composition_version",versionId,b);
        return ApiResponse.ok(Map.of("id",versionId,"versionNo",version));
    }

    @PostMapping("/api/skus/{skuId}/compositions/{versionId}/deactivate") @Transactional
    public ApiResponse<Map<String,Boolean>> deactivateVersion(@PathVariable long skuId,@PathVariable long versionId,@RequestBody Map<String,Object> b,@AuthenticationPrincipal AuthUser u,HttpServletRequest req) {
        manageCompositions(u); String reason=required(b,"reason"); maxLength(reason,1000,"停用原因"); LocalDate end=date(b.get("effectiveTo"));
        requireSku(skuId); db.queryForMap("SELECT id FROM skus WHERE id=? FOR UPDATE",skuId);
        Map<String,Object> v=db.queryForMap("SELECT status,effective_from effectiveFrom FROM sku_composition_versions WHERE id=? AND sku_id=? FOR UPDATE",versionId,skuId);
        if("INACTIVE".equals(v.get("status")))throw new IllegalArgumentException("该版本已停用");
        LocalDate from=sqlDate(v.get("effectiveFrom")); if(end!=null&&from!=null&&end.isBefore(from))throw new IllegalArgumentException("停用日期不能早于生效日期");
        db.update("UPDATE sku_composition_versions SET status='INACTIVE',effective_to=COALESCE(?,effective_to),deactivation_reason=?,deactivated_by=?,deactivated_at=CURRENT_TIMESTAMP WHERE id=?",end,reason,u.id(),versionId);
        audit(u,req,"sku_composition.deactivate_version","sku_composition_version",versionId,Map.of("reason",reason,"effectiveTo",Objects.toString(end,"")));
        return ApiResponse.ok(Map.of("deactivated",true));
    }

    @GetMapping("/api/skus/{skuId}/compositions/preview")
    public ApiResponse<Map<String,Object>> preview(@PathVariable long skuId,@RequestParam BigDecimal quantity,@RequestParam(required=false) String date,@RequestParam(required=false) Long versionId,@AuthenticationPrincipal AuthUser u) {
        viewCompositions(u); requireSku(skuId); quantity=positive(quantity,"销售数量");
        LocalDate on=date==null||date.isBlank()?LocalDate.now(BUSINESS_ZONE):date(date);
        List<Map<String,Object>> selected=versionId==null?db.queryForList("SELECT id,version_no versionNo FROM sku_composition_versions WHERE sku_id=? AND status='ACTIVE' AND (effective_from IS NULL OR effective_from<=?) AND (effective_to IS NULL OR effective_to>=?) ORDER BY effective_from DESC,version_no DESC",skuId,on,on):db.queryForList("SELECT id,version_no versionNo FROM sku_composition_versions WHERE sku_id=? AND id=?",skuId,versionId);
        if(selected.isEmpty())throw new IllegalArgumentException("指定日期没有生效的组成清单版本");
        if(selected.size()>1)throw new IllegalStateException("同一日期存在多个生效版本，请先处理版本日期重叠");
        long selectedId=((Number)selected.get(0).get("id")).longValue(); List<Map<String,Object>> all=compositionItems(selectedId),expanded=new ArrayList<>();
        for(Map<String,Object> row:all)if(row.get("substituteForItemId")==null){Map<String,Object>x=new LinkedHashMap<>(row);x.put("requiredQuantity",((BigDecimal)row.get("quantity")).multiply(quantity));List<Map<String,Object>>alternatives=new ArrayList<>();for(Map<String,Object>a:all)if(Objects.equals(a.get("substituteForItemId"),row.get("id"))){Map<String,Object>option=new LinkedHashMap<>(a);option.put("requiredQuantity",((BigDecimal)a.get("quantity")).multiply(quantity));alternatives.add(option);}x.put("alternatives",alternatives);expanded.add(x);}
        return ApiResponse.ok(Map.of("skuId",skuId,"versionId",selectedId,"versionNo",selected.get(0).get("versionNo"),"salesQuantity",quantity,"effectiveDate",on,"items",expanded));
    }

    private List<Map<String,Object>> compositionItems(long versionId){return db.queryForList("SELECT i.id,i.version_id versionId,i.warehouse_goods_id warehouseGoodsId,g.goods_code goodsCode,g.goods_name goodsName,g.specification,g.unit,i.quantity,i.substitute_for_item_id substituteForItemId,i.note FROM sku_composition_items i JOIN warehouse_goods g ON g.id=i.warehouse_goods_id WHERE i.version_id=? ORDER BY i.id",versionId);}
    private void validateCompositionRows(List<Map<String,Object>> rows){
        if(rows.size()>500)throw new IllegalArgumentException("单个组成版本最多 500 行");
        Set<String>unique=new HashSet<>();
        for(Map<String,Object>r:rows){
            long goods=number(r.get("warehouseGoodsId"),"货品"); existsActiveGoods(goods);
            r.put("_quantity",positive(r.get("quantity"),"货品用量")); maxLength(text(r.get("note")),500,"明细说明");
            Integer parent=r.get("substituteForIndex")==null?null:index(r.get("substituteForIndex"));
            if(parent!=null){if(parent<0||parent>=rows.size()||rows.get(parent).get("substituteForIndex")!=null)throw new IllegalArgumentException("替代货品必须关联同一版本的主要货品行");if(goods==number(rows.get(parent).get("warehouseGoodsId"),"主要货品"))throw new IllegalArgumentException("货品不能替代自身");}
            if(!unique.add((parent==null?"PRIMARY":parent.toString())+":"+goods))throw new IllegalArgumentException("主要货品或同一主要货品的替代件不能重复，请合并用量");
        }
    }
    private long insertItem(long version,Map<String,Object>r,Long parent){db.update("INSERT INTO sku_composition_items(version_id,warehouse_goods_id,quantity,substitute_for_item_id,note) VALUES (?,?,?,?,?)",version,number(r.get("warehouseGoodsId"),"货品"),r.get("_quantity"),parent,text(r.get("note")));return lastId();}
    private void ensureNoOverlap(long sku,LocalDate from,LocalDate to){
        LocalDate newStart=from==null?LocalDate.MIN:from,newEnd=to==null?LocalDate.MAX:to;
        List<Map<String,Object>> active=db.queryForList("SELECT effective_from effectiveFrom,effective_to effectiveTo FROM sku_composition_versions WHERE sku_id=? AND status='ACTIVE' FOR UPDATE",sku);
        for(Map<String,Object>row:active){LocalDate oldStart=sqlDate(row.get("effectiveFrom")),oldEnd=sqlDate(row.get("effectiveTo"));if(oldStart==null)oldStart=LocalDate.MIN;if(oldEnd==null)oldEnd=LocalDate.MAX;if(!newStart.isAfter(oldEnd)&&!oldStart.isAfter(newEnd))throw new IllegalArgumentException("该生效区间与现有启用版本重叠");}
    }
    private int primaryOrdinal(List<Map<String,Object>>rows,int sourceIndex){int n=0;for(int i=0;i<sourceIndex;i++)if(rows.get(i).get("substituteForIndex")==null)n++;return n;}
    private void requireSku(long id){Integer n=db.queryForObject("SELECT COUNT(*) FROM skus WHERE id=? AND is_deleted=FALSE",Integer.class,id);if(n==null||n==0)throw new IllegalArgumentException("内部销售 SKU 不存在");}
    private void existsGoods(long id){Integer n=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods WHERE id=? AND is_deleted=FALSE",Integer.class,id);if(n==null||n==0)throw new IllegalArgumentException("仓库货品不存在");}
    private void existsActiveGoods(long id){List<Map<String,Object>> rows=db.queryForList("SELECT id FROM warehouse_goods WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE FOR SHARE",id);if(rows.isEmpty())throw new IllegalArgumentException("组成清单只能引用启用中的仓库货品");}
    private void validateGoods(Map<String,Object>b){maxLength(required(b,"goodsCode"),100,"货品编码");maxLength(required(b,"goodsName"),255,"货品名称");maxLength(required(b,"unit"),32,"单位");maxLength(text(b.get("specification")),255,"规格");maxLength(text(b.get("barcode")),100,"条码");maxLength(text(b.get("note")),1000,"备注");}
    private static void maxLength(String value,int max,String label){if(value!=null&&value.length()>max)throw new IllegalArgumentException(label+"最多 "+max+" 个字符");}
    private void requireSupplier(long id){Integer n=db.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",Integer.class,id);if(n==null||n==0)throw new IllegalArgumentException("默认供应商不存在或已停用");}
    private void viewGoods(AuthUser u){auth.requirePermission(u.id(),"master.goods.view");}
    private void manageGoods(AuthUser u){viewGoods(u);auth.requirePermission(u.id(),"button.goods.manage");}
    private void viewCompositions(AuthUser u){auth.requirePermission(u.id(),"master.compositions.view");auth.requirePermission(u.id(),"master.skus.view");}
    private void manageCompositions(AuthUser u){viewCompositions(u);auth.requirePermission(u.id(),"button.compositions.manage");}
    private void audit(AuthUser u,HttpServletRequest req,String action,String type,long id,Object detail){try{db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,TRUE)",u.id(),u.username(),action,type,String.valueOf(id),json.writeValueAsString(detail),req.getRemoteAddr());}catch(Exception e){throw new IllegalStateException("操作日志写入失败",e);}}
    private long lastId(){return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));}
    private static String required(Map<String,Object>b,String k){String s=text(b.get(k));if(s==null||s.isBlank())throw new IllegalArgumentException(k+" 为必填项");return s;}
    private static String text(Object x){return x==null?null:String.valueOf(x).trim();}
    private static String status(Object x){String s=Objects.toString(x,"ACTIVE");if(!Set.of("ACTIVE","INACTIVE").contains(s))throw new IllegalArgumentException("状态值无效");return s;}
    private static BigDecimal positive(Object x,String label){BigDecimal n=nonNegative(x,label);if(n.signum()==0)throw new IllegalArgumentException(label+"必须大于 0");return n;}
    private static BigDecimal nonNegative(Object x,String label){try{BigDecimal n=new BigDecimal(Objects.toString(x,"0"));BigDecimal normalized=n.stripTrailingZeros();if(n.signum()<0||normalized.precision()>18||normalized.scale()>4||normalized.precision()-normalized.scale()>14)throw new IllegalArgumentException(label+"格式无效");return n;}catch(NumberFormatException e){throw new IllegalArgumentException(label+"必须为有效数字");}}
    private static long number(Object x,String label){try{return Long.parseLong(String.valueOf(x));}catch(Exception e){throw new IllegalArgumentException(label+"无效");}}
    private static int index(Object x){try{return Integer.parseInt(String.valueOf(x));}catch(Exception e){throw new IllegalArgumentException("替代货品行关联无效");}}
    private static Long optionalLong(Object x){return x==null||String.valueOf(x).isBlank()?null:number(x,"供应商");}
    private static LocalDate date(Object x){if(x==null||String.valueOf(x).isBlank())return null;try{return LocalDate.parse(String.valueOf(x));}catch(Exception e){throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD");}}
    private static LocalDate sqlDate(Object x){return x==null?null:x instanceof Date d?d.toLocalDate():LocalDate.parse(String.valueOf(x));}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object x){if(!(x instanceof List<?>l))throw new IllegalArgumentException("组成明细必须为数组");List<Map<String,Object>>rows=new ArrayList<>();for(Object item:l){if(!(item instanceof Map<?,?>))throw new IllegalArgumentException("组成明细格式无效");rows.add(new LinkedHashMap<>((Map<String,Object>)item));}return rows;}
}
