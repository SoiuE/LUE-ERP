package com.lue.erp.profits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class ProfitCalculationService {
    private static final Logger log = LoggerFactory.getLogger(ProfitCalculationService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    public ProfitCalculationService(JdbcTemplate db, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> recalculate(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("利润日期范围无效");
        return transaction.execute(status -> {
            Map<Key, Metrics> rows = new LinkedHashMap<>();
            loadOrders(from, to, rows);
            loadSettlements(from, to, rows);
            allocateOtherCosts(from, to, rows);
            allocatePackageCosts(from, to, rows);

            db.update("DELETE FROM sku_profit_daily WHERE profit_date BETWEEN ? AND ?", from, to);
            List<Object[]> args = new ArrayList<>(rows.size());
            for (var entry : rows.entrySet()) {
                Key key = entry.getKey(); Metrics m = entry.getValue();
                BigDecimal gross = m.revenue.subtract(m.platformFee).subtract(m.purchaseCost)
                    .subtract(m.packageCost).subtract(m.refundLoss).subtract(m.otherCost);
                BigDecimal margin = m.revenue.signum() == 0 ? null : gross.divide(m.revenue, 8, RoundingMode.HALF_UP);
                args.add(new Object[]{key.date, key.shopId, key.skuId, key.mode, m.quantity, m.revenue,
                    m.platformFee, m.purchaseCost, m.packageCost, m.refundLoss, m.otherCost, gross, margin});
            }
            if (!args.isEmpty()) db.batchUpdate("INSERT INTO sku_profit_daily(profit_date,shop_id,sku_id,order_mode,sales_qty,sales_revenue,platform_fee,purchase_cost,package_cost,refund_loss,other_cost,gross_profit,gross_margin) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", args);
            return Map.of("dateFrom", from.toString(), "dateTo", to.toString(), "rows", rows.size());
        });
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Shanghai")
    public void scheduledRecalculate() {
        LocalDate to = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        LocalDate from = to.minusDays(13);
        try {
            Map<String, Object> result = recalculate(from, to);
            log.info("Scheduled SKU profit calculation completed: {}", result);
        } catch (RuntimeException e) {
            log.error("Scheduled SKU profit calculation failed for {}..{}", from, to, e);
        }
    }

    private void loadOrders(LocalDate from, LocalDate to, Map<Key, Metrics> rows) {
        String sql = "SELECT o.order_date profitDate,o.shop_id shopId,o.sku_id skuId,o.order_mode orderMode,SUM(o.quantity) salesQty,SUM(o.sales_amount) salesRevenue,SUM(o.platform_fee) orderPlatformFee,SUM(o.quantity*s.purchase_price) purchaseCost,SUM(o.refund_amount) refundLoss FROM order_summaries o JOIN skus s ON s.id=o.sku_id WHERE o.is_deleted=FALSE AND o.sku_id IS NOT NULL AND o.order_date BETWEEN ? AND ? GROUP BY o.order_date,o.shop_id,o.sku_id,o.order_mode";
        for (Map<String, Object> r : db.queryForList(sql, from, to)) {
            Key key = key(r); Metrics m = rows.computeIfAbsent(key, ignored -> new Metrics());
            m.quantity = decimal(r.get("salesQty")); m.revenue = decimal(r.get("salesRevenue"));
            m.orderPlatformFee = decimal(r.get("orderPlatformFee")); m.platformFee = m.orderPlatformFee;
            m.purchaseCost = decimal(r.get("purchaseCost")); m.refundLoss = decimal(r.get("refundLoss"));
        }
    }

    private void loadSettlements(LocalDate from, LocalDate to, Map<Key, Metrics> rows) {
        String sql = "SELECT settlement_date profitDate,shop_id shopId,sku_id skuId,order_mode orderMode,SUM(platform_fee) platformFee,COUNT(*) rowCount FROM settlement_summaries WHERE is_deleted=FALSE AND settlement_date BETWEEN ? AND ? GROUP BY settlement_date,shop_id,sku_id,order_mode";
        for (Map<String, Object> r : db.queryForList(sql, from, to)) {
            Metrics m = rows.computeIfAbsent(key(r), ignored -> new Metrics());
            m.platformFee = decimal(r.get("platformFee"));
        }
    }

    private void allocateOtherCosts(LocalDate from, LocalDate to, Map<Key, Metrics> rows) {
        String sql = "SELECT cost_date profitDate,shop_id shopId,sku_id skuId,SUM(amount) amount FROM other_cost_imports WHERE is_deleted=FALSE AND cost_date BETWEEN ? AND ? GROUP BY cost_date,shop_id,sku_id";
        for (Map<String, Object> cost : db.queryForList(sql, from, to)) {
            LocalDate date = date(cost.get("profitDate")); long shop = number(cost.get("shopId")), sku = number(cost.get("skuId"));
            distribute(rows, date, shop, sku, "JIT", decimal(cost.get("amount")), false);
        }
    }

    private void allocatePackageCosts(LocalDate from, LocalDate to, Map<Key, Metrics> rows) {
        String sql = "SELECT DATE(i.created_at) profitDate,i.sku_id skuId,SUM((-f.quantity_delta)*m.unit_price) packageCost FROM package_flows f JOIN inventory_flows i ON f.reference_type='INVENTORY_FLOW' AND f.reference_id=i.id JOIN package_materials m ON m.id=f.material_id WHERE f.flow_type='JIT_CONSUMPTION' AND i.flow_type='JIT_OUTBOUND' AND DATE(i.created_at) BETWEEN ? AND ? GROUP BY DATE(i.created_at),i.sku_id";
        for (Map<String, Object> cost : db.queryForList(sql, from, to)) {
            LocalDate date = date(cost.get("profitDate")); long sku = number(cost.get("skuId"));
            BigDecimal amount = decimal(cost.get("packageCost"));
            List<Key> targets = rows.keySet().stream().filter(k -> k.date.equals(date) && k.skuId == sku && "JIT".equals(k.mode)).toList();
            if (targets.isEmpty()) {
                rows.computeIfAbsent(new Key(date, null, sku, "JIT"), ignored -> new Metrics()).packageCost = amount;
                continue;
            }
            BigDecimal totalQty = targets.stream().map(k -> rows.get(k).quantity.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalRevenue = targets.stream().map(k -> rows.get(k).revenue.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean useQuantity = totalQty.signum() > 0;
            BigDecimal totalWeight = useQuantity ? totalQty : totalRevenue;
            BigDecimal remaining = amount;
            for (int i = 0; i < targets.size(); i++) {
                Key target = targets.get(i); BigDecimal share;
                if (i == targets.size() - 1) share = remaining;
                else {
                    BigDecimal weight = totalWeight.signum() > 0
                        ? (useQuantity ? rows.get(target).quantity.max(BigDecimal.ZERO) : rows.get(target).revenue.max(BigDecimal.ZERO))
                        : BigDecimal.ONE;
                    BigDecimal denominator = totalWeight.signum() > 0 ? totalWeight : BigDecimal.valueOf(targets.size());
                    share = amount.multiply(weight).divide(denominator, 8, RoundingMode.HALF_UP);
                }
                rows.get(target).packageCost = rows.get(target).packageCost.add(share);
                remaining = remaining.subtract(share);
            }
        }
    }

    private void distribute(Map<Key, Metrics> rows, LocalDate date, long shop, long sku, String defaultMode, BigDecimal amount, boolean packageCost) {
        List<Key> targets = rows.keySet().stream().filter(k -> k.date.equals(date) && Objects.equals(k.shopId, shop) && k.skuId == sku).toList();
        if (targets.isEmpty()) targets = List.of(new Key(date, shop, sku, defaultMode));
        BigDecimal totalRevenue = targets.stream().map(k -> rows.getOrDefault(k, new Metrics()).revenue.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalQty = targets.stream().map(k -> rows.getOrDefault(k, new Metrics()).quantity.max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal denominator = totalRevenue.signum() > 0 ? totalRevenue : totalQty.signum() > 0 ? totalQty : BigDecimal.valueOf(targets.size());
        BigDecimal remaining = amount;
        for (int i = 0; i < targets.size(); i++) {
            Key target = targets.get(i); Metrics m = rows.computeIfAbsent(target, ignored -> new Metrics());
            BigDecimal weight = totalRevenue.signum() > 0 ? m.revenue.max(BigDecimal.ZERO)
                : totalQty.signum() > 0 ? m.quantity.max(BigDecimal.ZERO) : BigDecimal.ONE;
            BigDecimal share = i == targets.size() - 1 ? remaining : amount.multiply(weight).divide(denominator, 8, RoundingMode.HALF_UP);
            if (packageCost) m.packageCost = m.packageCost.add(share); else m.otherCost = m.otherCost.add(share);
            remaining = remaining.subtract(share);
        }
    }

    private static Key key(Map<String, Object> row) { return new Key(date(row.get("profitDate")), number(row.get("shopId")), number(row.get("skuId")), String.valueOf(row.get("orderMode"))); }
    private static LocalDate date(Object value) { return value instanceof Date d ? d.toLocalDate() : LocalDate.parse(String.valueOf(value)); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static BigDecimal decimal(Object value) { return value == null ? ZERO : value instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(value)); }
    private record Key(LocalDate date, Long shopId, long skuId, String mode) {}
    private static class Metrics {
        BigDecimal quantity = ZERO, revenue = ZERO, orderPlatformFee = ZERO, platformFee = ZERO;
        BigDecimal purchaseCost = ZERO, packageCost = ZERO, refundLoss = ZERO, otherCost = ZERO;
    }
}
