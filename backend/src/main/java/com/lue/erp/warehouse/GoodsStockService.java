package com.lue.erp.warehouse;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/** One inventory writer for manual and fulfillment goods outbounds. Caller owns the transaction. */
public class GoodsStockService {
    private final JdbcTemplate db;
    public GoodsStockService(JdbcTemplate db){this.db=db;}
    public record Outbound(long documentId,BigDecimal unitCost,BigDecimal amount,BigDecimal shortage){}

    public Outbound outbound(long warehouse,long goods,long location,BigDecimal qty,long operator,
            String reason,Long dispatchId,Long documentItemId,boolean allowShortage){
        if(qty.signum()<=0||qty.scale()>4)throw new IllegalArgumentException("出库数量必须是最多四位小数的正数");
        String locationType=db.query("SELECT location_type FROM warehouse_locations WHERE warehouse_id=? AND id=? AND status='ACTIVE'",r->r.next()?r.getString(1):null,warehouse,location);
        if(locationType==null||!java.util.Set.of("STORAGE","PRIMARY_PICK","TEMPORARY").contains(locationType))throw new IllegalArgumentException("来源必须是启用的正式库位");
        db.update("INSERT IGNORE INTO warehouse_goods_costs(warehouse_id,warehouse_goods_id) VALUES (?,?)",warehouse,goods);
        Map<String,Object> cost=db.queryForMap("SELECT quantity,total_cost,average_unit_cost,cost_status FROM warehouse_goods_costs WHERE warehouse_id=? AND warehouse_goods_id=? FOR UPDATE",warehouse,goods);
        if(!"ACTUAL".equals(cost.get("cost_status")))throw new IllegalArgumentException("货品历史成本未确认");
        BigDecimal beforeQty=dec(cost.get("quantity")),beforeCost=dec(cost.get("total_cost")),unit=dec(cost.get("average_unit_cost"));
        BigDecimal balanceQty=Objects.requireNonNull(db.queryForObject("SELECT COALESCE(SUM(available_qty+locked_qty+pending_putaway_qty+abnormal_qty),0) FROM warehouse_goods_location_balances WHERE warehouse_id=? AND warehouse_goods_id=?",BigDecimal.class,warehouse,goods));
        if(balanceQty.compareTo(beforeQty)!=0)throw new IllegalArgumentException("货品库位数量与成本账不一致");
        Integer costLayers=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_cost_layers WHERE warehouse_id=? AND warehouse_goods_id=?",Integer.class,warehouse,goods);
        if(costLayers==null||costLayers==0)throw new IllegalArgumentException("货品缺少已确认实际成本来源");
        db.update("INSERT IGNORE INTO warehouse_goods_location_balances(warehouse_id,warehouse_goods_id,location_id,position_status) VALUES (?,?,?,'LOCATED')",warehouse,goods,location);
        Map<String,Object> bin=db.queryForMap("SELECT available_qty,pending_putaway_qty FROM warehouse_goods_location_balances WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=? FOR UPDATE",warehouse,goods,location);
        BigDecimal available=dec(bin.get("available_qty"));BigDecimal shortage=qty.subtract(available.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
        if(!allowShortage&&(shortage.signum()>0||qty.compareTo(beforeQty)>0))throw new IllegalArgumentException("来源库位或仓库货品数量不足");
        BigDecimal nextQty=beforeQty.subtract(qty);
        BigDecimal amount=nextQty.signum()==0&&beforeQty.signum()>0?beforeCost:qty.multiply(unit).setScale(4,RoundingMode.HALF_UP);
        BigDecimal nextCost=beforeCost.subtract(amount);
        BigDecimal nextAverage=nextQty.signum()==0?unit:nextCost.divide(nextQty,6,RoundingMode.HALF_UP);
        db.update("INSERT INTO warehouse_goods_stock_documents(document_type,warehouse_id,warehouse_goods_id,source_location_id,quantity,unit_cost_snapshot,cost_amount_snapshot,reason,operator_id,fulfillment_dispatch_id,fulfillment_document_item_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",dispatchId==null?"OUTBOUND":"FULFILLMENT_OUTBOUND",warehouse,goods,location,qty,unit,amount,reason,operator,dispatchId,documentItemId);
        long doc=Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
        BigDecimal after=available.subtract(qty);
        db.update("UPDATE warehouse_goods_location_balances SET available_qty=? WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=?",after,warehouse,goods,location);
        db.update("INSERT INTO warehouse_goods_stock_flows(document_id,warehouse_id,warehouse_goods_id,location_id,flow_type,available_delta,pending_putaway_delta,available_before,available_after,pending_before,pending_after,unit_cost_snapshot,cost_amount_snapshot,operator_id) VALUES (?,?,?,?,?,?,0,?,?,?,?,?,?,?)",doc,warehouse,goods,location,dispatchId==null?"OUTBOUND":"FULFILLMENT_OUTBOUND",qty.negate(),available,after,bin.get("pending_putaway_qty"),bin.get("pending_putaway_qty"),unit,amount.negate(),operator);
        db.update("UPDATE warehouse_goods_costs SET quantity=?,total_cost=?,average_unit_cost=? WHERE warehouse_id=? AND warehouse_goods_id=?",nextQty,nextCost,nextAverage,warehouse,goods);
        return new Outbound(doc,unit,amount,shortage);
    }
    private static BigDecimal dec(Object value){return new BigDecimal(value.toString());}
}
