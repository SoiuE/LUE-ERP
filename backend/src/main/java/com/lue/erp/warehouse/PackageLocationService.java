package com.lue.erp.warehouse;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/** Position legacy warehouse package stock, then consume at an explicit bin. Caller owns transaction. */
public class PackageLocationService {
    private final JdbcTemplate db;
    public PackageLocationService(JdbcTemplate db){this.db=db;}
    public record Consumption(long documentId,BigDecimal unitCost,BigDecimal amount,BigDecimal shortage){}

    public long position(long warehouse,long material,long location,BigDecimal qty,BigDecimal actualUnitCost,String source,String key,long operator){
        if(qty.signum()<=0||actualUnitCost.signum()<0||source==null||source.isBlank())throw new IllegalArgumentException("包材定位需填写正数量、已确认成本及来源");
        validLocation(warehouse,location);
        Map<String,Object> existing=db.query("SELECT id,warehouse_id warehouseId,material_id materialId,location_id locationId,quantity,unit_cost_snapshot unitCost,cost_source costSource FROM package_location_documents WHERE document_type='POSITION' AND idempotency_key=?",r->r.next()?Map.of("id",r.getLong(1),"warehouseId",r.getLong(2),"materialId",r.getLong(3),"locationId",r.getLong(4),"quantity",r.getBigDecimal(5),"unitCost",r.getBigDecimal(6),"costSource",r.getString(7)):null,key);
        if(existing!=null){if(((Number)existing.get("warehouseId")).longValue()!=warehouse||((Number)existing.get("materialId")).longValue()!=material||((Number)existing.get("locationId")).longValue()!=location||dec(existing.get("quantity")).compareTo(qty)!=0||dec(existing.get("unitCost")).compareTo(actualUnitCost)!=0||!source.equals(existing.get("costSource")))throw new IllegalArgumentException("幂等键已用于不同的包材定位请求");return ((Number)existing.get("id")).longValue();}
        db.update("INSERT IGNORE INTO package_stocks(material_id,warehouse_id) VALUES (?,?)",material,warehouse);
        BigDecimal warehouseQty=Objects.requireNonNull(db.queryForObject("SELECT available_qty FROM package_stocks WHERE warehouse_id=? AND material_id=? FOR UPDATE",BigDecimal.class,warehouse,material));
        db.update("INSERT IGNORE INTO package_location_costs(warehouse_id,material_id) VALUES (?,?)",warehouse,material);
        Map<String,Object> cost=db.queryForMap("SELECT located_qty,total_cost,average_unit_cost FROM package_location_costs WHERE warehouse_id=? AND material_id=? FOR UPDATE",warehouse,material);
        BigDecimal placed=dec(cost.get("located_qty"));
        BigDecimal sum=Objects.requireNonNull(db.queryForObject("SELECT COALESCE(SUM(available_qty),0) FROM package_location_balances WHERE warehouse_id=? AND material_id=?",BigDecimal.class,warehouse,material));
        if(sum.compareTo(placed)!=0)throw new IllegalArgumentException("包材库位数量与成本账不一致");
        if(warehouseQty.subtract(placed).compareTo(qty)<0)throw new IllegalArgumentException("仓级未定位包材不足，不能重复定位");
        BigDecimal amount=qty.multiply(actualUnitCost).setScale(4,RoundingMode.HALF_UP),nextQty=placed.add(qty),nextCost=dec(cost.get("total_cost")).add(amount),average=nextCost.divide(nextQty,6,RoundingMode.HALF_UP);
        long doc=document("POSITION",key,warehouse,material,location,null,null,qty,actualUnitCost,amount,source,null,operator);
        balance(doc,warehouse,material,location,qty,"POSITION_IN",actualUnitCost,amount,operator);
        db.update("UPDATE package_location_costs SET located_qty=?,total_cost=?,average_unit_cost=? WHERE warehouse_id=? AND material_id=?",nextQty,nextCost,average,warehouse,material);
        return doc;
    }

