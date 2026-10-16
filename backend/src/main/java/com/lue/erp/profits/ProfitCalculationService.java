package com.lue.erp.profits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Order-date facts and SKU daily profit are persisted; report queries never aggregate source ledgers. */
@Service
public class ProfitCalculationService {
    private static final Logger log=LoggerFactory.getLogger(ProfitCalculationService.class);
    private static final BigDecimal Z=BigDecimal.ZERO.setScale(4);
    private final JdbcTemplate db;
    private final TransactionTemplate work,logTransaction;
    private final ProfitMatchService matching;
    public ProfitCalculationService(JdbcTemplate db,PlatformTransactionManager manager,ProfitMatchService matching){this.db=db;this.matching=matching;work=new TransactionTemplate(manager);logTransaction=new TransactionTemplate(manager);logTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}

    public Map<String,Object> recalculate(LocalDate from,LocalDate to){return recalculate(from,to,"MANUAL",null,null,null);}
    public Map<String,Object> recalculate(LocalDate from,LocalDate to,String trigger,Long actor,Long shop,Long sku){
        if(from==null||to==null||to.isBefore(from)||to.isAfter(from.plusYears(2)))throw new IllegalArgumentException("利润日期范围无效或超过两年");
        if(!Set.of("MANUAL","SCHEDULED","ORDER_IMPORT","SETTLEMENT_IMPORT","MATCH_REPAIR","OTHER_COST_IMPORT").contains(trigger))throw new IllegalArgumentException("重算触发类型无效");
        Long run=logTransaction.execute(tx->{db.update("INSERT INTO profit_recalculation_runs(trigger_type,date_from,date_to,shop_id,sku_id,requested_by) VALUES (?,?,?,?,?,?)",trigger,from,to,shop,sku,actor);return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);});
        boolean outerTransaction=TransactionSynchronizationManager.isActualTransactionActive();
        try{
            Map<String,Object> result=Objects.requireNonNull(work.execute(tx->calculate(from,to,shop,sku)));
            if(outerTransaction){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){logTransaction.executeWithoutResult(tx->db.update("UPDATE profit_recalculation_runs SET status='SUCCESS',affected_order_rows=?,affected_daily_rows=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",result.get("orders"),result.get("rows"),run));}
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)logTransaction.executeWithoutResult(tx->db.update("UPDATE profit_recalculation_runs SET status='FAILED',failure_reason='外层导入事务已回滚',completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",run));}
            });}else logTransaction.executeWithoutResult(tx->db.update("UPDATE profit_recalculation_runs SET status='SUCCESS',affected_order_rows=?,affected_daily_rows=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",result.get("orders"),result.get("rows"),run));
            Map<String,Object> answer=new LinkedHashMap<>(result);answer.put("runId",run);answer.put("dateFrom",from.toString());answer.put("dateTo",to.toString());return answer;
        }catch(RuntimeException e){String reason=Objects.toString(e.getMessage(),e.getClass().getSimpleName());if(reason.length()>1000)reason=reason.substring(0,1000);String failure=reason;logTransaction.executeWithoutResult(tx->db.update("UPDATE profit_recalculation_runs SET status='FAILED',failure_reason=?,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",failure,run));throw e;}
    }
    @Scheduled(cron="0 15 2 * * *",zone="Asia/Shanghai")
    public void scheduledRecalculate(){LocalDate to=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);try{log.info("Scheduled profit recalculation: {}",recalculate(to.minusDays(13),to,"SCHEDULED",null,null,null));}catch(RuntimeException e){log.error("Scheduled profit recalculation failed",e);}}

    private Map<String,Object> calculate(LocalDate from,LocalDate to,Long shop,Long sku){
        matching.matchFulfillment();
        matching.flagUnlinkedPackageCosts();
        matching.matchPendingSettlements();
        String filter="o.is_deleted=FALSE AND o.sku_id IS NOT NULL AND o.order_date BETWEEN ? AND ?"+(shop==null?"":" AND o.shop_id=?")+(sku==null?"":" AND o.sku_id=?");
        List<Object> args=new ArrayList<>(List.of(from,to));if(shop!=null)args.add(shop);if(sku!=null)args.add(sku);
        List<Map<String,Object>> orders=db.queryForList("SELECT o.id,o.order_date orderDate,o.shop_id shopId,o.sku_id skuId,o.order_mode orderMode,o.quantity,o.sales_amount salesAmount,o.platform_fee platformFee,o.refund_amount refundAmount,s.purchase_price referencePrice,o.platform_order_no orderNo,o.platform_sku platformSku FROM order_summaries o JOIN skus s ON s.id=o.sku_id WHERE "+filter+" ORDER BY o.id",args.toArray());
        Map<Key,Metrics> daily=new LinkedHashMap<>();int actual=0,legacy=0,mixed=0,unmatched=0;
        for(Map<String,Object> o:orders){long id=n(o.get("id"));LocalDate day=date(o.get("orderDate"));long shopId=n(o.get("shopId")),skuId=n(o.get("skuId"));String mode=Objects.toString(o.get("orderMode"));BigDecimal qty=d(o.get("quantity")),revenue=d(o.get("salesAmount")),reference=d(o.get("referencePrice"));
            Map<String,Object> cost=db.queryForMap("SELECT COALESCE(SUM(di.shipped_qty),0) fulfilledQty,COALESCE((SELECT SUM(x.cost_amount) FROM fulfillment_goods_outbounds x JOIN fulfillment_goods_picks p ON p.id=x.pick_id JOIN fulfillment_goods_tasks t ON t.id=p.task_id JOIN fulfillment_dispatch_items z ON z.id=t.dispatch_item_id JOIN fulfillment_order_matches m ON m.fulfillment_item_id=z.document_item_id WHERE m.order_summary_id=?),0) goodsCost FROM fulfillment_dispatch_items di JOIN fulfillment_dispatches fd ON fd.id=di.dispatch_id JOIN fulfillment_order_matches fm ON fm.fulfillment_item_id=di.document_item_id WHERE fm.order_summary_id=? AND fd.status='HANDED_OFF'",id,id);
            BigDecimal fulfilled=d(cost.get("fulfilledQty")),goods=d(cost.get("goodsCost"));
            BigDecimal packageCost=d(db.queryForObject("SELECT COALESCE(SUM(p.cost_amount_snapshot),0) FROM package_location_documents p JOIN fulfillment_order_matches m ON m.fulfillment_item_id=p.fulfillment_document_item_id WHERE m.order_summary_id=? AND p.document_type='FULFILLMENT_CONSUMPTION'",BigDecimal.class,id));
            int unlinked=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM fulfillment_document_items i JOIN fulfillment_documents fd ON fd.id=i.document_id WHERE fd.document_type='JIT_ORDER' AND fd.shop_id=? AND fd.source_document_id=? AND i.platform_sku=? AND i.sku_id=? AND NOT EXISTS(SELECT 1 FROM fulfillment_order_matches m WHERE m.fulfillment_item_id=i.id) AND EXISTS(SELECT 1 FROM fulfillment_dispatch_items di JOIN fulfillment_dispatches x ON x.id=di.dispatch_id WHERE di.document_item_id=i.id AND x.status='HANDED_OFF')",Integer.class,shopId,o.get("orderNo"),o.get("platformSku"),skuId));
            int missingCost=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM fulfillment_goods_tasks t JOIN fulfillment_dispatch_items di ON di.id=t.dispatch_item_id JOIN fulfillment_dispatches fd ON fd.id=di.dispatch_id JOIN fulfillment_order_matches m ON m.fulfillment_item_id=di.document_item_id WHERE m.order_summary_id=? AND fd.status='HANDED_OFF' AND t.shipped_qty>(SELECT COALESCE(SUM(x.quantity),0) FROM fulfillment_goods_outbounds x JOIN fulfillment_goods_picks p ON p.id=x.pick_id WHERE p.task_id=t.id)",Integer.class,id));
            BigDecimal snapshotCoverage=d(db.queryForObject("SELECT COALESCE(MIN((SELECT COALESCE(SUM(x.quantity),0) FROM fulfillment_goods_outbounds x JOIN fulfillment_goods_picks p ON p.id=x.pick_id WHERE p.task_id=t.id)/t.units_per_sku),0) FROM fulfillment_goods_tasks t JOIN fulfillment_dispatch_items di ON di.id=t.dispatch_item_id JOIN fulfillment_dispatches fd ON fd.id=di.dispatch_id JOIN fulfillment_order_matches m ON m.fulfillment_item_id=di.document_item_id WHERE m.order_summary_id=? AND fd.status='HANDED_OFF' AND t.shipped_qty>0",BigDecimal.class,id));
            String basis;BigDecimal purchase;
            if(unlinked>0||missingCost>0){basis="COST_UNMATCHED";purchase=goods.add(qty.subtract(snapshotCoverage).max(BigDecimal.ZERO).multiply(reference).setScale(4,RoundingMode.HALF_UP));matching.costIssue(id,shopId,Objects.toString(o.get("platformSku"),null),missingCost>0?"履约实发货品缺少完整出库成本快照":"履约未能关联唯一订单，实际成本未完整归属");unmatched++;}
            else if(fulfilled.signum()==0){basis="LEGACY_REFERENCE";purchase=qty.multiply(reference).setScale(4,RoundingMode.HALF_UP);legacy++;}
            else if(fulfilled.compareTo(qty)<0){basis="MIXED_ESTIMATE";purchase=goods.add(qty.subtract(fulfilled).multiply(reference).setScale(4,RoundingMode.HALF_UP));mixed++;}
            else{basis="ACTUAL";purchase=goods;actual++;}
            if(fulfilled.compareTo(qty)>0)throw new IllegalArgumentException("履约累计实发大于订单销量，请先核对订单 "+id);
            List<Map<String,Object>> settlements=db.queryForList("SELECT platform_fee platformFee,settlement_amount cash,adjustment_type adjustmentType,adjustment_amount adjustmentAmount,settlement_complete complete FROM settlement_summaries WHERE order_summary_id=? AND match_status IN ('MATCHED','MANUAL') AND is_deleted=FALSE ORDER BY id",id);
            boolean complete=settlements.stream().anyMatch(s->Boolean.TRUE.equals(s.get("complete"))||Boolean.TRUE.equals(s.get("complete") instanceof Number x&&x.intValue()!=0));
            String settlementStatus=settlements.isEmpty()?"ESTIMATED":complete?"SETTLED":"PARTIALLY_SETTLED";
            BigDecimal fee=d(o.get("platformFee")),refund=d(o.get("refundAmount")),other=Z,cash=Z,adjustmentTotal=Z;
            if(complete)fee=settlements.stream().map(s->d(s.get("platformFee"))).reduce(Z,BigDecimal::add);
            for(Map<String,Object> s:settlements){cash=cash.add(d(s.get("cash")));BigDecimal delta=d(s.get("adjustmentAmount"));adjustmentTotal=adjustmentTotal.add(delta);switch(Objects.toString(s.get("adjustmentType"),"")){case "FEE_DELTA"->fee=fee.add(delta);case "REFUND_DELTA"->refund=refund.add(delta);case "OTHER_COST_DELTA"->other=other.add(delta);default->{}}}
            BigDecimal gross=revenue.subtract(fee).subtract(purchase).subtract(packageCost).subtract(refund).subtract(other);
            BigDecimal difference=d(o.get("platformFee")).subtract(fee).add(d(o.get("refundAmount")).subtract(refund)).subtract(other);
            db.update("INSERT INTO order_profit_facts(order_summary_id,order_date,shop_id,sku_id,order_mode,sales_qty,sales_revenue,platform_fee,purchase_cost,package_cost,refund_loss,other_cost,gross_profit,cost_basis,settlement_status,estimated_platform_fee,estimated_refund_loss,settlement_adjustment,settlement_cash_amount,settlement_difference,fulfilled_qty) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE order_date=VALUES(order_date),shop_id=VALUES(shop_id),sku_id=VALUES(sku_id),order_mode=VALUES(order_mode),sales_qty=VALUES(sales_qty),sales_revenue=VALUES(sales_revenue),platform_fee=VALUES(platform_fee),purchase_cost=VALUES(purchase_cost),package_cost=VALUES(package_cost),refund_loss=VALUES(refund_loss),other_cost=VALUES(other_cost),gross_profit=VALUES(gross_profit),cost_basis=VALUES(cost_basis),settlement_status=VALUES(settlement_status),estimated_platform_fee=VALUES(estimated_platform_fee),estimated_refund_loss=VALUES(estimated_refund_loss),settlement_adjustment=VALUES(settlement_adjustment),settlement_cash_amount=VALUES(settlement_cash_amount),settlement_difference=VALUES(settlement_difference),fulfilled_qty=VALUES(fulfilled_qty)",id,day,shopId,skuId,mode,qty,revenue,fee,purchase,packageCost,refund,other,gross,basis,settlementStatus,o.get("platformFee"),o.get("refundAmount"),adjustmentTotal,cash,difference,fulfilled);
            Key key=new Key(day,shopId,skuId,mode);Metrics m=daily.computeIfAbsent(key,k->new Metrics());m.qty=m.qty.add(qty);m.revenue=m.revenue.add(revenue);m.fee=m.fee.add(fee);m.purchase=m.purchase.add(purchase);m.pack=m.pack.add(packageCost);m.refund=m.refund.add(refund);m.other=m.other.add(other);m.difference=m.difference.add(difference);switch(basis){case "ACTUAL"->m.actual++;case "MIXED_ESTIMATE"->m.mixed++;case "LEGACY_REFERENCE"->m.legacy++;default->m.unmatched++;}switch(settlementStatus){case "SETTLED"->m.settled++;case "PARTIALLY_SETTLED"->m.partial++;default->m.estimated++;}
        }
        applyOtherCosts(from,to,daily);applyLegacyPackageCosts(from,to,daily);
        String dailyFilter="profit_date BETWEEN ? AND ?"+(shop==null?"":" AND shop_id=?")+(sku==null?"":" AND sku_id=?");db.update("DELETE FROM sku_profit_daily WHERE "+dailyFilter,args.toArray());
        for(var entry:daily.entrySet()){Key k=entry.getKey();Metrics m=entry.getValue();BigDecimal gross=m.revenue.subtract(m.fee).subtract(m.purchase).subtract(m.pack).subtract(m.refund).subtract(m.other);BigDecimal margin=m.revenue.signum()==0?null:gross.divide(m.revenue,8,RoundingMode.HALF_UP);db.update("INSERT INTO sku_profit_daily(profit_date,shop_id,sku_id,order_mode,sales_qty,sales_revenue,platform_fee,purchase_cost,package_cost,refund_loss,other_cost,gross_profit,gross_margin,actual_cost_orders,mixed_cost_orders,legacy_cost_orders,cost_unmatched_orders,estimated_orders,partial_settlement_orders,settled_orders,settlement_difference) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",k.day,k.shop,k.sku,k.mode,m.qty,m.revenue,m.fee,m.purchase,m.pack,m.refund,m.other,gross,margin,m.actual,m.mixed,m.legacy,m.unmatched,m.estimated,m.partial,m.settled,m.difference);}
        return Map.of("orders",orders.size(),"rows",daily.size(),"actualCostOrders",actual,"legacyCostOrders",legacy,"mixedCostOrders",mixed,"costUnmatchedOrders",unmatched);
    }

    private void applyOtherCosts(LocalDate from,LocalDate to,Map<Key,Metrics> rows){
        for(Map<String,Object> c:db.queryForList("SELECT cost_date day,shop_id shopId,sku_id skuId,SUM(amount) amount FROM other_cost_imports WHERE is_deleted=FALSE AND cost_date BETWEEN ? AND ? GROUP BY cost_date,shop_id,sku_id",from,to)){
            LocalDate day=date(c.get("day"));long shop=n(c.get("shopId")),sku=n(c.get("skuId"));List<Key> targets=rows.keySet().stream().filter(k->k.day.equals(day)&&k.shop==shop&&k.sku==sku).toList();distribute(rows,targets,d(c.get("amount")),false);
        }
    }
    private void applyLegacyPackageCosts(LocalDate from,LocalDate to,Map<Key,Metrics> rows){
        for(Map<String,Object> c:db.queryForList("SELECT DATE(i.created_at) day,i.sku_id skuId,SUM((-f.quantity_delta)*m.unit_price) amount FROM package_flows f JOIN inventory_flows i ON f.reference_type='INVENTORY_FLOW' AND f.reference_id=i.id JOIN package_materials m ON m.id=f.material_id WHERE f.flow_type='JIT_CONSUMPTION' AND i.flow_type='JIT_OUTBOUND' AND DATE(i.created_at) BETWEEN ? AND ? GROUP BY DATE(i.created_at),i.sku_id",from,to)){
            LocalDate day=date(c.get("day"));long sku=n(c.get("skuId"));List<Key> targets=rows.keySet().stream().filter(k->k.day.equals(day)&&k.sku==sku&&"JIT".equals(k.mode)&&rows.get(k).legacy>0&&rows.get(k).actual+rows.get(k).mixed+rows.get(k).unmatched==0).toList();distribute(rows,targets,d(c.get("amount")),true);
        }
    }
    private void distribute(Map<Key,Metrics> rows,List<Key> targets,BigDecimal amount,boolean packageCost){if(targets.isEmpty())return;BigDecimal total=targets.stream().map(k->rows.get(k).revenue.max(Z)).reduce(Z,BigDecimal::add);if(total.signum()==0)total=targets.stream().map(k->rows.get(k).qty.max(Z)).reduce(Z,BigDecimal::add);BigDecimal remainder=amount;for(int i=0;i<targets.size();i++){Metrics m=rows.get(targets.get(i));BigDecimal weight=m.revenue.max(Z);if(weight.signum()==0)weight=m.qty.max(Z);BigDecimal share=i==targets.size()-1?remainder:total.signum()==0?amount.divide(BigDecimal.valueOf(targets.size()),4,RoundingMode.HALF_UP):amount.multiply(weight).divide(total,4,RoundingMode.HALF_UP);if(packageCost)m.pack=m.pack.add(share);else m.other=m.other.add(share);remainder=remainder.subtract(share);}}
    private static long n(Object value){return ((Number)value).longValue();}
    private static BigDecimal d(Object value){return value==null?Z:new BigDecimal(value.toString());}
    private static LocalDate date(Object value){return value instanceof Date x?x.toLocalDate():value instanceof LocalDate x?x:LocalDate.parse(value.toString());}
    private record Key(LocalDate day,long shop,long sku,String mode){}
    private static final class Metrics {BigDecimal qty=Z,revenue=Z,fee=Z,purchase=Z,pack=Z,refund=Z,other=Z,difference=Z;int actual,mixed,legacy,unmatched,estimated,partial,settled;}
}
