package com.lue.erp.profits;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** Exact identifiers first; a missing or ambiguous link remains an exception. */
@Service
public class ProfitMatchService {
    private final JdbcTemplate db;
    public ProfitMatchService(JdbcTemplate db){this.db=db;}

    @Transactional
    public void matchFulfillment(){
        List<Map<String,Object>> items=db.queryForList("SELECT i.id,d.shop_id shopId,d.source_document_id orderNo,i.source_line_id lineNo,i.platform_sku platformSku,i.sku_id skuId FROM fulfillment_document_items i JOIN fulfillment_documents d ON d.id=i.document_id WHERE d.document_type='JIT_ORDER' AND EXISTS(SELECT 1 FROM fulfillment_dispatch_items di JOIN fulfillment_dispatches x ON x.id=di.dispatch_id WHERE di.document_item_id=i.id AND x.status='HANDED_OFF') AND NOT EXISTS(SELECT 1 FROM fulfillment_order_matches m WHERE m.fulfillment_item_id=i.id)");
        for(Map<String,Object> row:items){long id=((Number)row.get("id")).longValue();List<Long> candidates=candidates(row,"JIT");if(candidates.size()==1){db.update("INSERT IGNORE INTO fulfillment_order_matches(fulfillment_item_id,order_summary_id,match_method) VALUES (?,?,'AUTO')",id,candidates.get(0));resolve("FULFILLMENT_ITEM",id);}else issue("FULFILLMENT_ITEM",id,"FULFILLMENT_ORDER_UNMATCHED",row.get("shopId"),Objects.toString(row.get("platformSku"),null),candidates.isEmpty()?"履约明细无法关联唯一订单":"履约明细存在多个订单候选");}
    }

    @Transactional
    public void flagUnlinkedPackageCosts(){
        List<Map<String,Object>> rows=db.queryForList("SELECT p.id,d.shop_id shopId,i.platform_sku platformSku FROM package_location_documents p JOIN fulfillment_dispatches x ON x.id=p.fulfillment_dispatch_id JOIN fulfillment_documents d ON d.id=x.document_id LEFT JOIN fulfillment_document_items i ON i.id=p.fulfillment_document_item_id LEFT JOIN fulfillment_order_matches m ON m.fulfillment_item_id=i.id WHERE p.document_type='FULFILLMENT_CONSUMPTION' AND x.status='HANDED_OFF' AND d.document_type='JIT_ORDER' AND (i.id IS NULL OR m.order_summary_id IS NULL)");
        for(Map<String,Object> row:rows)costIssue(-((Number)row.get("id")).longValue(),row.get("shopId"),Objects.toString(row.get("platformSku"),null),"包材实际消耗无法关联唯一 JIT 订单，成本未进入订单利润");
    }

    @Transactional
    public void matchPendingSettlements(){
        for(Long id:db.queryForList("SELECT id FROM settlement_summaries WHERE match_status='PENDING' AND is_deleted=FALSE ORDER BY id",Long.class))matchSettlement(id);
    }

    @Transactional
    public LocalDate matchSettlement(long id){
        Map<String,Object> line=db.queryForMap("SELECT id,shop_id shopId,platform_order_no orderNo,platform_bill_no billNo,platform_line_no settlementLineNo,platform_order_line_no lineNo,platform_sku platformSku,sku_id skuId,order_mode orderMode,adjustment_type adjustmentType,adjustment_amount adjustmentAmount,order_summary_id orderId,match_status matchStatus FROM settlement_summaries WHERE id=? AND is_deleted=FALSE FOR UPDATE",id);
        if(line.get("orderId")!=null&&Set.of("MATCHED","MANUAL").contains(line.get("matchStatus")))return Objects.requireNonNull(db.queryForObject("SELECT order_date FROM order_summaries WHERE id=?",java.sql.Date.class,line.get("orderId"))).toLocalDate();
        if(line.get("billNo")==null||line.get("settlementLineNo")==null){String reason="缺少稳定平台账单号或结算明细号，不能保证跨文件防重";db.update("UPDATE settlement_summaries SET match_status='UNMAPPED',match_reason=? WHERE id=?",reason,id);issue("SETTLEMENT_LINE",id,"SETTLEMENT_AMOUNT_UNMAPPED",line.get("shopId"),Objects.toString(line.get("platformSku"),null),reason);return null;}
        List<Long> candidates=candidates(line,Objects.toString(line.get("orderMode"),null));
        if(candidates.size()!=1){String reason=candidates.isEmpty()?"结算明细找不到唯一订单":"结算明细存在多个订单候选";db.update("UPDATE settlement_summaries SET match_status='PENDING',match_reason=? WHERE id=?",reason,id);issue("SETTLEMENT_LINE",id,"SETTLEMENT_ORDER_UNMATCHED",line.get("shopId"),Objects.toString(line.get("platformSku"),null),reason);return null;}
        long order=candidates.get(0);String type=Objects.toString(line.get("adjustmentType"),"").trim().toUpperCase(Locale.ROOT);
        if(line.get("adjustmentAmount")!=null&&!Set.of("FEE_DELTA","REFUND_DELTA","OTHER_COST_DELTA").contains(type)){String reason="结算调整金额缺少可确认的收入/费用/退款类型映射";db.update("UPDATE settlement_summaries SET order_summary_id=?,match_status='UNMAPPED',match_reason=? WHERE id=?",order,reason,id);issue("SETTLEMENT_LINE",id,"SETTLEMENT_AMOUNT_UNMAPPED",line.get("shopId"),Objects.toString(line.get("platformSku"),null),reason);return null;}
        db.update("UPDATE settlement_summaries SET order_summary_id=?,match_status='MATCHED',match_reason=NULL WHERE id=?",order,id);resolve("SETTLEMENT_LINE",id);
        return Objects.requireNonNull(db.queryForObject("SELECT order_date FROM order_summaries WHERE id=?",java.sql.Date.class,order)).toLocalDate();
    }

