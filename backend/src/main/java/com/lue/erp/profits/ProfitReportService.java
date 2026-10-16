package com.lue.erp.profits;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class ProfitReportService {
    public static final int MAX_EXPORT_ROWS = 50_000;
    private final JdbcTemplate db;
    public ProfitReportService(JdbcTemplate db) { this.db = db; }

    public Map<String,Object> list(String dimension, LocalDate from, LocalDate to, Long shopId, Long skuId, String mode, String q, int page, int pageSize) {
        QuerySpec spec = spec(dimension);
        Filter filter = filters(from, to, shopId, skuId, mode, q);
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, pageSize));
        Long total = db.queryForObject("SELECT COUNT(*) FROM (SELECT 1" + filter.from + filter.where + " GROUP BY " + spec.group + ") dimensions", Long.class, filter.args.toArray());
        List<Object> args = new ArrayList<>(filter.args); args.add(safeSize); args.add((safePage - 1) * safeSize);
        List<Map<String,Object>> items = db.queryForList(select(spec) + filter.from + filter.where + " GROUP BY " + spec.group + " ORDER BY " + spec.order + " LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", items, "total", Objects.requireNonNullElse(total, 0L), "page", safePage, "pageSize", safeSize, "dimension", dimension);
    }

    public List<Map<String,Object>> export(String dimension, LocalDate from, LocalDate to, Long shopId, Long skuId, String mode, String q) {
        QuerySpec spec = spec(dimension); Filter filter = filters(from, to, shopId, skuId, mode, q);
        Long total = db.queryForObject("SELECT COUNT(*) FROM (SELECT 1" + filter.from + filter.where + " GROUP BY " + spec.group + ") dimensions", Long.class, filter.args.toArray());
        if (total != null && total > MAX_EXPORT_ROWS) throw new IllegalArgumentException("导出超过 50,000 行（当前 " + total + " 行），请缩小日期范围或增加筛选条件");
        return db.queryForList(select(spec) + filter.from + filter.where + " GROUP BY " + spec.group + " ORDER BY " + spec.order, filter.args.toArray());
    }

    private static String select(QuerySpec spec) {
        return "SELECT " + spec.columns + ",SUM(d.sales_qty) salesQty,SUM(d.sales_revenue) salesRevenue,SUM(d.platform_fee) platformFee,SUM(d.purchase_cost) purchaseCost,SUM(d.package_cost) packageCost,SUM(d.refund_loss) refundLoss,SUM(d.other_cost) otherCost,SUM(d.gross_profit) grossProfit,SUM(d.actual_cost_orders) actualCostOrders,SUM(d.mixed_cost_orders) mixedCostOrders,SUM(d.legacy_cost_orders) legacyCostOrders,SUM(d.cost_unmatched_orders) costUnmatchedOrders,SUM(d.estimated_orders) estimatedOrders,SUM(d.partial_settlement_orders) partialSettlementOrders,SUM(d.settled_orders) settledOrders,SUM(d.settlement_difference) settlementDifference,CASE WHEN SUM(d.sales_revenue)=0 THEN NULL ELSE SUM(d.gross_profit)/SUM(d.sales_revenue) END grossMargin";
    }
    private static QuerySpec spec(String dimension) {
        return switch (dimension) {
            case "daily" -> new QuerySpec("d.profit_date, d.shop_id, d.sku_id, d.order_mode",
                "d.profit_date reportDate,d.shop_id shopId,d.sku_id skuId,COALESCE(sh.shop_name,'未归属店铺') shopName,s.internal_sku internalSku,s.product_name productName,d.order_mode orderMode",
                "d.profit_date DESC,sh.shop_name,s.internal_sku,d.order_mode");
            case "monthly" -> new QuerySpec("DATE_FORMAT(d.profit_date,'%Y-%m'),d.shop_id,d.sku_id,d.order_mode",
                "DATE_FORMAT(d.profit_date,'%Y-%m') reportMonth,d.shop_id shopId,d.sku_id skuId,COALESCE(sh.shop_name,'未归属店铺') shopName,s.internal_sku internalSku,s.product_name productName,d.order_mode orderMode",
                "reportMonth DESC,sh.shop_name,s.internal_sku,d.order_mode");
            case "shop" -> new QuerySpec("d.shop_id", "d.shop_id shopId,COALESCE(sh.shop_name,'未归属店铺') shopName", "shopName");
            case "mode" -> new QuerySpec("d.order_mode", "d.order_mode orderMode", "d.order_mode");
            default -> throw new IllegalArgumentException("利润报表维度无效");
        };
    }
    private static Filter filters(LocalDate from, LocalDate to, Long shopId, Long skuId, String mode, String q) {
        StringBuilder where = new StringBuilder(" WHERE d.is_deleted=FALSE"); List<Object> args = new ArrayList<>();
        if (from != null) { where.append(" AND d.profit_date>=?"); args.add(from); }
        if (to != null) { where.append(" AND d.profit_date<=?"); args.add(to); }
        if (shopId != null) { where.append(" AND d.shop_id=?"); args.add(shopId); }
        if (skuId != null) { where.append(" AND d.sku_id=?"); args.add(skuId); }
        if (mode != null && !mode.isBlank()) { if (!Set.of("JIT", "WAREHOUSE").contains(mode)) throw new IllegalArgumentException("订单模式无效"); where.append(" AND d.order_mode=?"); args.add(mode); }
        if (q != null && !q.isBlank()) { where.append(" AND (s.internal_sku LIKE ? OR s.product_name LIKE ?)"); args.add("%" + q.trim() + "%"); args.add("%" + q.trim() + "%"); }
        return new Filter(" FROM sku_profit_daily d JOIN skus s ON s.id=d.sku_id LEFT JOIN shops sh ON sh.id=d.shop_id", where.toString(), args);
    }
    private record QuerySpec(String group, String columns, String order) {}
    private record Filter(String from, String where, List<Object> args) {}
}
