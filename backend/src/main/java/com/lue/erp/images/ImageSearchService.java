package com.lue.erp.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ImageSearchService {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final ImageContentValidator validator;
    private final ImageAssetService assets;
    private final ImageSearchClient client;
    private final ImageSearchProperties properties;

    public ImageSearchService(JdbcTemplate db, AuthService auth, ObjectMapper json, ImageContentValidator validator,
            ImageAssetService assets, ImageSearchClient client, ImageSearchProperties properties) {
        this.db = db; this.auth = auth; this.json = json; this.validator = validator;
        this.assets = assets; this.client = client; this.properties = properties;
    }

    public Map<String,Object> search(MultipartFile file, AuthUser user) {
        view(user);
        ImageContentValidator.ValidatedImage query = validator.validate(file);
        ImageSearchClient.SearchResponse response = client.search(query.bytes(), query.contentType(), query.originalFilename(), 100);
        List<ImageSearchClient.Hit> accepted = acceptedHits(response.items(), properties.getMinimumScore());
        List<Map<String,Object>> candidates = aggregate(accepted).stream().limit(10).toList();
        return Map.of("items", candidates, "indexCount", response.count(), "modelVersion", response.modelVersion(),
            "minimumScore", properties.getMinimumScore(), "lowSimilarity", !response.items().isEmpty() && candidates.isEmpty());
    }

    public Map<String,Object> manual(String query, AuthUser user) {
        view(user);
        String value = Objects.toString(query, "").trim();
        if (value.isBlank()) return Map.of("items", List.of());
        if (value.length() > 100) throw new IllegalArgumentException("搜索词最多 100 个字符");
        String like = "%" + value + "%";
        List<Map<String,Object>> candidates = new ArrayList<>();
        for (Map<String,Object> row : db.queryForList("SELECT 'SKU' objectType,id objectId,internal_sku code,product_name name,specification FROM skus WHERE is_deleted=FALSE AND status='ACTIVE' AND (internal_sku LIKE ? OR product_name LIKE ? OR barcode LIKE ?) ORDER BY internal_sku LIMIT 10", like, like, like)) candidates.add(enrich(row));
        for (Map<String,Object> row : db.queryForList("SELECT 'WAREHOUSE_GOODS' objectType,id objectId,goods_code code,goods_name name,specification FROM warehouse_goods WHERE is_deleted=FALSE AND status='ACTIVE' AND (goods_code LIKE ? OR goods_name LIKE ? OR barcode LIKE ?) ORDER BY goods_code LIMIT 10", like, like, like)) candidates.add(enrich(row));
        return Map.of("items", candidates.stream().limit(20).toList());
    }

    public Map<String,Object> status(AuthUser user) {
        manage(user);
        Map<String,Long> counts = new LinkedHashMap<>();
        for (Map<String,Object> row : db.queryForList("SELECT index_status status,COUNT(*) count FROM image_assets GROUP BY index_status"))
            counts.put(String.valueOf(row.get("status")), ((Number) row.get("count")).longValue());
        List<Map<String,Object>> failures = db.queryForList("SELECT id,index_error indexError,index_attempt_count indexAttemptCount,index_last_attempt_at indexLastAttemptAt FROM image_assets WHERE index_status='FAILED' ORDER BY index_last_attempt_at DESC,id DESC LIMIT 20");
        Map<String,Object> health;
        try { health = new LinkedHashMap<>((Map<String,Object>) client.health()); }
        catch (Exception ex) { health = Map.of("ready", false, "error", Objects.toString(ex.getMessage(), "服务不可用")); }
        return Map.of("counts", counts, "failures", failures, "service", health);
    }

    @Transactional
    public Map<String,Object> retry(long assetId, AuthUser user, HttpServletRequest request) {
        manage(user);
        int updated = db.update("UPDATE image_assets SET index_status='PENDING',index_error=NULL,index_requested_at=CURRENT_TIMESTAMP WHERE id=? AND status='ACTIVE' AND source_type IN ('UPLOAD','ARCHIVED_URL')", assetId);
        if (updated != 1) throw new IllegalArgumentException("图片不存在、已停用或尚未归档");
        audit(user, request, "image.search.index.retry", assetId, Map.of());
        return Map.of("queued", true);
    }

    @Transactional
    public Map<String,Object> rebuild(AuthUser user, HttpServletRequest request) {
        manage(user);
        client.reset();
        String purposes = indexPurposesSql();
        int queued = db.update("UPDATE image_assets a SET index_status='PENDING',index_error=NULL,index_version=NULL,index_requested_at=CURRENT_TIMESTAMP WHERE a.status='ACTIVE' AND a.source_type IN ('UPLOAD','ARCHIVED_URL') AND EXISTS (SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND l.purpose IN (" + purposes + "))");
        db.update("UPDATE image_assets a SET index_status='NOT_INDEXED',index_error=NULL,index_version=NULL,index_completed_at=NULL WHERE NOT (a.status='ACTIVE' AND a.source_type IN ('UPLOAD','ARCHIVED_URL') AND EXISTS (SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND l.purpose IN (" + purposes + ")))");
        audit(user, request, "image.search.index.rebuild", 0, Map.of("queued", queued));
        return Map.of("queued", queued);
    }

    public Map<String,Object> backup(AuthUser user, HttpServletRequest request) {
        manage(user);
        Map<?,?> result = client.backup();
        audit(user, request, "image.search.index.backup", 0, Map.of("filename", Objects.toString(result.get("filename"), "")));
        return Map.of("filename", Objects.toString(result.get("filename"), ""));
    }

    @Scheduled(fixedDelayString = "${lue.images.search.index-poll-delay-ms:3000}")
    public void indexPending() {
        db.update("UPDATE image_assets SET index_status='PENDING',index_error='上次索引异常中断，已重新排队' WHERE index_status='PROCESSING' AND index_last_attempt_at < DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5 MINUTE)");
        List<Long> ids = db.query("SELECT id FROM image_assets WHERE index_status='PENDING' ORDER BY index_requested_at,id LIMIT 10", (rs, row) -> rs.getLong(1));
        for (long id : ids) processIndex(id);
    }

    void processIndex(long id) {
        if (db.update("UPDATE image_assets SET index_status='PROCESSING',index_attempt_count=index_attempt_count+1,index_last_attempt_at=CURRENT_TIMESTAMP,index_error=NULL WHERE id=? AND index_status='PENDING'", id) != 1) return;
        try {
            Map<String,Object> state = db.queryForMap("SELECT source_type sourceType,status,EXISTS(SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND l.purpose IN (" + indexPurposesSql() + ")) eligible FROM image_assets a WHERE id=?", id);
            if (!"ACTIVE".equals(state.get("status")) || !truthy(state.get("eligible"))) {
                client.delete(id);
                db.update("UPDATE image_assets SET index_status='NOT_INDEXED',index_error=NULL,index_version=NULL,index_completed_at=CURRENT_TIMESTAMP WHERE id=? AND index_status='PROCESSING'", id);
                return;
            }
            if ("EXTERNAL_URL".equals(state.get("sourceType"))) throw new IllegalStateException("外链图片需先归档后才能索引");
            ImageSearchClient.IndexResponse result = client.index(id, assets.read(id));
            db.update("UPDATE image_assets SET index_status='INDEXED',index_error=NULL,index_version=?,index_completed_at=CURRENT_TIMESTAMP WHERE id=? AND index_status='PROCESSING'", result.modelVersion(), id);
        } catch (Exception ex) {
            String message = readable(ex);
            db.update("UPDATE image_assets SET index_status='FAILED',index_error=?,index_completed_at=CURRENT_TIMESTAMP WHERE id=? AND index_status='PROCESSING'", message, id);
        }
    }

    private List<Map<String,Object>> aggregate(List<ImageSearchClient.Hit> hits) {
        if (hits.isEmpty()) return List.of();
        Map<Long,Double> scores = new LinkedHashMap<>();
        for (ImageSearchClient.Hit hit : hits) scores.put(hit.assetId(), hit.score());
        String placeholders = String.join(",", java.util.Collections.nCopies(scores.size(), "?"));
        List<Map<String,Object>> rows = db.queryForList("SELECT a.id assetId,a.source_type sourceType,a.external_url externalUrl,l.object_type objectType,l.object_id objectId,COALESCE(s.internal_sku,g.goods_code) code,COALESCE(s.product_name,g.goods_name) name,COALESCE(s.specification,g.specification) specification FROM image_assets a JOIN image_asset_links l ON l.image_asset_id=a.id AND l.status='ACTIVE' LEFT JOIN skus s ON l.object_type='SKU' AND s.id=l.object_id AND s.status='ACTIVE' AND s.is_deleted=FALSE LEFT JOIN warehouse_goods g ON l.object_type='WAREHOUSE_GOODS' AND g.id=l.object_id AND g.status='ACTIVE' AND g.is_deleted=FALSE WHERE a.status='ACTIVE' AND a.id IN (" + placeholders + ") AND l.purpose IN (" + indexPurposesSql() + ") AND (s.id IS NOT NULL OR g.id IS NOT NULL)", scores.keySet().toArray());
        return mergeObjectRows(rows, scores).stream().map(this::enrich).toList();
    }

    static List<ImageSearchClient.Hit> acceptedHits(List<ImageSearchClient.Hit> hits, double minimumScore) {
        return hits.stream()
            .filter(hit -> hit.score() >= minimumScore)
            .sorted(Comparator.comparingDouble(ImageSearchClient.Hit::score).reversed())
            .toList();
    }

    static List<Map<String,Object>> mergeObjectRows(List<Map<String,Object>> rows, Map<Long,Double> scores) {
        Map<String,Map<String,Object>> merged = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            long assetId = ((Number) row.get("assetId")).longValue();
            double score = scores.getOrDefault(assetId, -1.0);
            String key = row.get("objectType") + ":" + row.get("objectId");
            Map<String,Object> previous = merged.get(key);
            if (previous == null || score > ((Number) previous.get("score")).doubleValue()) {
                Map<String,Object> candidate = new LinkedHashMap<>(row);
                candidate.put("score", score);
                merged.put(key, candidate);
            }
        }
        return merged.values().stream().sorted(Comparator.comparingDouble(row -> -((Number) row.get("score")).doubleValue())).limit(10).toList();
    }

    private Map<String,Object> enrich(Map<String,Object> candidate) {
        Map<String,Object> result = new LinkedHashMap<>(candidate);
        String type = String.valueOf(candidate.get("objectType"));
        long objectId = ((Number) candidate.get("objectId")).longValue();
        if ("WAREHOUSE_GOODS".equals(type)) {
            BigDecimal available = db.queryForObject("SELECT COALESCE(SUM(CASE WHEN l.status='ACTIVE' AND l.location_type IN ('STORAGE','PRIMARY_PICK','TEMPORARY') THEN b.available_qty ELSE 0 END),0) FROM warehouse_goods_location_balances b LEFT JOIN warehouse_locations l ON l.id=b.location_id WHERE b.warehouse_goods_id=?", BigDecimal.class, objectId);
            result.put("inventoryText", "可拣库存 " + Objects.requireNonNullElse(available, BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        } else {
            List<Map<String,Object>> components = db.queryForList("SELECT g.goods_code goodsCode,i.quantity requiredQty,COALESCE(SUM(CASE WHEN l.status='ACTIVE' AND l.location_type IN ('STORAGE','PRIMARY_PICK','TEMPORARY') THEN b.available_qty ELSE 0 END),0) availableQty FROM sku_composition_versions v JOIN sku_composition_items i ON i.version_id=v.id JOIN warehouse_goods g ON g.id=i.warehouse_goods_id LEFT JOIN warehouse_goods_location_balances b ON b.warehouse_goods_id=g.id LEFT JOIN warehouse_locations l ON l.id=b.location_id WHERE v.id=(SELECT v2.id FROM sku_composition_versions v2 WHERE v2.sku_id=? AND v2.status='ACTIVE' AND (v2.effective_from IS NULL OR v2.effective_from<=CURRENT_DATE) AND (v2.effective_to IS NULL OR v2.effective_to>=CURRENT_DATE) ORDER BY v2.version_no DESC LIMIT 1) GROUP BY g.id,g.goods_code,i.quantity ORDER BY g.goods_code", objectId);
            String summary = components.isEmpty() ? "未配置生效组成" : components.stream().map(row -> row.get("goodsCode") + " 可拣 " + decimal(row.get("availableQty")) + " / 单件需 " + decimal(row.get("requiredQty"))).collect(java.util.stream.Collectors.joining("；"));
            result.put("inventoryText", summary);
        }
        return result;
    }

    private String indexPurposesSql() { return properties.isIncludePackaging() ? "'MAIN_IMAGE','PRODUCT_PHOTO','PACKAGING'" : "'MAIN_IMAGE','PRODUCT_PHOTO'"; }
    private void view(AuthUser user) { auth.requirePermission(user.id(), "menu.image-search"); }
    private void manage(AuthUser user) { view(user); auth.requirePermission(user.id(), "button.image-search.manage"); }
    private void audit(AuthUser user, HttpServletRequest request, String action, long id, Map<String,Object> detail) {
        try { db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,TRUE)", user.id(), user.username(), action, "image_search", String.valueOf(id), json.writeValueAsString(detail), request.getRemoteAddr()); }
        catch (Exception ex) { throw new IllegalStateException("图片搜索操作记录写入失败", ex); }
    }
    private static String readable(Exception ex) { String value=Objects.toString(ex.getMessage(),"图片索引失败").replaceAll("[\\p{Cntrl}]","").trim(); return value.length()>1000?value.substring(0,1000):value; }
    private static String decimal(Object value) { return value == null ? "0" : new BigDecimal(String.valueOf(value)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private static boolean truthy(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0; }
}