    @Transactional
    public LocalDate repairSettlement(long line,long order,long actor){
        Map<String,Object> s=db.queryForMap("SELECT shop_id shopId,platform_order_no orderNo,platform_bill_no billNo,platform_line_no settlementLineNo,platform_order_line_no lineNo,platform_sku platformSku,sku_id skuId,order_mode orderMode,adjustment_type adjustmentType,adjustment_amount adjustmentAmount FROM settlement_summaries WHERE id=? AND is_deleted=FALSE FOR UPDATE",line);
        if(s.get("billNo")==null||s.get("settlementLineNo")==null)throw new IllegalArgumentException("结算明细缺少稳定账单号或明细号，请先补正来源文件");
        Map<String,Object> o=db.queryForMap("SELECT shop_id shopId,platform_order_no orderNo,platform_line_no lineNo,platform_sku platformSku,sku_id skuId,order_mode orderMode,order_date orderDate FROM order_summaries WHERE id=? AND is_deleted=FALSE",order);
        if(!Objects.equals(s.get("shopId"),o.get("shopId"))||!Objects.equals(s.get("platformSku"),o.get("platformSku"))||!Objects.equals(s.get("skuId"),o.get("skuId"))||!Objects.equals(s.get("orderMode"),o.get("orderMode")))throw new IllegalArgumentException("结算行与目标订单的店铺、平台 SKU、内部 SKU 或模式不一致");
        if(s.get("orderNo")!=null&&!Objects.equals(s.get("orderNo"),o.get("orderNo")))throw new IllegalArgumentException("平台订单号不一致");
        if(s.get("lineNo")!=null&&o.get("lineNo")!=null&&!Objects.equals(s.get("lineNo"),o.get("lineNo")))throw new IllegalArgumentException("平台明细号不一致");
        String type=Objects.toString(s.get("adjustmentType"),"").toUpperCase(Locale.ROOT);if(s.get("adjustmentAmount")!=null&&!Set.of("FEE_DELTA","REFUND_DELTA","OTHER_COST_DELTA").contains(type))throw new IllegalArgumentException("请先确认调整金额类型");
        db.update("UPDATE settlement_summaries SET order_summary_id=?,match_status='MANUAL',match_reason=NULL WHERE id=?",order,line);resolve("SETTLEMENT_LINE",line);return o.get("orderDate") instanceof java.sql.Date day?day.toLocalDate():LocalDate.parse(o.get("orderDate").toString());
    }

    @Transactional
    public LocalDate mapSettlementAdjustment(long line,String type){
        String mapped=Objects.toString(type,"").trim().toUpperCase(Locale.ROOT);
        if(!Set.of("FEE_DELTA","REFUND_DELTA","OTHER_COST_DELTA").contains(mapped))throw new IllegalArgumentException("调整类型必须是费用、退款或其他成本增量");
        Map<String,Object> current=db.queryForMap("SELECT adjustment_amount amount,platform_bill_no billNo,platform_line_no lineNo FROM settlement_summaries WHERE id=? AND is_deleted=FALSE FOR UPDATE",line);
        if(current.get("amount")==null)throw new IllegalArgumentException("结算行没有待映射调整金额");
        if(current.get("billNo")==null||current.get("lineNo")==null)throw new IllegalArgumentException("结算行缺少稳定账单标识，不能核销");
        db.update("UPDATE settlement_summaries SET adjustment_type=?,match_status='PENDING',match_reason=NULL WHERE id=?",mapped,line);
        return matchSettlement(line);
    }

