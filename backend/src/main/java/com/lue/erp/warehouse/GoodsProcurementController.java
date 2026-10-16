package com.lue.erp.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/goods-procurement")
public class GoodsProcurementController {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;

    public GoodsProcurementController(JdbcTemplate db, AuthService auth, ObjectMapper json) {
        this.db=db; this.auth=auth; this.json=json;
    }

    @GetMapping("/lookups")
    public ApiResponse<Map<String,Object>> lookups(@AuthenticationPrincipal AuthUser user) {
        view(user);
        return ApiResponse.ok(Map.of(
            "suppliers",db.queryForList("SELECT id,supplier_name supplierName FROM suppliers WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY supplier_name"),
            "goods",db.queryForList("SELECT id,goods_code goodsCode,goods_name goodsName,reference_purchase_price referencePrice FROM warehouse_goods WHERE status='ACTIVE' AND is_deleted=FALSE ORDER BY goods_code LIMIT 1000"),
            "warehouses",db.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName FROM warehouses WHERE status='ACTIVE' ORDER BY warehouse_code")));
    }

    @GetMapping("/locations")
    public ApiResponse<Map<String,Object>> locations(@RequestParam long warehouseId,@AuthenticationPrincipal AuthUser user) {
        view(user);
        return ApiResponse.ok(Map.of("items",db.queryForList("SELECT id,location_code locationCode,location_type locationType FROM warehouse_locations WHERE warehouse_id=? AND status='ACTIVE' ORDER BY location_code",warehouseId)));
    }

    @GetMapping("/receipt-locations")
    public ApiResponse<Map<String,Object>> receiptLocations(@RequestParam long warehouseId,@AuthenticationPrincipal AuthUser user) {
        view(user);
        List<Map<String,Object>> locations=db.queryForList("""
            SELECT l.id,l.location_code locationCode,l.location_type locationType,
              (SELECT MIN(a.warehouse_goods_id) FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.status='ACTIVE') assignedGoodsId,
              (SELECT COUNT(*) FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.status='ACTIVE') assignmentCount,
              (SELECT COUNT(*) FROM warehouse_goods_location_balances b WHERE b.warehouse_id=l.warehouse_id AND b.location_id=l.id AND (b.available_qty<>0 OR b.locked_qty<>0 OR b.pending_putaway_qty<>0 OR b.abnormal_qty<>0)) occupiedRows,
              (SELECT COUNT(*) FROM warehouse_location_task_claims t WHERE t.warehouse_id=l.warehouse_id AND t.location_id=l.id AND t.status='OPEN') openTasks
            FROM warehouse_locations l JOIN warehouses w ON w.id=l.warehouse_id
            WHERE l.warehouse_id=? AND w.status='ACTIVE' AND l.status='ACTIVE'
              AND l.location_type IN ('PENDING_PUTAWAY','STORAGE','PRIMARY_PICK','TEMPORARY')
            ORDER BY l.location_code
            """,warehouseId);
        return ApiResponse.ok(Map.of("items",locations));
    }

    @GetMapping("/putaway-locations")
    public ApiResponse<Map<String,Object>> putawayLocations(@RequestParam long warehouseId,@RequestParam long goodsId,@AuthenticationPrincipal AuthUser user) {
        view(user);
        exists("SELECT COUNT(*) FROM warehouses WHERE id=? AND status='ACTIVE'",warehouseId,"仓库不存在或已停用");
        exists("SELECT COUNT(*) FROM warehouse_goods WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",goodsId,"货品不存在或已停用");
        List<Map<String,Object>> locations=db.queryForList("""
            SELECT l.id,l.location_code locationCode,l.location_type locationType,
              (SELECT MIN(a.warehouse_goods_id) FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.status='ACTIVE') assignedGoodsId,
              (SELECT COUNT(*) FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.status='ACTIVE') assignmentCount,
              (SELECT COUNT(*) FROM warehouse_goods_location_balances b WHERE b.warehouse_id=l.warehouse_id AND b.location_id=l.id AND (b.available_qty<>0 OR b.locked_qty<>0 OR b.pending_putaway_qty<>0 OR b.abnormal_qty<>0)) occupiedRows,
              (SELECT COUNT(*) FROM warehouse_location_task_claims t WHERE t.warehouse_id=l.warehouse_id AND t.location_id=l.id AND t.status='OPEN') openTasks,
              EXISTS(SELECT 1 FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.warehouse_goods_id=? AND a.status='ACTIVE') boundToGoods
            FROM warehouse_locations l JOIN warehouses w ON w.id=l.warehouse_id
            WHERE l.warehouse_id=? AND w.status='ACTIVE' AND l.status='ACTIVE'
              AND l.location_type IN ('STORAGE','PRIMARY_PICK','TEMPORARY')
              AND (
                EXISTS(SELECT 1 FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.warehouse_goods_id=? AND a.status='ACTIVE')
                OR (
                  NOT EXISTS(SELECT 1 FROM warehouse_goods_location_assignments a WHERE a.warehouse_id=l.warehouse_id AND a.location_id=l.id AND a.status='ACTIVE')
                  AND NOT EXISTS(SELECT 1 FROM warehouse_goods_location_balances b WHERE b.warehouse_id=l.warehouse_id AND b.location_id=l.id AND (b.available_qty<>0 OR b.locked_qty<>0 OR b.pending_putaway_qty<>0 OR b.abnormal_qty<>0))
                  AND NOT EXISTS(SELECT 1 FROM warehouse_location_task_claims t WHERE t.warehouse_id=l.warehouse_id AND t.location_id=l.id AND t.status='OPEN')
                )
              )
            ORDER BY boundToGoods DESC,l.location_code
            """,goodsId,warehouseId,goodsId);
        return ApiResponse.ok(Map.of("items",locations));
    }

    @GetMapping("/purchases")
    public ApiResponse<Map<String,Object>> purchases(@AuthenticationPrincipal AuthUser user) {
        view(user);
        return ApiResponse.ok(Map.of("items",db.queryForList("SELECT p.id,p.po_no poNo,p.status,p.warehouse_id warehouseId,w.warehouse_name warehouseName,p.tracking_no trackingNo,p.order_no orderNo,p.expected_arrival_date expectedArrivalDate,s.supplier_name supplierName,p.created_at createdAt FROM purchase_orders p JOIN suppliers s ON s.id=p.supplier_id LEFT JOIN warehouses w ON w.id=p.warehouse_id WHERE EXISTS (SELECT 1 FROM purchase_order_items i WHERE i.purchase_order_id=p.id AND i.warehouse_goods_id IS NOT NULL) ORDER BY p.id DESC LIMIT 200")));
    }

    @GetMapping("/purchases/{id}")
    public ApiResponse<Map<String,Object>> purchase(@PathVariable long id,@AuthenticationPrincipal AuthUser user) {
        view(user);
        Map<String,Object> result=db.queryForMap("SELECT p.id,p.po_no poNo,p.status,p.warehouse_id warehouseId,w.warehouse_name warehouseName,p.tracking_no trackingNo,p.order_no orderNo,p.expected_arrival_date expectedArrivalDate,p.note,p.created_at createdAt,s.supplier_name supplierName FROM purchase_orders p JOIN suppliers s ON s.id=p.supplier_id LEFT JOIN warehouses w ON w.id=p.warehouse_id WHERE p.id=?",id);
        List<Map<String,Object>> lines=db.queryForList("SELECT i.id,i.warehouse_goods_id goodsId,g.goods_code goodsCode,g.goods_name goodsName,g.unit,i.ordered_qty orderedQty,i.confirmed_qty confirmedQty,i.received_qty receivedQty,i.unit_price suggestedPrice,i.total_amount totalAmount FROM purchase_order_items i JOIN warehouse_goods g ON g.id=i.warehouse_goods_id WHERE i.purchase_order_id=? ORDER BY i.id",id);
        if(lines.isEmpty())throw new IllegalArgumentException("不是仓库货品采购单");
        result.put("items",lines);return ApiResponse.ok(result);
    }

    @PostMapping("/purchases") @Transactional
    public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        require(user,"warehouse.goods.purchase");
        Map<String,Object> prior=begin("CREATE",body,user);if(prior!=null)return ApiResponse.ok(prior);
        long supplier=id(body,"supplierId"),warehouse=id(body,"warehouseId");exists("SELECT COUNT(*) FROM suppliers WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",supplier,"供应商已停用或不存在");exists("SELECT COUNT(*) FROM warehouses WHERE id=? AND status='ACTIVE'",warehouse,"仓库不存在或已停用");
        List<Map<String,Object>> lines=lines(body,"items");if(lines.isEmpty())throw new IllegalArgumentException("请添加采购货品");
        String no=nextCode("PURCHASE_ORDER","PO");
        db.update("INSERT INTO purchase_orders(po_no,supplier_id,warehouse_id,tracking_no,order_no,expected_arrival_date,note,created_by) VALUES (?,?,?,?,?,?,?,?)",no,supplier,warehouse,body.get("trackingNo"),body.get("orderNo"),blank(body.get("expectedArrivalDate"))?null:body.get("expectedArrivalDate"),body.get("note"),user.id());
        long purchaseId=lastId();Set<Long> seen=new HashSet<>();
        for(Map<String,Object> line:lines){long goods=id(line,"goodsId");if(!seen.add(goods))throw new IllegalArgumentException("同一货品请合并为一行");exists("SELECT COUNT(*) FROM warehouse_goods WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",goods,"货品不存在或已停用");BigDecimal qty=qty(line,"quantity",true),price=qty(line,"suggestedPrice",false);db.update("INSERT INTO purchase_order_items(purchase_order_id,warehouse_goods_id,ordered_qty,unit_price,total_amount,note) VALUES (?,?,?,?,?,?)",purchaseId,goods,qty,price,qty.multiply(price).setScale(4,RoundingMode.HALF_UP),line.get("note"));}
        Map<String,Object> result=Map.of("id",purchaseId,"poNo",no);finish("CREATE",body,purchaseId,result);return ApiResponse.ok(result);
    }

    @PostMapping("/purchases/{id}/confirm") @Transactional
    public ApiResponse<Map<String,Object>> confirm(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        require(user,"warehouse.goods.purchase");Map<String,Object> request=new HashMap<>(body);request.put("_purchaseId",id);Map<String,Object> prior=begin("CONFIRM",request,user);if(prior!=null)return ApiResponse.ok(prior);
        String status=orderStatus(id);if(!Set.of("DRAFT","CONFIRMED","PARTIALLY_RECEIVED").contains(status))throw new IllegalArgumentException("当前采购单不可确认");
        List<Map<String,Object>> rows=lines(body,"items");if(rows.isEmpty())throw new IllegalArgumentException("请填写确认数量");
        for(Map<String,Object> row:rows){long item=id(row,"itemId");Map<String,Object> old=item(id,item);BigDecimal confirmed=qty(row,"confirmedQty",false);if(confirmed.compareTo(dec(old.get("receivedQty")))<0)throw new IllegalArgumentException("确认量不能小于已收量");if(confirmed.compareTo(dec(old.get("orderedQty")))>0&&blank(row.get("reason")))throw new IllegalArgumentException("超量确认必须填写原因");db.update("UPDATE purchase_order_items SET confirmed_qty=? WHERE id=?",confirmed,item);}
        db.update("UPDATE purchase_orders SET status=?,confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP WHERE id=?","PARTIALLY_RECEIVED".equals(status)?status:"CONFIRMED",user.id(),id);
        Map<String,Object> result=Map.of("confirmed",true);finish("CONFIRM",request,id,result);return ApiResponse.ok(result);
    }

    @PostMapping("/purchases/{id}/receipts") @Transactional
    public ApiResponse<Map<String,Object>> receive(@PathVariable long id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        require(user,"warehouse.goods.receive");Map<String,Object> request=new HashMap<>(body);request.put("_purchaseId",id);Map<String,Object> prior=begin("RECEIVE",request,user);if(prior!=null)return ApiResponse.ok(prior);
        String status=orderStatus(id);if(!Set.of("CONFIRMED","PARTIALLY_RECEIVED").contains(status))throw new IllegalArgumentException("请先确认采购单");
        List<Map<String,Object>> lines=body.get("items") instanceof List<?>?lines(body,"items"):List.of(body);if(lines.isEmpty())throw new IllegalArgumentException("请选择待收货采购明细");
        if(Boolean.TRUE.equals(body.get("receiveAll"))){
            List<Map<String,Object>> pending=db.queryForList("SELECT id,confirmed_qty-received_qty remaining FROM purchase_order_items WHERE purchase_order_id=? AND warehouse_goods_id IS NOT NULL AND confirmed_qty>received_qty FOR UPDATE",id);
            Set<Long> ids=new HashSet<>();for(Map<String,Object> row:lines)ids.add(id(row,"itemId"));
            if(ids.size()!=pending.size()||ids.size()!=lines.size())throw new IllegalArgumentException("采购单待收明细已变化，请刷新后重新确认全部收货");
            for(Map<String,Object> row:pending){long lineId=((Number)row.get("id")).longValue();Map<String,Object> selected=lines.stream().filter(x->id(x,"itemId")==lineId).findFirst().orElseThrow(()->new IllegalArgumentException("全部收货必须包含所有待收货明细"));if(qty(selected,"quantity",true).compareTo(dec(row.get("remaining")))!=0)throw new IllegalArgumentException("采购单待收数量已变化，请刷新后重新确认全部收货");}
        }
        Set<Long> seen=new HashSet<>();List<Map<String,Object>> received=new ArrayList<>();List<String> receiptNos=new ArrayList<>();
        for(Map<String,Object> line:lines){long itemId=id(line,"itemId");if(!seen.add(itemId))throw new IllegalArgumentException("同一采购明细不能重复提交");Map<String,Object> result=receiveLine(id,itemId,body,line,user);received.add(result);receiptNos.add(String.valueOf(result.get("receiptNo")));}
        Integer remaining=db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id=? AND received_qty<confirmed_qty",Integer.class,id);
        String next=remaining!=null&&remaining==0?"RECEIVED":"PARTIALLY_RECEIVED";db.update("UPDATE purchase_orders SET status=? WHERE id=?",next,id);
        Map<String,Object> result=Map.of("receiptNos",receiptNos,"items",received,"status",next);
        receivingAudit(user,"purchase.receipt.confirm","purchase_order",id,Map.of("request",request,"result",result));
        finish("RECEIVE",request,id,result);return ApiResponse.ok(result);
    }

    private Map<String,Object> receiveLine(long orderId,long itemId,Map<String,Object> common,Map<String,Object> line,AuthUser user) {
        long warehouse=line.containsKey("warehouseId")?id(line,"warehouseId"):id(common,"warehouseId"),location=id(line,"locationId");BigDecimal amount=qty(line,"quantity",true),price=qty(line,"actualUnitPrice",false);
        Map<String,Object> item=item(orderId,itemId);long goods=((Number)item.get("goodsId")).longValue();Long purchaseWarehouse=db.queryForObject("SELECT warehouse_id FROM purchase_orders WHERE id=?",Long.class,orderId);if(purchaseWarehouse==null||purchaseWarehouse!=warehouse)throw new IllegalArgumentException("收货仓库必须与采购单仓库一致");
        if(dec(item.get("receivedQty")).add(amount).compareTo(dec(item.get("confirmedQty")))>0&&blank(line.get("overReceiptReason")))throw new IllegalArgumentException("超过确认量的收货必须填写原因");
        String note=String.valueOf(line.getOrDefault("note",""));if(note.length()>1000)throw new IllegalArgumentException("收货备注最多 1000 字");
        boolean pendingPutaway=checkReceiptLocation(warehouse,location,goods,user,orderId,itemId);lockCost(warehouse,goods);
        Map<String,Object> cost=cost(warehouse,goods);BigDecimal beforeQty=dec(cost.get("quantity")),beforeTotal=dec(cost.get("total_cost"));
        BigDecimal incoming=amount.multiply(price).setScale(4,RoundingMode.HALF_UP),afterQty=beforeQty.add(amount),afterTotal=beforeTotal.add(incoming),beforeAverage=dec(cost.get("average_unit_cost"));
        BigDecimal coveredNegative=BigDecimal.ZERO,variance=BigDecimal.ZERO;
        if(beforeQty.signum()<0){coveredNegative=amount.min(beforeQty.negate());BigDecimal targetTotal=afterQty.signum()>0?afterQty.multiply(price).setScale(4,RoundingMode.HALF_UP):afterQty.multiply(beforeAverage).setScale(4,RoundingMode.HALF_UP);variance=afterTotal.subtract(targetTotal);afterTotal=targetTotal;}
        BigDecimal afterAverage=afterQty.signum()==0?beforeAverage:afterTotal.divide(afterQty,6,RoundingMode.HALF_UP);String no=nextCode("PURCHASE_RECEIPT","PR");
        db.update("INSERT INTO purchase_receipts(receipt_no,purchase_order_id,purchase_order_item_id,warehouse_goods_id,warehouse_id,received_qty,actual_unit_price,over_receipt_reason,receipt_note,received_by) VALUES (?,?,?,?,?,?,?,?,?,?)",no,orderId,itemId,goods,warehouse,amount,price,line.get("overReceiptReason"),note,user.id());long receiptId=lastId();
        if(coveredNegative.signum()>0)db.update("INSERT INTO warehouse_goods_negative_cost_variances(purchase_receipt_id,warehouse_id,warehouse_goods_id,covered_negative_qty,variance_amount) VALUES (?,?,?,?,?)",receiptId,warehouse,goods,coveredNegative,variance);
        db.update("INSERT INTO warehouse_goods_cost_layers(warehouse_id,warehouse_goods_id,purchase_receipt_id,quantity,actual_unit_price,amount,average_before,average_after) VALUES (?,?,?,?,?,?,?,?)",warehouse,goods,receiptId,amount,price,incoming,beforeAverage,afterAverage);
        db.update("UPDATE warehouse_goods_costs SET quantity=?,total_cost=?,average_unit_cost=? WHERE warehouse_id=? AND warehouse_goods_id=?",afterQty,afterTotal,afterAverage,warehouse,goods);
        long doc=document("RECEIPT",warehouse,goods,receiptId,null,location,amount,price,incoming,user.id());change(doc,warehouse,goods,location,"RECEIPT",pendingPutaway?ZERO:amount,pendingPutaway?amount:ZERO,price,incoming,user.id());
        db.update("UPDATE purchase_order_items SET received_qty=received_qty+? WHERE id=?",amount,itemId);
        db.update("UPDATE fulfillment_shortages SET status='RECEIVED',resolved_at=CURRENT_TIMESTAMP WHERE purchase_order_item_id=? AND status='PURCHASE_LINKED' AND (SELECT received_qty FROM purchase_order_items WHERE id=?)>=COALESCE(required_goods_qty,shortage_qty)",itemId,itemId);
        return Map.of("itemId",itemId,"receiptNo",no,"quantity",amount,"locationId",location,"pendingPutaway",pendingPutaway);
    }

    @PostMapping("/putaway") @Transactional
    public ApiResponse<Map<String,Object>> putaway(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        require(user,"warehouse.goods.putaway");Map<String,Object> prior=begin("PUTAWAY",body,user);if(prior!=null)return ApiResponse.ok(prior);
        long warehouse=id(body,"warehouseId"),goods=id(body,"goodsId"),from=id(body,"sourceLocationId"),to=id(body,"targetLocationId");BigDecimal amount=qty(body,"quantity",true);
        if(from==to)throw new IllegalArgumentException("来源和目标库位不能相同");checkLocation(warehouse,from,true);lockGood(goods);boolean bindingCreated=checkPutawayTarget(warehouse,to,goods,user);
        lockCost(warehouse,goods);Map<String,Object> c=cost(warehouse,goods);BigDecimal unit=dec(c.get("average_unit_cost")),value=amount.multiply(unit).setScale(4,RoundingMode.HALF_UP);
        long doc=document("PUTAWAY",warehouse,goods,null,from,to,amount,unit,value,user.id());
        if(bindingCreated)receivingAudit(user,"warehouse.putaway.location.bind","warehouse_goods_location_assignment",Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_goods_location_assignments WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=? AND status='ACTIVE'",Long.class,warehouse,goods,to)),Map.of("documentId",doc,"warehouseId",warehouse,"goodsId",goods,"locationId",to));
        change(doc,warehouse,goods,from,"PUTAWAY_OUT",ZERO,amount.negate(),unit,value.negate(),user.id());
        change(doc,warehouse,goods,to,"PUTAWAY_IN",amount,ZERO,unit,value,user.id());
        Map<String,Object> result=Map.of("documentId",doc,"bindingCreated",bindingCreated,"targetLocationId",to);finish("PUTAWAY",body,doc,result);return ApiResponse.ok(result);
    }

    @PostMapping("/moves") @Transactional
    public ApiResponse<Map<String,Object>> move(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        require(user,"warehouse.goods.move");Map<String,Object> prior=begin("MOVE",body,user);if(prior!=null)return ApiResponse.ok(prior);
        long warehouse=id(body,"warehouseId"),goods=id(body,"goodsId"),from=id(body,"sourceLocationId"),to=id(body,"targetLocationId");BigDecimal amount=qty(body,"quantity",true);
        if(from==to)throw new IllegalArgumentException("来源和目标库位不能相同");checkLocation(warehouse,from,false);checkLocation(warehouse,to,false);
        lockCost(warehouse,goods);Map<String,Object> c=cost(warehouse,goods);BigDecimal unit=dec(c.get("average_unit_cost")),value=amount.multiply(unit).setScale(4,RoundingMode.HALF_UP);
        long doc=document("MOVE",warehouse,goods,null,from,to,amount,unit,value,user.id());
        change(doc,warehouse,goods,from,"MOVE_OUT",amount.negate(),ZERO,unit,value.negate(),user.id());
        change(doc,warehouse,goods,to,"MOVE_IN",amount,ZERO,unit,value,user.id());
        Map<String,Object> result=Map.of("documentId",doc);finish("MOVE",body,doc,result);return ApiResponse.ok(result);
    }

    @PostMapping("/outbound") @Transactional
    public ApiResponse<Map<String,Object>> outbound(@RequestBody Map<String,Object> body,@AuthenticationPrincipal AuthUser user) {
        require(user,"warehouse.goods.outbound");Map<String,Object> prior=begin("OUTBOUND",body,user);if(prior!=null)return ApiResponse.ok(prior);
        long warehouse=id(body,"warehouseId"),goods=id(body,"goodsId"),from=id(body,"sourceLocationId");BigDecimal amount=qty(body,"quantity",true);
        String reason=String.valueOf(body.getOrDefault("reason","")).trim();if(reason.isEmpty()||reason.length()>500)throw new IllegalArgumentException("出库原因必填，最多 500 字符");
        GoodsStockService.Outbound written=new GoodsStockService(db).outbound(warehouse,goods,from,amount,user.id(),reason,null,null,false);
        Map<String,Object> result=Map.of("documentId",written.documentId(),"unitCostSnapshot",written.unitCost(),"costAmountSnapshot",written.amount());finish("OUTBOUND",body,written.documentId(),result);return ApiResponse.ok(result);
    }

    @GetMapping("/stock")
    public ApiResponse<Map<String,Object>> stock(@RequestParam(required=false) Long warehouseId,@RequestParam(required=false) Long goodsId,@AuthenticationPrincipal AuthUser user) {
        view(user);String where=" WHERE 1=1";List<Object> args=new ArrayList<>();if(warehouseId!=null){where+=" AND b.warehouse_id=?";args.add(warehouseId);}if(goodsId!=null){where+=" AND b.warehouse_goods_id=?";args.add(goodsId);}
        return ApiResponse.ok(Map.of("items",db.queryForList("SELECT b.warehouse_id warehouseId,w.warehouse_code warehouseCode,b.warehouse_goods_id goodsId,g.goods_code goodsCode,g.goods_name goodsName,b.location_id locationId,l.location_code locationCode,l.location_type locationType,b.available_qty physicalQty,CASE WHEN l.status='ACTIVE' AND l.location_type IN ('STORAGE','PRIMARY_PICK','TEMPORARY') THEN b.available_qty ELSE 0 END availableQty,b.pending_putaway_qty pendingPutawayQty,c.average_unit_cost averageUnitCost,c.total_cost warehouseTotalCost FROM warehouse_goods_location_balances b JOIN warehouses w ON w.id=b.warehouse_id JOIN warehouse_goods g ON g.id=b.warehouse_goods_id LEFT JOIN warehouse_locations l ON l.id=b.location_id LEFT JOIN warehouse_goods_costs c ON c.warehouse_id=b.warehouse_id AND c.warehouse_goods_id=b.warehouse_goods_id"+where+" ORDER BY b.id DESC LIMIT 500",args.toArray())));
    }

    @GetMapping("/flows")
    public ApiResponse<Map<String,Object>> flows(@RequestParam(required=false) Long warehouseId,@RequestParam(required=false) Long goodsId,@AuthenticationPrincipal AuthUser user) {
        view(user);String where=" WHERE 1=1";List<Object> args=new ArrayList<>();if(warehouseId!=null){where+=" AND f.warehouse_id=?";args.add(warehouseId);}if(goodsId!=null){where+=" AND f.warehouse_goods_id=?";args.add(goodsId);}
        return ApiResponse.ok(Map.of("items",db.queryForList("SELECT f.id,f.document_id documentId,d.document_type documentType,f.flow_type flowType,f.warehouse_id warehouseId,f.warehouse_goods_id goodsId,g.goods_code goodsCode,f.location_id locationId,l.location_code locationCode,f.available_delta availableDelta,f.pending_putaway_delta pendingPutawayDelta,f.available_before availableBefore,f.available_after availableAfter,f.pending_before pendingBefore,f.pending_after pendingAfter,f.unit_cost_snapshot unitCostSnapshot,f.cost_amount_snapshot costAmountSnapshot,f.operator_id operatorId,f.created_at createdAt FROM warehouse_goods_stock_flows f JOIN warehouse_goods_stock_documents d ON d.id=f.document_id JOIN warehouse_goods g ON g.id=f.warehouse_goods_id JOIN warehouse_locations l ON l.id=f.location_id"+where+" ORDER BY f.id DESC LIMIT 500",args.toArray())));
    }

    private void change(long doc,long warehouse,long goods,long location,String type,BigDecimal availableDelta,BigDecimal pendingDelta,BigDecimal unit,BigDecimal value,long operator) {
        db.update("INSERT IGNORE INTO warehouse_goods_location_balances(warehouse_id,warehouse_goods_id,location_id,position_status) VALUES (?,?,?,'LOCATED')",warehouse,goods,location);
        Map<String,Object> before=db.queryForMap("SELECT available_qty,pending_putaway_qty FROM warehouse_goods_location_balances WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=? FOR UPDATE",warehouse,goods,location);
        BigDecimal available=dec(before.get("available_qty")),pending=dec(before.get("pending_putaway_qty"));BigDecimal nextAvailable=available.add(availableDelta),nextPending=pending.add(pendingDelta);
        if(nextAvailable.signum()<0||nextPending.signum()<0)throw new IllegalArgumentException("来源库位数量不足；货品库位库存不允许负数");
        db.update("UPDATE warehouse_goods_location_balances SET available_qty=?,pending_putaway_qty=? WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=?",nextAvailable,nextPending,warehouse,goods,location);
        db.update("INSERT INTO warehouse_goods_stock_flows(document_id,warehouse_id,warehouse_goods_id,location_id,flow_type,available_delta,pending_putaway_delta,available_before,available_after,pending_before,pending_after,unit_cost_snapshot,cost_amount_snapshot,operator_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",doc,warehouse,goods,location,type,availableDelta,pendingDelta,available,nextAvailable,pending,nextPending,unit,value,operator);
    }

    private void lockGood(long goods){
        List<Long> rows=db.query("SELECT id FROM warehouse_goods WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE FOR UPDATE",(rs,n)->rs.getLong(1),goods);
        if(rows.isEmpty())throw new IllegalArgumentException("货品不存在或已停用");
    }

    /** A newly selected empty putaway bin becomes a normal, non-primary binding in this transaction. */
    private boolean checkPutawayTarget(long warehouse,long location,long goods,AuthUser user){
        List<String> types=db.query("SELECT l.location_type FROM warehouse_locations l JOIN warehouses w ON w.id=l.warehouse_id WHERE l.warehouse_id=? AND l.id=? AND l.status='ACTIVE' AND w.status='ACTIVE' FOR UPDATE",(rs,n)->rs.getString(1),warehouse,location);
        if(types.isEmpty())throw new IllegalArgumentException("目标库位不存在、已停用或不属于当前启用仓库");
        if(!Set.of("STORAGE","PRIMARY_PICK","TEMPORARY").contains(types.get(0)))throw new IllegalArgumentException("上架目标必须是正式存储、主拣货或暂存位");

        List<Long> assigned=db.query("SELECT warehouse_goods_id FROM warehouse_goods_location_assignments WHERE warehouse_id=? AND location_id=? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getLong(1),warehouse,location);
        if(assigned.size()>1)throw new IllegalArgumentException("目标库位存在多个有效货品绑定，请先整理货品位点绑定");
        if(!assigned.isEmpty()){
            if(assigned.get(0)!=goods)throw new IllegalArgumentException("目标库位已绑定其他货品，请选择本货品已绑定位或空闲位");
            return false;
        }

        Long occupied=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances WHERE warehouse_id=? AND location_id=? AND (available_qty<>0 OR locked_qty<>0 OR pending_putaway_qty<>0 OR abnormal_qty<>0)",Long.class,warehouse,location);
        if(occupied!=null&&occupied>0)throw new IllegalArgumentException("空闲目标库位已有库存，请选择其他库位");
        Long tasks=db.queryForObject("SELECT COUNT(*) FROM warehouse_location_task_claims WHERE warehouse_id=? AND location_id=? AND status='OPEN'",Long.class,warehouse,location);
        if(tasks!=null&&tasks>0)throw new IllegalArgumentException("目标库位有未完成占用任务，请选择其他库位");

        int reactivated=db.update("UPDATE warehouse_goods_location_assignments SET status='ACTIVE',is_primary_pick=FALSE WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=? AND status='INACTIVE'",warehouse,goods,location);
        if(reactivated==0)db.update("INSERT INTO warehouse_goods_location_assignments(warehouse_id,warehouse_goods_id,location_id,is_primary_pick,created_by) VALUES (?,?,?,FALSE,?)",warehouse,goods,location,user.id());
        return true;
    }

    private long document(String type,long warehouse,long goods,Long receipt,Long from,Long to,BigDecimal amount,BigDecimal unit,BigDecimal value,long operator){db.update("INSERT INTO warehouse_goods_stock_documents(document_type,warehouse_id,warehouse_goods_id,purchase_receipt_id,source_location_id,target_location_id,quantity,unit_cost_snapshot,cost_amount_snapshot,operator_id) VALUES (?,?,?,?,?,?,?,?,?,?)",type,warehouse,goods,receipt,from,to,amount,unit,value,operator);return lastId();}
    private void lockCost(long warehouse,long goods){db.update("INSERT IGNORE INTO warehouse_goods_costs(warehouse_id,warehouse_goods_id) VALUES (?,?)",warehouse,goods);Map<String,Object> c=cost(warehouse,goods);if(!"ACTUAL".equals(c.get("cost_status")))throw new IllegalArgumentException("该货品历史成本未确认，请先核定期初成本");BigDecimal located=db.queryForObject("SELECT COALESCE(SUM(available_qty+locked_qty+pending_putaway_qty+abnormal_qty),0) FROM warehouse_goods_location_balances WHERE warehouse_id=? AND warehouse_goods_id=?",BigDecimal.class,warehouse,goods);if(dec(located).compareTo(dec(c.get("quantity")))!=0)throw new IllegalArgumentException("货品库位数量与成本账不一致；请先核定历史库存成本");}
    private Map<String,Object> cost(long warehouse,long goods){return db.queryForMap("SELECT quantity,total_cost,average_unit_cost,cost_status FROM warehouse_goods_costs WHERE warehouse_id=? AND warehouse_goods_id=? FOR UPDATE",warehouse,goods);}
    private void checkLocation(long warehouse,long location,boolean pending){String type=db.query("SELECT location_type FROM warehouse_locations WHERE warehouse_id=? AND id=? AND status='ACTIVE'",r->r.next()?r.getString(1):null,warehouse,location);if(type==null)throw new IllegalArgumentException("目标仓库或库位不存在/已停用");if(pending?!"PENDING_PUTAWAY".equals(type):Set.of("PENDING_PUTAWAY","QUARANTINE").contains(type))throw new IllegalArgumentException(pending?"收货/上架来源必须是待上架位":"目标或移库来源必须是正式存储/拣货/暂存位");}
    private boolean checkReceiptLocation(long warehouse,long location,long goods,AuthUser user,long order,long itemId){
        String type=db.query("SELECT l.location_type FROM warehouse_locations l JOIN warehouses w ON w.id=l.warehouse_id WHERE l.warehouse_id=? AND l.id=? AND l.status='ACTIVE' AND w.status='ACTIVE' FOR UPDATE",r->r.next()?r.getString(1):null,warehouse,location);
        if(type==null)throw new IllegalArgumentException("收货库位不存在、已停用或不属于采购仓库");
        if("PENDING_PUTAWAY".equals(type))return true;
        if(!Set.of("STORAGE","PRIMARY_PICK","TEMPORARY").contains(type))throw new IllegalArgumentException("收货库位类型不允许收货");
        List<Long> assigned=db.query("SELECT warehouse_goods_id FROM warehouse_goods_location_assignments WHERE warehouse_id=? AND location_id=? AND status='ACTIVE' FOR UPDATE",(r,n)->r.getLong(1),warehouse,location);
        if(assigned.size()>1||(!assigned.isEmpty()&&assigned.get(0)!=goods))throw new IllegalArgumentException("收货库位已绑定其他货品");
        Long occupied=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances WHERE warehouse_id=? AND location_id=? AND (available_qty<>0 OR locked_qty<>0 OR pending_putaway_qty<>0 OR abnormal_qty<>0)"+(assigned.isEmpty()?"":" AND warehouse_goods_id<>?"),Long.class,assigned.isEmpty()?new Object[]{warehouse,location}:new Object[]{warehouse,location,goods});
        if(occupied!=null&&occupied>0)throw new IllegalArgumentException("所选空库位已有库存，或已有其他货品库存");
        if(assigned.isEmpty()){
            Long existingBindings=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments a JOIN warehouse_locations l ON l.id=a.location_id AND l.warehouse_id=a.warehouse_id WHERE a.warehouse_id=? AND a.warehouse_goods_id=? AND a.status='ACTIVE' AND l.status='ACTIVE' AND l.location_type IN ('STORAGE','PRIMARY_PICK','TEMPORARY')",Long.class,warehouse,goods);
            if(existingBindings!=null&&existingBindings>0)throw new IllegalArgumentException("请使用该货品已有的有效绑定库位");
            Long tasks=db.queryForObject("SELECT COUNT(*) FROM warehouse_location_task_claims WHERE warehouse_id=? AND location_id=? AND status='OPEN'",Long.class,warehouse,location);
            if(tasks!=null&&tasks>0)throw new IllegalArgumentException("所选空库位有未完成占用任务");
            int updated=db.update("UPDATE warehouse_goods_location_assignments SET status='ACTIVE',is_primary_pick=FALSE WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=? AND status='INACTIVE'",warehouse,goods,location);
            if(updated==0)db.update("INSERT INTO warehouse_goods_location_assignments(warehouse_id,warehouse_goods_id,location_id,is_primary_pick,created_by) VALUES (?,?,?,FALSE,?)",warehouse,goods,location,user.id());
            Long binding=db.queryForObject("SELECT id FROM warehouse_goods_location_assignments WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=?",Long.class,warehouse,goods,location);
            receivingAudit(user,"purchase.receipt.location.bind","warehouse_goods_location_assignment",Objects.requireNonNull(binding),Map.of("purchaseOrderId",order,"purchaseOrderItemId",itemId,"warehouseId",warehouse,"goodsId",goods,"locationId",location));
        }
        return false;
    }
    private void receivingAudit(AuthUser user,String action,String resource,long id,Map<String,Object> detail){try{db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,success) VALUES (?,?,?,?,?,CAST(? AS JSON),TRUE)",user.id(),user.username(),action,resource,String.valueOf(id),json.writeValueAsString(detail));}catch(Exception e){throw new IllegalStateException("收货操作记录写入失败",e);}}
    private Map<String,Object> item(long order,long item){List<Map<String,Object>> rows=db.queryForList("SELECT warehouse_goods_id goodsId,ordered_qty orderedQty,confirmed_qty confirmedQty,received_qty receivedQty FROM purchase_order_items WHERE purchase_order_id=? AND id=? AND warehouse_goods_id IS NOT NULL FOR UPDATE",order,item);if(rows.isEmpty())throw new IllegalArgumentException("采购明细不存在");return rows.get(0);}
    private String orderStatus(long id){List<String> rows=db.query("SELECT status FROM purchase_orders WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id);if(rows.isEmpty())throw new IllegalArgumentException("采购单不存在");return rows.get(0);}
    private void view(AuthUser user){if(!auth.hasPermission(user.id(),"menu.purchases")&&!auth.hasPermission(user.id(),"warehouse.location_stock.view"))throw new org.springframework.security.access.AccessDeniedException("没有查看货品采购和库存的权限");}
    private void require(AuthUser user,String permission){if(!auth.hasPermission(user.id(),permission))throw new org.springframework.security.access.AccessDeniedException("没有执行此操作的权限");}
    private void exists(String sql,long id,String error){if(db.queryForObject(sql,Integer.class,id)==0)throw new IllegalArgumentException(error);}
    private long lastId(){return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));}
    private String nextCode(String key,String prefix){LocalDate d=LocalDate.now(ZoneId.of("Asia/Shanghai"));db.update("INSERT INTO master_code_sequences(sequence_key,sequence_date,sequence_value) VALUES (?,?,1) ON DUPLICATE KEY UPDATE sequence_value=IF(sequence_date=VALUES(sequence_date),sequence_value+1,1),sequence_date=VALUES(sequence_date)",key,d);long n=Objects.requireNonNull(db.queryForObject("SELECT sequence_value FROM master_code_sequences WHERE sequence_key=? FOR UPDATE",Long.class,key));return prefix+d.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format("%06d",n);}
    private static long id(Map<String,Object> body,String name){try{long v=Long.parseLong(String.valueOf(body.get(name)));if(v<=0)throw new Exception();return v;}catch(Exception e){throw new IllegalArgumentException(name+" 必须选择有效记录");}}
    private static BigDecimal qty(Map<String,Object> body,String name,boolean positive){try{BigDecimal v=new BigDecimal(String.valueOf(body.get(name)));if(v.scale()>4||v.precision()-v.scale()>14||(positive?v.signum()<=0:v.signum()<0))throw new Exception();return v.setScale(4);}catch(Exception e){throw new IllegalArgumentException(name+" 必须为最多四位小数的"+(positive?"正数":"非负数"));}}
    private static BigDecimal dec(Object value){return value==null?ZERO:new BigDecimal(value.toString());}
    private static boolean blank(Object value){return value==null||value.toString().isBlank();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> lines(Map<String,Object> body,String key){if(!(body.get(key) instanceof List<?> list))throw new IllegalArgumentException(key+" 必须是明细数组");return list.stream().map(v->{if(!(v instanceof Map<?,?> m))throw new IllegalArgumentException("明细格式错误");return (Map<String,Object>)m;}).toList();}
    private String hash(Map<String,Object> body){try{Map<String,Object> copy=new TreeMap<>(body);copy.remove("idempotencyKey");byte[] data=MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(copy));return HexFormat.of().formatHex(data);}catch(Exception e){throw new IllegalStateException("无法计算请求摘要",e);}}
    private String key(Map<String,Object> body){String key=String.valueOf(body.getOrDefault("idempotencyKey","")).trim();if(key.isEmpty()||key.length()>100)throw new IllegalArgumentException("幂等键必填且不得超过 100 字符");return key;}
    private Map<String,Object> begin(String type,Map<String,Object> body,AuthUser user){String key=key(body),hash=hash(body);db.update("INSERT INTO goods_purchase_commands(command_key,command_type,request_hash,result_id,result_json,created_by) VALUES (?,?,?,0,'{}',?) ON DUPLICATE KEY UPDATE id=id",key,type,hash,user.id());Map<String,Object> row=db.queryForMap("SELECT request_hash,result_id,result_json FROM goods_purchase_commands WHERE command_key=? AND command_type=? FOR UPDATE",key,type);if(!hash.equals(row.get("request_hash")))throw new IllegalArgumentException("该幂等键已用于不同请求");if(((Number)row.get("result_id")).longValue()==0)return null;try{return json.readValue(row.get("result_json").toString(),Map.class);}catch(Exception e){throw new IllegalStateException("已提交结果读取失败",e);}}
    private void finish(String type,Map<String,Object> body,long resultId,Map<String,Object> result){try{db.update("UPDATE goods_purchase_commands SET result_id=?,result_json=CAST(? AS JSON) WHERE command_type=? AND command_key=?",resultId,json.writeValueAsString(result),type,key(body));}catch(Exception e){throw new IllegalStateException("幂等结果保存失败",e);}}
}