    public Consumption consume(long warehouse,long material,long location,BigDecimal qty,long dispatch,long item,String flowType,String reason,long operator){
        if(qty.signum()<=0)throw new IllegalArgumentException("包材实耗量必须大于零");validLocation(warehouse,location);
        String category=db.query("SELECT category FROM package_materials WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",r->r.next()?r.getString(1):null,material);
        if(category==null)throw new IllegalArgumentException("包材不存在或已停用");
        if("STOCKING_CONSUMPTION".equals(flowType)&&!"PRODUCT_PACKAGING".equals(category))throw new IllegalArgumentException("平台仓备货仅消耗产品包装");
        db.update("INSERT IGNORE INTO package_stocks(material_id,warehouse_id) VALUES (?,?)",material,warehouse);
        BigDecimal total=Objects.requireNonNull(db.queryForObject("SELECT available_qty FROM package_stocks WHERE warehouse_id=? AND material_id=? FOR UPDATE",BigDecimal.class,warehouse,material));
        Map<String,Object> cost=db.query("SELECT located_qty,total_cost,average_unit_cost FROM package_location_costs WHERE warehouse_id=? AND material_id=? FOR UPDATE",r->r.next()?Map.of("located_qty",r.getBigDecimal(1),"total_cost",r.getBigDecimal(2),"average_unit_cost",r.getBigDecimal(3)):null,warehouse,material);
        if(cost==null)throw new IllegalArgumentException("包材缺少库位及已确认成本来源，请先定位包材");
        BigDecimal placed=dec(cost.get("located_qty")),sum=Objects.requireNonNull(db.queryForObject("SELECT COALESCE(SUM(available_qty),0) FROM package_location_balances WHERE warehouse_id=? AND material_id=?",BigDecimal.class,warehouse,material));
        if(sum.compareTo(placed)!=0)throw new IllegalArgumentException("包材库位数量与成本账不一致");
        db.update("INSERT IGNORE INTO package_location_balances(warehouse_id,material_id,location_id) VALUES (?,?,?)",warehouse,material,location);
        BigDecimal bin=Objects.requireNonNull(db.queryForObject("SELECT available_qty FROM package_location_balances WHERE warehouse_id=? AND material_id=? AND location_id=? FOR UPDATE",BigDecimal.class,warehouse,material,location));
        BigDecimal shortage=qty.subtract(bin.max(BigDecimal.ZERO)).max(BigDecimal.ZERO),unit=dec(cost.get("average_unit_cost"));
        BigDecimal amount=placed.compareTo(qty)==0&&placed.signum()>0?dec(cost.get("total_cost")):qty.multiply(unit).setScale(4,RoundingMode.HALF_UP);
        long doc=document("FULFILLMENT_CONSUMPTION",null,warehouse,material,location,dispatch,item,qty,unit,amount,"CONFIRMED_POSITION",reason,operator);
        balance(doc,warehouse,material,location,qty.negate(),"FULFILLMENT_OUT",unit,amount.negate(),operator);
        BigDecimal after=total.subtract(qty);
        db.update("UPDATE package_stocks SET available_qty=? WHERE warehouse_id=? AND material_id=?",after,warehouse,material);
        db.update("INSERT INTO package_flows(material_id,warehouse_id,material_category,flow_type,quantity_delta,available_before,available_after,reference_type,reference_id,reason,operator_id) VALUES (?,?,?,?,?,?,?,'FULFILLMENT_DISPATCH',?,?,?)",material,warehouse,category,flowType,qty.negate(),total,after,dispatch,reason,operator);
        BigDecimal nextQty=placed.subtract(qty),nextCost=dec(cost.get("total_cost")).subtract(amount),nextAverage=nextQty.signum()==0?unit:nextCost.divide(nextQty,6,RoundingMode.HALF_UP);
        db.update("UPDATE package_location_costs SET located_qty=?,total_cost=?,average_unit_cost=? WHERE warehouse_id=? AND material_id=?",nextQty,nextCost,nextAverage,warehouse,material);
        return new Consumption(doc,unit,amount,shortage);
    }

    private void validLocation(long warehouse,long location){String type=db.query("SELECT location_type FROM warehouse_locations WHERE warehouse_id=? AND id=? AND status='ACTIVE'",r->r.next()?r.getString(1):null,warehouse,location);if(type==null||!java.util.Set.of("STORAGE","PRIMARY_PICK","TEMPORARY").contains(type))throw new IllegalArgumentException("包材必须位于启用的正式库位");}
    private long document(String type,String key,long warehouse,long material,long location,Long dispatch,Long item,BigDecimal qty,BigDecimal unit,BigDecimal amount,String source,String reason,long operator){db.update("INSERT INTO package_location_documents(document_type,idempotency_key,warehouse_id,material_id,location_id,fulfillment_dispatch_id,fulfillment_document_item_id,quantity,unit_cost_snapshot,cost_amount_snapshot,cost_source,reason,operator_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",type,key,warehouse,material,location,dispatch,item,qty,unit,amount,source,reason,operator);return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));}
    private void balance(long doc,long warehouse,long material,long location,BigDecimal delta,String type,BigDecimal unit,BigDecimal amount,long operator){db.update("INSERT IGNORE INTO package_location_balances(warehouse_id,material_id,location_id) VALUES (?,?,?)",warehouse,material,location);BigDecimal before=Objects.requireNonNull(db.queryForObject("SELECT available_qty FROM package_location_balances WHERE warehouse_id=? AND material_id=? AND location_id=? FOR UPDATE",BigDecimal.class,warehouse,material,location));BigDecimal after=before.add(delta);db.update("UPDATE package_location_balances SET available_qty=? WHERE warehouse_id=? AND material_id=? AND location_id=?",after,warehouse,material,location);db.update("INSERT INTO package_location_flows(document_id,warehouse_id,material_id,location_id,flow_type,quantity_delta,quantity_before,quantity_after,unit_cost_snapshot,cost_amount_snapshot,operator_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",doc,warehouse,material,location,type,delta,before,after,unit,amount,operator);}
    private static BigDecimal dec(Object x){return new BigDecimal(x.toString());}
}