    @Transactional
    public LocalDate repairFulfillment(long item,long order,long actor){
        Map<String,Object> f=db.queryForMap("SELECT d.shop_id shopId,d.source_document_id orderNo,i.source_line_id lineNo,i.platform_sku platformSku,i.sku_id skuId,d.document_type documentType FROM fulfillment_document_items i JOIN fulfillment_documents d ON d.id=i.document_id WHERE i.id=?",item);
        Map<String,Object> o=db.queryForMap("SELECT shop_id shopId,platform_order_no orderNo,platform_line_no lineNo,platform_sku platformSku,sku_id skuId,order_mode orderMode,order_date orderDate FROM order_summaries WHERE id=? AND is_deleted=FALSE",order);
        if(!"JIT_ORDER".equals(f.get("documentType"))||!"JIT".equals(o.get("orderMode"))||!Objects.equals(f.get("shopId"),o.get("shopId"))||!Objects.equals(f.get("orderNo"),o.get("orderNo"))||!Objects.equals(f.get("platformSku"),o.get("platformSku"))||!Objects.equals(f.get("skuId"),o.get("skuId")))throw new IllegalArgumentException("履约与订单的稳定标识不一致");
        if(f.get("lineNo")!=null&&o.get("lineNo")!=null&&!Objects.equals(f.get("lineNo"),o.get("lineNo")))throw new IllegalArgumentException("平台明细号不一致");
        db.update("INSERT INTO fulfillment_order_matches(fulfillment_item_id,order_summary_id,match_method,matched_by) VALUES (?,?,'MANUAL',?) ON DUPLICATE KEY UPDATE order_summary_id=VALUES(order_summary_id),match_method='MANUAL',matched_by=VALUES(matched_by),matched_at=CURRENT_TIMESTAMP(6)",item,order,actor);resolve("FULFILLMENT_ITEM",item);return o.get("orderDate") instanceof java.sql.Date day?day.toLocalDate():LocalDate.parse(o.get("orderDate").toString());
    }

    private List<Long> candidates(Map<String,Object> row,String mode){
        if(row.get("shopId")==null||row.get("orderNo")==null||Objects.toString(row.get("orderNo"),"").isBlank()||row.get("platformSku")==null||row.get("skuId")==null||mode==null)return List.of();
        List<Map<String,Object>> found=db.queryForList("SELECT id,platform_line_no lineNo FROM order_summaries WHERE shop_id=? AND platform_order_no=? AND platform_sku=? AND sku_id=? AND order_mode=? AND is_deleted=FALSE",row.get("shopId"),row.get("orderNo"),row.get("platformSku"),row.get("skuId"),mode);
        String line=Objects.toString(row.get("lineNo"),"");if(!line.isBlank()){List<Long> exact=found.stream().filter(x->line.equals(x.get("lineNo"))).map(x->((Number)x.get("id")).longValue()).toList();if(!exact.isEmpty())return exact;found=found.stream().filter(x->x.get("lineNo")==null).toList();}
        return found.stream().map(x->((Number)x.get("id")).longValue()).toList();
    }
    public void settlementIssue(long id,Object shop,String sku,String title){issue("SETTLEMENT_LINE",id,"SETTLEMENT_AMOUNT_UNMAPPED",shop,sku,title);}
    public void costIssue(long order,Object shop,String sku,String title){issue("COST_SOURCE",order,"PROFIT_COST_SOURCE_UNMATCHED",shop,sku,title);}
    private void issue(String source,long id,String type,Object shop,String sku,String title){
        Long existing=db.query("SELECT exception_id FROM profit_match_issues WHERE source_type=? AND source_id=?",rs->rs.next()?rs.getLong(1):null,source,id);
        if(existing!=null){db.update("UPDATE exceptions SET status='OPEN',exception_type=?,title=?,resolved_at=NULL WHERE id=?",type,title,existing);return;}
        String no="EX"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        db.update("INSERT INTO exceptions(exception_no,exception_type,status,shop_id,platform_sku,title,raw_data) VALUES (?,?,'OPEN',?,?,?,JSON_OBJECT('sourceType',?,'sourceId',?))",no,type,shop,sku,title,source,id);
        long exception=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);db.update("INSERT INTO profit_match_issues(source_type,source_id,exception_id) VALUES (?,?,?)",source,id,exception);
    }
    private void resolve(String source,long id){db.update("UPDATE exceptions e JOIN profit_match_issues p ON p.exception_id=e.id SET e.status='RESOLVED',e.resolution_note='已关联到唯一订单',e.resolved_at=CURRENT_TIMESTAMP WHERE p.source_type=? AND p.source_id=? AND e.status='OPEN'",source,id);}
}
