package com.lue.erp.warehouse;

import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper json;

    public WarehouseController(JdbcTemplate db, AuthService auth, ObjectMapper json) { this.db = db; this.auth = auth; this.json=json; }

    @GetMapping("/lookups")
    public ApiResponse<Map<String,Object>> lookups(@AuthenticationPrincipal AuthUser user) {
        purchaseView(user);
        return ApiResponse.ok(Map.of(
            "suppliers", db.queryForList("SELECT id,supplier_code supplierCode,supplier_name supplierName FROM suppliers WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY supplier_name"),
            "skus", db.queryForList("SELECT id,internal_sku internalSku,product_name productName,purchase_price purchasePrice FROM skus WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY internal_sku"),
            "materials", db.queryForList("SELECT id,material_code materialCode,material_name materialName,category,unit FROM package_materials WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY material_code")
        ));
    }

    @GetMapping("/purchases")
    public ApiResponse<Map<String,Object>> purchases(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String q, @RequestParam(required=false) String trackingNo,
            @RequestParam(required=false) String orderNo, @AuthenticationPrincipal AuthUser user) {
        purchaseView(user); int p=Math.max(1,page), s=Math.min(100,Math.max(1,pageSize));
        String where=" WHERE 1=1"; List<Object> args=new ArrayList<>();
        if(q!=null&&!q.isBlank()){where+=" AND (po.po_no LIKE ? OR po.tracking_no LIKE ? OR po.order_no LIKE ? OR sp.supplier_name LIKE ?)";for(int i=0;i<4;i++)args.add("%"+q.trim()+"%");}
        if(trackingNo!=null&&!trackingNo.isBlank()){where+=" AND po.tracking_no LIKE ?";args.add("%"+trackingNo.trim()+"%");}
        if(orderNo!=null&&!orderNo.isBlank()){where+=" AND po.order_no LIKE ?";args.add("%"+orderNo.trim()+"%");}
        String from=" FROM purchase_orders po JOIN suppliers sp ON sp.id=po.supplier_id";
        Long total=db.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        args.add(s);args.add((p-1)*s);
        var items=db.queryForList("SELECT po.id,po.po_no poNo,po.tracking_no trackingNo,po.order_no orderNo,po.supplier_id supplierId,sp.supplier_name supplierName,po.status,po.is_abnormal isAbnormal,po.abnormal_reason abnormalReason,po.abnormal_handled_at abnormalHandledAt,po.abnormal_handling_note abnormalHandlingNote,po.expected_arrival_date expectedArrivalDate,po.note,po.created_at createdAt,(SELECT COALESCE(SUM(total_amount),0) FROM purchase_order_items i WHERE i.purchase_order_id=po.id) totalAmount FROM purchase_orders po JOIN suppliers sp ON sp.id=po.supplier_id"+where+" ORDER BY po.id DESC LIMIT ? OFFSET ?",args.toArray());
        return ApiResponse.ok(Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s));
    }

    @GetMapping("/purchases/{id}")
    public ApiResponse<Map<String,Object>> purchase(@PathVariable long id,@AuthenticationPrincipal AuthUser user) {
        purchaseView(user); Map<String,Object> po=db.queryForMap("SELECT po.id,po.po_no poNo,po.tracking_no trackingNo,po.order_no orderNo,po.supplier_id supplierId,sp.supplier_name supplierName,po.status,po.is_abnormal isAbnormal,po.abnormal_reason abnormalReason,po.abnormal_marked_at abnormalMarkedAt,po.abnormal_handled_at abnormalHandledAt,po.abnormal_handling_note abnormalHandlingNote,po.expected_arrival_date expectedArrivalDate,po.note FROM purchase_orders po JOIN suppliers sp ON sp.id=po.supplier_id WHERE po.id=?",id);
        po.put("items",db.queryForList("SELECT i.id,i.sku_id skuId,s.internal_sku internalSku,s.product_name productName,i.ordered_qty orderedQty,i.confirmed_qty confirmedQty,i.received_qty receivedQty,i.unit_price unitPrice,i.total_amount totalAmount FROM purchase_order_items i JOIN skus s ON s.id=i.sku_id WHERE i.purchase_order_id=? ORDER BY i.id",id));
        return ApiResponse.ok(po);
    }

    @PostMapping("/purchases") @Transactional
    public ApiResponse<Map<String,Object>> createPurchase(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        purchaseManage(user); long supplier=number(body,"supplierId"); requireExists("SELECT COUNT(*) FROM suppliers WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",supplier,"供应商不存在或已停用");
        List<Map<String,Object>> items=maps(body.get("items")); if(items.isEmpty())throw new IllegalArgumentException("采购单至少需要一个 SKU");
        String no=nextCode("PURCHASE_ORDER","PO"); db.update("INSERT INTO purchase_orders(po_no,tracking_no,order_no,supplier_id,expected_arrival_date,note,created_by) VALUES (?,?,?,?,?,?,?)",no,text(body.get("trackingNo")),text(body.get("orderNo")),supplier,body.get("expectedArrivalDate"),text(body.get("note")),user.id());
        long id=lastId();
        for(Map<String,Object> row:items){long sku=number(row,"skuId");requireExists("SELECT COUNT(*) FROM skus WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",sku,"SKU 不存在或已停用");BigDecimal qty=positive(row,"quantity"),price=nonNegative(row,"unitPrice");db.update("INSERT INTO purchase_order_items(purchase_order_id,sku_id,ordered_qty,unit_price,total_amount,note) VALUES (?,?,?,?,?,?)",id,sku,qty,price,qty.multiply(price),text(row.get("note")));}
        return ApiResponse.ok(Map.of("id",id,"poNo",no));
    }

    @PostMapping("/purchases/{id}/abnormal") @Transactional
    public ApiResponse<Map<String,Object>> markPurchaseAbnormal(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        purchaseManage(user); lockedPurchase(id); String reason=text(body.get("reason"));
        if(reason==null||reason.isBlank())throw new IllegalArgumentException("请填写采购单异常原因");
        db.update("UPDATE purchase_orders SET is_abnormal=TRUE,abnormal_reason=?,abnormal_marked_by=?,abnormal_marked_at=CURRENT_TIMESTAMP,abnormal_handled_by=NULL,abnormal_handled_at=NULL,abnormal_handling_note=NULL WHERE id=?",reason,user.id(),id);
        return ApiResponse.ok(Map.of("marked",true));
    }

    @PostMapping("/purchases/{id}/abnormal/resolve") @Transactional
    public ApiResponse<Map<String,Object>> resolvePurchaseAbnormal(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        purchaseManage(user); Map<String,Object> po=lockedPurchase(id);
        Integer flagged=db.queryForObject("SELECT is_abnormal FROM purchase_orders WHERE id=?",Integer.class,id);
        if(flagged==null||flagged==0)throw new IllegalArgumentException("该采购单没有待处理异常标记");
        String note=text(body.get("handlingNote"));if(note==null||note.isBlank())throw new IllegalArgumentException("请填写异常处理结果");
        db.update("UPDATE purchase_orders SET is_abnormal=FALSE,abnormal_handled_by=?,abnormal_handled_at=CURRENT_TIMESTAMP,abnormal_handling_note=? WHERE id=?",user.id(),note,po.get("id"));
        return ApiResponse.ok(Map.of("resolved",true));
    }

    @PostMapping("/purchases/{id}/confirm") @Transactional
    public ApiResponse<Map<String,Object>> confirmPurchase(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        purchaseManage(user); Map<String,Object> po=lockedPurchase(id); if(!Set.of("DRAFT","CONFIRMED","PARTIALLY_RECEIVED").contains(String.valueOf(po.get("status"))))throw new IllegalArgumentException("当前采购单状态不能确认");
        List<Map<String,Object>> confirmed=maps(body.get("items"));if(confirmed.isEmpty())throw new IllegalArgumentException("请填写到货确认数量");
        for(Map<String,Object> line:confirmed){long itemId=number(line,"itemId");BigDecimal qty=nonNegative(line,"confirmedQty");Map<String,Object> item=db.queryForMap("SELECT sku_id skuId,ordered_qty orderedQty,confirmed_qty confirmedQty,received_qty receivedQty FROM purchase_order_items WHERE id=? AND purchase_order_id=? FOR UPDATE",itemId,id);if(qty.compareTo(decimal(item.get("receivedQty")))<0)throw new IllegalArgumentException("确认数量不能少于已入库数量");BigDecimal old=decimal(item.get("confirmedQty")),delta=qty.subtract(old),exceed=qty.subtract(decimal(item.get("orderedQty")));String note=text(line.get("overReceiptReason"));if(exceed.signum()>0&&(note==null||note.isBlank()))throw new IllegalArgumentException("确认到货数量超过采购数量时必须填写原因");if(delta.signum()!=0){long sku=((Number)item.get("skuId")).longValue();Stock oldStock=lockStock(sku);db.update("UPDATE inventory_stocks SET pending_inbound_qty=pending_inbound_qty+? WHERE sku_id=?",delta,sku);Stock next=lockStock(sku);flow(sku,"PURCHASE_CONFIRM",BigDecimal.ZERO,oldStock,next,"PURCHASE_ORDER",id,note==null?text(body.get("note")):note,user.id());db.update("UPDATE purchase_order_items SET confirmed_qty=? WHERE id=?",qty,itemId);}}
        db.update("UPDATE purchase_orders SET status='CONFIRMED',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP WHERE id=?",user.id(),id);
        return ApiResponse.ok(Map.of("confirmed",true));
    }

    @PostMapping("/purchases/{id}/receipts") @Transactional
    public ApiResponse<Map<String,Object>> receivePurchase(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        purchaseManage(user); Map<String,Object> po=lockedPurchase(id);if(!Set.of("CONFIRMED","PARTIALLY_RECEIVED").contains(String.valueOf(po.get("status"))))throw new IllegalArgumentException("请先确认采购到货");
        List<Map<String,Object>> lines=maps(body.get("items"));if(lines.isEmpty())throw new IllegalArgumentException("请填写本次入库明细");String receipt=nextCode("PURCHASE_RECEIPT","PR");
        for(Map<String,Object> line:lines){long itemId=number(line,"itemId");BigDecimal qty=positive(line,"quantity");Map<String,Object> item=db.queryForMap("SELECT sku_id skuId,ordered_qty orderedQty,received_qty receivedQty FROM purchase_order_items WHERE id=? AND purchase_order_id=? FOR UPDATE",itemId,id);long sku=((Number)item.get("skuId")).longValue();BigDecimal exceeding=decimal(item.get("receivedQty")).add(qty).subtract(decimal(item.get("orderedQty")));String reason=text(line.get("overReceiptReason"));if(exceeding.signum()>0&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("超出采购数量的入库必须填写原因");
            Stock before=lockStock(sku);BigDecimal pendingDelta=qty.negate().max(before.pendingInbound().negate());db.update("UPDATE inventory_stocks SET available_qty=available_qty+?,pending_inbound_qty=pending_inbound_qty+? WHERE sku_id=?",qty,pendingDelta,sku);Stock after=lockStock(sku);
            db.update("INSERT INTO purchase_receipts(receipt_no,purchase_order_id,purchase_order_item_id,sku_id,received_qty,over_receipt_reason,received_by) VALUES (?,?,?,?,?,?,?)",receipt,id,itemId,sku,qty,reason,user.id());long receiptId=lastId();
            flow(sku,"PURCHASE_INBOUND",qty,before,after,"PURCHASE_RECEIPT",receiptId,reason,user.id());db.update("UPDATE purchase_order_items SET received_qty=received_qty+? WHERE id=?",qty,itemId);db.update("UPDATE fulfillment_shortages SET status='RECEIVED',resolved_at=CURRENT_TIMESTAMP WHERE purchase_order_item_id=? AND status='PURCHASE_LINKED' AND (SELECT received_qty FROM purchase_order_items WHERE id=?)>=shortage_qty",itemId,itemId);
        }
        Integer remaining=db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id=? AND received_qty<confirmed_qty",Integer.class,id);String status=remaining!=null&&remaining==0?"RECEIVED":"PARTIALLY_RECEIVED";db.update("UPDATE purchase_orders SET status=? WHERE id=?",status,id);return ApiResponse.ok(Map.of("receiptNo",receipt,"status",status));
    }

    @GetMapping("/inventory")
    public ApiResponse<Map<String,Object>> inventory(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String q,@AuthenticationPrincipal AuthUser user){inventoryView(user);return ApiResponse.ok(stockList(false,page,pageSize,q));}
    @GetMapping("/inventory/flows")
    public ApiResponse<Map<String,Object>> inventoryFlows(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) Long skuId,@AuthenticationPrincipal AuthUser user){inventoryView(user);return ApiResponse.ok(flowList(false,page,pageSize,skuId));}
    @PostMapping("/inventory/operations") @Transactional
    public ApiResponse<Map<String,Object>> inventoryOperation(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user){inventoryManage(user);String op=operation(body);long sku=number(body,"skuId");requireExists("SELECT COUNT(*) FROM skus WHERE id=? AND is_deleted=FALSE",sku,"SKU 不存在");BigDecimal qty=operationQuantity(body,op);String reason=text(body.get("reason"));if((op.equals("COUNT")||op.equals("ADJUST"))&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("盘点和调整必须填写原因");Stock before=lockStock(sku);BigDecimal delta=switch(op){case "INBOUND"->qty;case "OUTBOUND"->qty.negate();case "COUNT"->qty.subtract(before.available());case "ADJUST"->qty;default->throw new IllegalArgumentException("不支持的库存操作");};String outbound=Objects.toString(body.get("outboundType"),"MANUAL").toUpperCase(Locale.ROOT);if(!Set.of("JIT","STOCKING","MANUAL").contains(outbound))throw new IllegalArgumentException("出库类型仅支持 JIT、备货或手工出库；仓发订单不能从本地库存出库");String type=switch(op){case "INBOUND"->"MANUAL_INBOUND";case "OUTBOUND"->switch(outbound){case "JIT"->"JIT_OUTBOUND";case "STOCKING"->"STOCKING_OUTBOUND";default->"MANUAL_OUTBOUND";};case "COUNT"->"STOCK_COUNT";default->"ADJUSTMENT";};db.update("UPDATE inventory_stocks SET available_qty=available_qty+? WHERE sku_id=?",delta,sku);Stock after=lockStock(sku);long flowId=flow(sku,type,delta,before,after,"MANUAL",null,reason,user.id());List<String> packageWarnings=op.equals("OUTBOUND")&&outbound.equals("JIT")?consumeJitPackaging(sku,qty,user.id(),flowId):List.of();return ApiResponse.ok(Map.of("availableQty",after.available(),"warning",after.available().signum()<0?"可用库存不足，已按宽松模式记账":"","packageWarnings",packageWarnings,"inventoryFlowId",flowId));}

    @GetMapping("/packages")
    public ApiResponse<Map<String,Object>> packages(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String q,@AuthenticationPrincipal AuthUser user){packageView(user);return ApiResponse.ok(stockList(true,page,pageSize,q));}
    @GetMapping("/packages/flows")
    public ApiResponse<Map<String,Object>> packageFlows(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) Long materialId,@AuthenticationPrincipal AuthUser user){packageView(user);return ApiResponse.ok(flowList(true,page,pageSize,materialId));}
    @PostMapping("/packages/operations") @Transactional
    public ApiResponse<Map<String,Object>> packageOperation(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user){packageManage(user);String op=operation(body);long material=number(body,"materialId");String category=db.query("SELECT category FROM package_materials WHERE id=? AND is_deleted=FALSE",rs->rs.next()?rs.getString(1):null,material);if(category==null)throw new IllegalArgumentException("包材不存在");BigDecimal qty=operationQuantity(body,op);String reason=text(body.get("reason"));if((op.equals("COUNT")||op.equals("ADJUST"))&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("盘点和调整必须填写原因");db.update("INSERT IGNORE INTO package_stocks(material_id) VALUES (?)",material);BigDecimal before=db.queryForObject("SELECT available_qty FROM package_stocks WHERE material_id=? FOR UPDATE",BigDecimal.class,material);BigDecimal delta=switch(op){case "INBOUND"->qty;case "OUTBOUND"->qty.negate();case "COUNT"->qty.subtract(before);case "ADJUST"->qty;default->throw new IllegalArgumentException("不支持的包材操作");};String type=switch(op){case "INBOUND"->"MANUAL_INBOUND";case "OUTBOUND"->"MANUAL_OUTBOUND";case "COUNT"->"STOCK_COUNT";default->"ADJUSTMENT";};db.update("UPDATE package_stocks SET available_qty=available_qty+? WHERE material_id=?",delta,material);BigDecimal after=db.queryForObject("SELECT available_qty FROM package_stocks WHERE material_id=?",BigDecimal.class,material);db.update("INSERT INTO package_flows(material_id,material_category,flow_type,quantity_delta,available_before,available_after,reference_type,reason,operator_id) VALUES (?,?,?,?,?,?, 'MANUAL',?,?)",material,category,type,delta,before,after,reason,user.id());boolean shortStock=after.signum()<0;if(shortStock)db.update("INSERT INTO exceptions(exception_no,exception_type,status,title,resolution_note,created_at) VALUES (?,'PACKAGE_SHORTAGE','OPEN',?,?,CURRENT_TIMESTAMP)",nextCode("EXCEPTION","EX"),"包材库存不足："+material,"操作后库存："+after+"；原因："+Objects.toString(reason,""));return ApiResponse.ok(Map.of("availableQty",after,"warning",shortStock?"包材库存不足，已记入异常中心":""));}

    private Map<String,Object> stockList(boolean packs,int page,int size,String q){int p=Math.max(1,page),s=Math.min(100,Math.max(1,size));String table=packs?"package_materials m LEFT JOIN package_stocks st ON st.material_id=m.id":"skus k LEFT JOIN inventory_stocks st ON st.sku_id=k.id";String select=packs?"m.id,m.material_code code,m.material_name name,m.category,m.unit,COALESCE(st.available_qty,0) availableQty,m.safety_stock safetyStock":"k.id,k.internal_sku code,k.product_name name,COALESCE(st.available_qty,0) availableQty,COALESCE(st.locked_qty,0) lockedQty,COALESCE(st.pending_inbound_qty,0) pendingInboundQty,COALESCE(st.pending_outbound_qty,0) pendingOutboundQty,COALESCE(st.abnormal_qty,0) abnormalQty";String where=packs?" WHERE m.is_deleted=FALSE":" WHERE k.is_deleted=FALSE";List<Object>a=new ArrayList<>();if(q!=null&&!q.isBlank()){where+=packs?" AND (m.material_code LIKE ? OR m.material_name LIKE ?)":" AND (k.internal_sku LIKE ? OR k.product_name LIKE ?)";a.add("%"+q.trim()+"%");a.add("%"+q.trim()+"%");}Long total=db.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,a.toArray());a.add(s);a.add((p-1)*s);List<Map<String,Object>>items=db.queryForList("SELECT "+select+" FROM "+table+where+" ORDER BY code LIMIT ? OFFSET ?",a.toArray());return Map.of("items",items,"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s);}
    private Map<String,Object> flowList(boolean packs,int page,int size,Long id){int p=Math.max(1,page),s=Math.min(100,Math.max(1,size));String where=" WHERE 1=1";List<Object>a=new ArrayList<>();String sql;if(packs){sql=" FROM package_flows f JOIN package_materials m ON m.id=f.material_id";if(id!=null){where+=" AND f.material_id=?";a.add(id);} }else{sql=" FROM inventory_flows f JOIN skus k ON k.id=f.sku_id";if(id!=null){where+=" AND f.sku_id=?";a.add(id);}}
        Long total=db.queryForObject("SELECT COUNT(*)"+sql+where,Long.class,a.toArray());a.add(s);a.add((p-1)*s);String cols=packs?"f.id,m.material_code code,m.material_name name,f.material_category category,f.flow_type flowType,f.quantity_delta quantityDelta,f.available_before availableBefore,f.available_after availableAfter,f.reason,f.reference_type referenceType,f.reference_id referenceId,f.created_at createdAt":"f.id,k.internal_sku code,k.product_name name,f.flow_type flowType,f.quantity_delta quantityDelta,f.available_before availableBefore,f.available_after availableAfter,f.pending_inbound_before pendingInboundBefore,f.pending_inbound_after pendingInboundAfter,f.reason,f.reference_type referenceType,f.reference_id referenceId,f.created_at createdAt";return Map.of("items",db.queryForList("SELECT "+cols+sql+where+" ORDER BY f.id DESC LIMIT ? OFFSET ?",a.toArray()),"total",Objects.requireNonNullElse(total,0L),"page",p,"pageSize",s);}
    private long flow(long sku,String type,BigDecimal delta,Stock before,Stock after,String ref,Long refId,String reason,long operator){db.update("INSERT INTO inventory_flows(sku_id,flow_type,quantity_delta,available_before,available_after,pending_inbound_before,pending_inbound_after,reference_type,reference_id,reason,operator_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",sku,type,delta,before.available(),after.available(),before.pendingInbound(),after.pendingInbound(),ref,refId,reason,operator);return lastId();}
    private List<String> consumeJitPackaging(long sku,BigDecimal units,long operator,long inventoryFlowId){List<String>warnings=new ArrayList<>();consumePackagingPlan(sku,"default_product_packaging_plan","PRODUCT_PACKAGING",units,operator,inventoryFlowId,warnings);consumePackagingPlan(sku,"default_shipping_material_plan","SHIPPING_MATERIAL",units,operator,inventoryFlowId,warnings);return warnings;}
    private void consumePackagingPlan(long sku,String field,String category,BigDecimal units,long operator,long inventoryFlowId,List<String>warnings){String plan=db.queryForObject("SELECT "+field+" FROM skus WHERE id=?",String.class,sku);if(plan==null||plan.isBlank())return;try{JsonNode rows=json.readTree(plan);if(!rows.isArray())throw new IllegalArgumentException("SKU 默认包材方案格式无效");for(JsonNode row:rows){String code=row.path("materialCode").asText();BigDecimal perUnit=row.hasNonNull("quantity")?row.get("quantity").decimalValue():BigDecimal.ONE;BigDecimal quantity=perUnit.multiply(units);Long material=db.query("SELECT id FROM package_materials WHERE material_code=? AND category=? AND status='ACTIVE' AND is_deleted=FALSE",rs->rs.next()?rs.getLong(1):null,code,category);if(material==null)throw new IllegalArgumentException("SKU 包材方案引用了不可用包材："+code);db.update("INSERT IGNORE INTO package_stocks(material_id) VALUES (?)",material);BigDecimal before=db.queryForObject("SELECT available_qty FROM package_stocks WHERE material_id=? FOR UPDATE",BigDecimal.class,material);BigDecimal after=before.subtract(quantity);db.update("UPDATE package_stocks SET available_qty=? WHERE material_id=?",after,material);db.update("INSERT INTO package_flows(material_id,material_category,flow_type,quantity_delta,available_before,available_after,reference_type,reference_id,reason,operator_id) VALUES (?,?, 'JIT_CONSUMPTION',?,?,?,?,?,'JIT 商品出库包材消耗',?)",material,category,quantity.negate(),before,after,"INVENTORY_FLOW",inventoryFlowId,operator);if(after.signum()<0){String warning="包材库存不足："+code+"（缺口 "+after.abs()+"）";warnings.add(warning);db.update("INSERT INTO exceptions(exception_no,exception_type,status,title,resolution_note,created_at) VALUES (?,'PACKAGE_SHORTAGE','OPEN',?,?,CURRENT_TIMESTAMP)",nextCode("EXCEPTION","EX"),warning,"JIT 出库流水 #"+inventoryFlowId+"；包材分类："+category);}}}catch(java.io.IOException e){throw new IllegalArgumentException("SKU 默认包材方案格式无效");}}
    private Stock lockStock(long sku){db.update("INSERT IGNORE INTO inventory_stocks(sku_id) VALUES (?)",sku);return db.query("SELECT available_qty,pending_inbound_qty FROM inventory_stocks WHERE sku_id=? FOR UPDATE",rs->rs.next()?new Stock(rs.getBigDecimal(1),rs.getBigDecimal(2)):null,sku);}
    private record Stock(BigDecimal available,BigDecimal pendingInbound){}
    private Map<String,Object> lockedPurchase(long id){List<Map<String,Object>> rows=db.queryForList("SELECT id,status FROM purchase_orders WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new IllegalArgumentException("采购单不存在");return rows.get(0);}
    private String nextCode(String key,String prefix){LocalDate d=LocalDate.now(ZONE);db.update("INSERT INTO master_code_sequences(sequence_key,sequence_date,sequence_value) VALUES (?,?,1) ON DUPLICATE KEY UPDATE sequence_value=sequence_value+1",key,d);Long n=db.queryForObject("SELECT sequence_value FROM master_code_sequences WHERE sequence_key=? AND sequence_date=? FOR UPDATE",Long.class,key,d);return prefix+d.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format("%06d",n);}
    private long lastId(){return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));}
    private void purchaseView(AuthUser u){requireAny(u,"menu.purchases");} private void inventoryView(AuthUser u){requireAny(u,"menu.inventory");} private void packageView(AuthUser u){requireAny(u,"menu.package-stocks");}
    private void purchaseManage(AuthUser u){purchaseView(u);requireAny(u,"master.data.manage");}private void inventoryManage(AuthUser u){inventoryView(u);requireAny(u,"master.data.manage");}private void packageManage(AuthUser u){packageView(u);requireAny(u,"master.data.manage");}
    private void requireAny(AuthUser u,String permission){if(!auth.hasPermission(u.id(),permission)&&!auth.hasPermission(u.id(),"menu.mobile"))throw new org.springframework.security.access.AccessDeniedException("没有执行此操作的权限");}
    private static String operation(Map<String,Object>b){String op=Objects.toString(b.get("operation"),"").toUpperCase(Locale.ROOT);if(!Set.of("INBOUND","OUTBOUND","COUNT","ADJUST").contains(op))throw new IllegalArgumentException("请选择正确的业务操作");return op;}
    private static BigDecimal operationQuantity(Map<String,Object>b,String op){try{BigDecimal value=new BigDecimal(Objects.toString(b.get("quantity")));if((op.equals("INBOUND")||op.equals("OUTBOUND"))&&value.signum()<=0)throw new Exception();if(op.equals("COUNT")&&value.signum()<0)throw new Exception();if(op.equals("ADJUST")&&value.signum()==0)throw new Exception();return value;}catch(Exception e){throw new IllegalArgumentException("数量格式不正确或不符合当前操作要求");}}
    private static long number(Map<String,Object>b,String key){try{return Long.parseLong(Objects.toString(b.get(key)));}catch(Exception e){throw new IllegalArgumentException(key+" 必填");}}
    private static BigDecimal positive(Map<String,Object>b,String key){BigDecimal x=nonNegative(b,key);if(x.signum()<=0)throw new IllegalArgumentException(key+" 必须大于 0");return x;}private static BigDecimal nonNegative(Map<String,Object>b,String key){try{BigDecimal x=new BigDecimal(Objects.toString(b.get(key)));if(x.signum()<0)throw new Exception();return x;}catch(Exception e){throw new IllegalArgumentException(key+" 必须为非负数量");}}private static BigDecimal decimal(Object x){return x==null?BigDecimal.ZERO:new BigDecimal(x.toString());}
    private static String text(Object x){return x==null?null:x.toString().trim();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().filter(Map.class::isInstance).map(x->(Map<String,Object>)x).toList();}
    private void requireExists(String sql,long id,String message){if(db.queryForObject(sql,Integer.class,id)==0)throw new IllegalArgumentException(message);}
    private static <T>T fail(String message){throw new IllegalArgumentException(message);}
}
