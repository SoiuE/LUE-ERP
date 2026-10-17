package com.lue.erp.images;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.YearMonth;
import java.util.*;

@Service
public class ImageAssetService {
    private static final Set<String> OBJECT_TYPES = Set.of("SKU", "WAREHOUSE_GOODS");
    private static final Set<String> PURPOSES = Set.of("MAIN_IMAGE", "PRODUCT_PHOTO", "PACKAGING", "WAREHOUSE_PHOTO", "OTHER");
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final ImageStorage storage;
    private final ImageContentValidator validator;

    public ImageAssetService(JdbcTemplate db, AuthService auth, ObjectMapper json, ImageStorage storage, ImageContentValidator validator) {
        this.db = db; this.auth = auth; this.json = json; this.storage = storage; this.validator = validator;
    }

    @Transactional
    public Map<String,Object> upload(org.springframework.web.multipart.MultipartFile file, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        ImageContentValidator.ValidatedImage image = validator.validate(file);
        String objectKey = "images/" + YearMonth.now() + "/" + UUID.randomUUID() + extension(image.contentType());
        try { storage.put(objectKey, image.bytes(), image.contentType()); }
        catch (IOException ex) { throw new IllegalStateException("图片文件存储暂不可用"); }
        String thumbnailKey = null, thumbnailStatus = "READY", thumbnailError = null;
        try {
            byte[] thumbnail = ImageThumbnailer.jpeg(image.bytes(), 320);
            thumbnailKey = "images/thumbnails/" + YearMonth.now() + "/" + UUID.randomUUID() + ".jpg";
            storage.put(thumbnailKey, thumbnail, "image/jpeg");
        } catch (Exception ex) {
            if (thumbnailKey != null) try { storage.delete(thumbnailKey); } catch (IOException ignored) { }
            thumbnailKey = null; thumbnailStatus = "FAILED"; thumbnailError = "缩略图生成或存储失败";
        }
        try {
            db.update("INSERT INTO image_assets(source_type,object_key,original_filename,content_type,file_size,width,height,thumbnail_object_key,thumbnail_status,thumbnail_error,index_status,index_requested_at,created_by) VALUES ('UPLOAD',?,?,?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,?)",
                objectKey, image.originalFilename(), image.contentType(), image.bytes().length, image.width(), image.height(), thumbnailKey, thumbnailStatus, thumbnailError, actor.id());
            long id = lastId();
            audit(actor, request, "image.upload", "image_asset", id, Map.of("sourceType", "UPLOAD", "contentType", image.contentType(), "fileSize", image.bytes().length));
            return detail(id);
        } catch (RuntimeException ex) {
            if (thumbnailKey != null) try { storage.delete(thumbnailKey); } catch (IOException ignored) { }
            try { storage.delete(objectKey); } catch (IOException ignored) { }
            throw ex;
        }
    }

    @Transactional
    public Map<String,Object> createExternal(String url, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        String safeUrl = validator.validateExternalUrl(url);
        db.update("INSERT INTO image_assets(source_type,external_url,created_by) VALUES ('EXTERNAL_URL',?,?)", safeUrl, actor.id());
        long id = lastId();
        audit(actor, request, "image.external.create", "image_asset", id, Map.of("sourceType", "EXTERNAL_URL"));
        return detail(id);
    }

    public Map<String,Object> list(int page, int pageSize, String sourceType, String status, String objectType,
                                   Long objectId, String purpose, String tag, String q) {
        int p = Math.max(1, page), size = Math.min(100, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) { requireSource(sourceType); where.append(" AND a.source_type=?"); args.add(sourceType); }
        if (status != null && !status.isBlank()) { requireStatus(status); where.append(" AND a.status=?"); args.add(status); }
        if (objectType != null && !objectType.isBlank()) { requireObjectType(objectType); where.append(" AND EXISTS (SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND l.object_type=?"); args.add(objectType); if (objectId != null) { where.append(" AND l.object_id=?"); args.add(objectId); } where.append(')'); }
        else if (objectId != null) throw new IllegalArgumentException("objectId 必须与 objectType 一起提供");
        if (purpose != null && !purpose.isBlank()) { requirePurpose(purpose); where.append(" AND EXISTS (SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND l.purpose=?)"); args.add(purpose); }
        if (tag != null && !tag.isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND JSON_CONTAINS(COALESCE(l.tags,JSON_ARRAY()),JSON_QUOTE(?)))"); args.add(tag.trim()); }
        if (q != null && !q.isBlank()) {
            String term = "%" + q.trim() + "%";
            where.append(" AND (a.original_filename LIKE ? OR a.external_url LIKE ? OR a.source_url LIKE ? OR EXISTS (SELECT 1 FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.status='ACTIVE' AND ((l.object_type='SKU' AND EXISTS (SELECT 1 FROM skus s WHERE s.id=l.object_id AND (s.internal_sku LIKE ? OR s.product_name LIKE ?))) OR (l.object_type='WAREHOUSE_GOODS' AND EXISTS (SELECT 1 FROM warehouse_goods g WHERE g.id=l.object_id AND (g.goods_code LIKE ? OR g.goods_name LIKE ?))))))");
            args.add(term); args.add(term); args.add(term); args.add(term); args.add(term); args.add(term); args.add(term);
        }
        Long total = db.queryForObject("SELECT COUNT(*) FROM image_assets a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        String ordering = objectType != null && objectId != null
            ? " ORDER BY (SELECT l.is_primary FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.object_type=? AND l.object_id=? AND l.status='ACTIVE' LIMIT 1) DESC,(SELECT l.sort_order FROM image_asset_links l WHERE l.image_asset_id=a.id AND l.object_type=? AND l.object_id=? AND l.status='ACTIVE' LIMIT 1),a.id"
            : " ORDER BY a.created_at DESC,a.id DESC";
        if (objectType != null && objectId != null) { pageArgs.add(objectType); pageArgs.add(objectId); pageArgs.add(objectType); pageArgs.add(objectId); }
        pageArgs.add(size); pageArgs.add((p - 1) * size);
        List<Map<String,Object>> rows = db.queryForList("SELECT a.id,a.source_type sourceType,a.external_url externalUrl,a.source_url sourceUrl,a.object_key objectKey,a.original_filename originalFilename,a.content_type contentType,a.file_size fileSize,a.width,a.height,a.archive_status archiveStatus,a.archive_error archiveError,a.archive_attempt_count archiveAttemptCount,a.archive_requested_at archiveRequestedAt,a.archive_last_attempt_at archiveLastAttemptAt,a.archive_completed_at archiveCompletedAt,a.thumbnail_status thumbnailStatus,a.thumbnail_error thumbnailError,a.index_status indexStatus,a.index_version indexVersion,a.index_error indexError,a.index_requested_at indexRequestedAt,a.index_completed_at indexCompletedAt,a.status,a.created_by createdBy,a.created_at createdAt,a.updated_at updatedAt FROM image_assets a" + where + ordering + " LIMIT ? OFFSET ?", pageArgs.toArray());
        for (Map<String,Object> row : rows) { row.remove("objectKey"); row.put("links", links(((Number) row.get("id")).longValue())); }
        return Map.of("items", rows, "total", Objects.requireNonNullElse(total, 0L), "page", p, "pageSize", size);
    }

    public Map<String,Object> detail(long id) {
        Map<String,Object> row = assetRow(id);
        row.remove("objectKey");
        row.put("links", links(id));
        return row;
    }

    public Map<String,Object> references(String objectType, String ids, AuthUser user) {
        String type = requireObjectType(objectType);
        if (!canViewType(user, type)) throw new AccessDeniedException("没有查看商品参考图的权限");
        LinkedHashSet<Long> objectIds = new LinkedHashSet<>();
        try {
            for (String token : ids.split(",")) {
                long id = Long.parseLong(token.trim());
                if (id < 1) throw new NumberFormatException();
                objectIds.add(id);
            }
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("对象 ID 无效"); }
        if (objectIds.isEmpty() || objectIds.size() > 100) throw new IllegalArgumentException("一次只能查询 1 至 100 个对象");
        String placeholders = String.join(",", Collections.nCopies(objectIds.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(type); args.addAll(objectIds);
        List<Map<String,Object>> rows = db.queryForList("SELECT l.object_id objectId,a.id,a.source_type sourceType,a.external_url externalUrl FROM image_asset_links l JOIN image_assets a ON a.id=l.image_asset_id AND a.status='ACTIVE' WHERE l.status='ACTIVE' AND l.object_type=? AND l.object_id IN (" + placeholders + ") ORDER BY l.object_id,l.is_primary DESC,l.sort_order,a.id", args.toArray());
        Map<String,Object> result = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) result.putIfAbsent(String.valueOf(row.get("objectId")), Map.of(
            "id", row.get("id"), "sourceType", row.get("sourceType"), "externalUrl", Objects.toString(row.get("externalUrl"), "")));
        return Map.of("items", result);
    }

    public void authorizeReferenceRead(long assetId, AuthUser user) {
        if (auth.hasPermission(user.id(), "menu.images")) return;
        List<String> linkedTypes = db.query("SELECT DISTINCT l.object_type FROM image_asset_links l JOIN image_assets a ON a.id=l.image_asset_id AND a.status='ACTIVE' WHERE l.image_asset_id=? AND l.status='ACTIVE'", (rs, row) -> rs.getString(1), assetId);
        if (linkedTypes.stream().noneMatch(type -> canViewType(user, type))) throw new AccessDeniedException("没有查看商品参考图的权限");
    }

    private boolean canViewType(AuthUser user, String type) {
        String[] permissions = "SKU".equals(type)
            ? new String[]{"menu.images", "menu.image-search", "master.skus.view", "menu.fulfillment", "menu.imports", "menu.mobile"}
            : new String[]{"menu.images", "menu.image-search", "master.goods.view", "menu.purchases", "warehouse.location_stock.view", "menu.fulfillment", "menu.mobile"};
        for (String permission : permissions) if (auth.hasPermission(user.id(), permission)) return true;
        return false;
    }

    @Transactional
    public Map<String,Object> link(Map<String,Object> body, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        long assetId = number(body.get("assetId"), "图片资产");
        String objectType = requireObjectType(text(body.get("objectType")));
        long objectId = number(body.get("objectId"), "关联对象");
        String purpose = body.get("purpose") == null ? "OTHER" : requirePurpose(text(body.get("purpose")));
        boolean primary = Boolean.TRUE.equals(body.get("isPrimary"));
        int sort = sortOrder(body.get("sortOrder"));
        List<String> tags = tags(body.get("tags"));
        String note = note(body.get("note"));
        ensureActiveAsset(assetId);
        lockObject(objectType, objectId);
        if (primary) clearPrimary(objectType, objectId, null);
        try {
            db.update("INSERT INTO image_asset_links(image_asset_id,object_type,object_id,purpose,is_primary,sort_order,tags,note,created_by) VALUES (?,?,?,?,?,?,CAST(? AS JSON),?,?)",
                assetId, objectType, objectId, purpose, primary, sort, encode(tags), note, actor.id());
        } catch (DataIntegrityViolationException ex) { throw new IllegalArgumentException("该图片已关联此对象，或对象已有主图"); }
        queueIndex(assetId);
        long id = lastId();
        audit(actor, request, "image.link.create", "image_asset_link", id, auditLink(assetId, objectType, objectId, purpose, primary));
        return linkDetail(id);
    }

    @Transactional
    public Map<String,Object> updateLink(long id, Map<String,Object> body, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        Map<String,Object> current = activeLink(id);
        String objectType = String.valueOf(current.get("objectType"));
        long objectId = ((Number) current.get("objectId")).longValue();
        lockObject(objectType, objectId);
        String purpose = body.containsKey("purpose") ? requirePurpose(text(body.get("purpose"))) : String.valueOf(current.get("purpose"));
        boolean primary = body.containsKey("isPrimary") ? Boolean.TRUE.equals(body.get("isPrimary")) : Boolean.TRUE.equals(current.get("isPrimary"));
        int sort = body.containsKey("sortOrder") ? sortOrder(body.get("sortOrder")) : ((Number) current.get("sortOrder")).intValue();
        List<String> tags = body.containsKey("tags") ? tags(body.get("tags")) : parseTags(current.get("tags"));
        String note = body.containsKey("note") ? note(body.get("note")) : (String) current.get("note");
        if (primary) clearPrimary(objectType, objectId, id);
        db.update("UPDATE image_asset_links SET purpose=?,is_primary=?,sort_order=?,tags=CAST(? AS JSON),note=? WHERE id=? AND status='ACTIVE'", purpose, primary, sort, encode(tags), note, id);
        queueIndex(((Number) current.get("assetId")).longValue());
        audit(actor, request, primary != Boolean.TRUE.equals(current.get("isPrimary")) ? "image.link.primary" : "image.link.update", "image_asset_link", id,
            Map.of("objectType", objectType, "objectId", objectId, "purpose", purpose, "isPrimary", primary, "sortOrder", sort));
        return linkDetail(id);
    }

    @Transactional
    public Map<String,Object> unlink(long id, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        Map<String,Object> current = activeLink(id);
        lockObject(String.valueOf(current.get("objectType")), ((Number) current.get("objectId")).longValue());
        db.update("UPDATE image_asset_links SET status='INACTIVE',is_primary=FALSE WHERE id=? AND status='ACTIVE'", id);
        queueIndex(((Number) current.get("assetId")).longValue());
        audit(actor, request, "image.link.deactivate", "image_asset_link", id, auditLink(
            ((Number) current.get("assetId")).longValue(), String.valueOf(current.get("objectType")), ((Number) current.get("objectId")).longValue(), String.valueOf(current.get("purpose")), Boolean.TRUE.equals(current.get("isPrimary"))));
        return Map.of("deactivated", true);
    }

    @Transactional
    public Map<String,Object> deactivate(long id, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        Map<String,Object> row = assetRow(id);
        if ("INACTIVE".equals(row.get("status"))) return Map.of("deactivated", true);
        int activeLinks = links(id).stream().filter(link -> "ACTIVE".equals(link.get("status"))).toList().size();
        db.update("UPDATE image_assets SET status='INACTIVE',index_status='PENDING',index_error=NULL,index_requested_at=CURRENT_TIMESTAMP WHERE id=? AND status='ACTIVE'", id);
        db.update("UPDATE image_asset_links SET status='INACTIVE',is_primary=FALSE WHERE image_asset_id=? AND status='ACTIVE'", id);
        audit(actor, request, "image.deactivate", "image_asset", id, Map.of("sourceType", row.get("sourceType"), "deactivatedLinks", activeLinks));
        return Map.of("deactivated", true);
    }

    public StoredImage read(long id) {
        Map<String,Object> row = assetRow(id);
        if (!"ACTIVE".equals(row.get("status"))) throw new IllegalArgumentException("图片资产不存在或已停用");
        if ("EXTERNAL_URL".equals(row.get("sourceType"))) throw new IllegalArgumentException("外部图片由浏览器直接读取");
        String key = Objects.toString(row.get("objectKey"), "");
        try { return new StoredImage(storage.get(key), String.valueOf(row.get("contentType")), String.valueOf(row.get("originalFilename"))); }
        catch (IOException ex) { throw new IllegalStateException("图片文件读取失败"); }
    }

    public StoredImage thumbnail(long id) {
        Map<String,Object> row = assetRow(id);
        String thumbnailKey = Objects.toString(row.get("thumbnailObjectKey"), "");
        if (!thumbnailKey.isBlank()) {
            try { return new StoredImage(storage.get(thumbnailKey), "image/jpeg", "thumbnail.jpg"); }
            catch (IOException ex) { db.update("UPDATE image_assets SET thumbnail_status='FAILED',thumbnail_error='缩略图文件读取失败' WHERE id=?", id); }
        }
        StoredImage original = read(id);
        try {
            return new StoredImage(ImageThumbnailer.jpeg(original.bytes(), 320), "image/jpeg", "thumbnail.jpg");
        } catch (IOException ex) { throw new IllegalStateException("图片缩略图生成失败"); }
    }

    private Map<String,Object> assetRow(long id) {
        List<Map<String,Object>> rows = db.queryForList("SELECT id,source_type sourceType,external_url externalUrl,source_url sourceUrl,object_key objectKey,original_filename originalFilename,content_type contentType,file_size fileSize,width,height,archive_status archiveStatus,archive_error archiveError,archive_attempt_count archiveAttemptCount,archive_requested_at archiveRequestedAt,archive_last_attempt_at archiveLastAttemptAt,archive_completed_at archiveCompletedAt,thumbnail_object_key thumbnailObjectKey,thumbnail_status thumbnailStatus,thumbnail_error thumbnailError,index_status indexStatus,index_version indexVersion,index_error indexError,index_requested_at indexRequestedAt,index_completed_at indexCompletedAt,status,created_by createdBy,created_at createdAt,updated_at updatedAt FROM image_assets WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("图片资产不存在");
        return rows.get(0);
    }
    private List<Map<String,Object>> links(long assetId) {
        List<Map<String,Object>> rows = db.queryForList("SELECT l.id,l.image_asset_id assetId,l.object_type objectType,l.object_id objectId,l.purpose,l.is_primary isPrimary,l.sort_order sortOrder,l.tags,l.note,l.status,l.created_by createdBy,l.created_at createdAt,CASE WHEN l.object_type='SKU' THEN (SELECT s.internal_sku FROM skus s WHERE s.id=l.object_id) ELSE (SELECT g.goods_code FROM warehouse_goods g WHERE g.id=l.object_id) END objectCode,CASE WHEN l.object_type='SKU' THEN (SELECT s.product_name FROM skus s WHERE s.id=l.object_id) ELSE (SELECT g.goods_name FROM warehouse_goods g WHERE g.id=l.object_id) END objectName FROM image_asset_links l WHERE l.image_asset_id=? ORDER BY l.status='ACTIVE' DESC,l.sort_order,l.id", assetId);
        for (Map<String,Object> row : rows) row.put("tags", parseTags(row.get("tags")));
        return rows;
    }
    private Map<String,Object> linkDetail(long id) { Map<String,Object> row=db.queryForMap("SELECT id,image_asset_id assetId,object_type objectType,object_id objectId,purpose,is_primary isPrimary,sort_order sortOrder,tags,note,status,created_by createdBy,created_at createdAt FROM image_asset_links WHERE id=?", id); row.put("tags",parseTags(row.get("tags"))); return row; }
    private Map<String,Object> activeLink(long id) {
        List<Map<String,Object>> rows = db.queryForList("SELECT id,image_asset_id assetId,object_type objectType,object_id objectId,purpose,is_primary isPrimary,sort_order sortOrder,tags,note,status FROM image_asset_links WHERE id=? AND status='ACTIVE'", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("图片关联不存在或已解除");
        Map<String,Object> row = rows.get(0); row.put("tags", parseTags(row.get("tags"))); return row;
    }
    private void ensureActiveAsset(long id) {
        List<Long> found = db.query("SELECT id FROM image_assets WHERE id=? AND status='ACTIVE' FOR UPDATE", (rs, row) -> rs.getLong(1), id);
        if (found.isEmpty()) throw new IllegalArgumentException("图片资产不存在或已停用");
    }
    private void lockObject(String type, long id) {
        String sql = switch (type) {
            case "SKU" -> "SELECT id FROM skus WHERE id=? AND is_deleted=FALSE FOR UPDATE";
            case "WAREHOUSE_GOODS" -> "SELECT id FROM warehouse_goods WHERE id=? AND is_deleted=FALSE FOR UPDATE";
            default -> throw new IllegalArgumentException("图片对象类型无效");
        };
        List<Long> found = db.query(sql, (rs, row) -> rs.getLong(1), id);
        if (found.isEmpty()) throw new IllegalArgumentException("关联对象不存在");
    }
    private void clearPrimary(String objectType, long objectId, Long exceptLinkId) {
        if (exceptLinkId == null) db.update("UPDATE image_asset_links SET is_primary=FALSE WHERE object_type=? AND object_id=? AND status='ACTIVE' AND is_primary=TRUE", objectType, objectId);
        else db.update("UPDATE image_asset_links SET is_primary=FALSE WHERE object_type=? AND object_id=? AND status='ACTIVE' AND is_primary=TRUE AND id<>?", objectType, objectId, exceptLinkId);
    }
    private void queueIndex(long assetId) { db.update("UPDATE image_assets SET index_status='PENDING',index_error=NULL,index_requested_at=CURRENT_TIMESTAMP WHERE id=? AND status='ACTIVE'", assetId); }
    private void manage(AuthUser actor) { auth.requirePermission(actor.id(), "menu.images"); auth.requirePermission(actor.id(), "button.images.manage"); }
    private void view(AuthUser actor) { auth.requirePermission(actor.id(), "menu.images"); }
    private void audit(AuthUser actor, HttpServletRequest request, String action, String resource, long id, Object detail) {
        view(actor);
        try {
            db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,TRUE)",
                actor.id(), actor.username(), action, resource, String.valueOf(id), json.writeValueAsString(detail), request.getRemoteAddr());
        } catch (JsonProcessingException ex) { throw new IllegalStateException("图片操作记录写入失败"); }
    }
    private Map<String,Object> auditLink(long asset, String type, long objectId, String purpose, boolean primary) {
        return Map.of("assetId", asset, "objectType", type, "objectId", objectId, "purpose", purpose, "isPrimary", primary);
    }
    private long lastId() { return Objects.requireNonNull(db.queryForObject("SELECT LAST_INSERT_ID()", Long.class)); }
    private static String extension(String type) { return switch (type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "image/gif" -> ".gif"; default -> throw new IllegalArgumentException("图片类型无效"); }; }
    private static String requireObjectType(String value) { if (value == null || !OBJECT_TYPES.contains(value)) throw new IllegalArgumentException("对象类型仅支持 SKU 或 WAREHOUSE_GOODS"); return value; }
    private static String requirePurpose(String value) { if (value == null || !PURPOSES.contains(value)) throw new IllegalArgumentException("图片用途类型无效"); return value; }
    private static String requireSource(String value) { if (!Set.of("UPLOAD", "EXTERNAL_URL", "ARCHIVED_URL").contains(value)) throw new IllegalArgumentException("图片来源类型无效"); return value; }
    private static String requireStatus(String value) { if (!Set.of("ACTIVE", "INACTIVE").contains(value)) throw new IllegalArgumentException("图片状态无效"); return value; }
    private static int sortOrder(Object value) { if (value == null) return 0; try { int n = Integer.parseInt(String.valueOf(value)); if (n < 0 || n > 1_000_000) throw new IllegalArgumentException("排序值无效"); return n; } catch (NumberFormatException ex) { throw new IllegalArgumentException("排序值无效"); } }
    private static long number(Object value, String label) { try { long n = Long.parseLong(String.valueOf(value)); if (n < 1) throw new NumberFormatException(); return n; } catch (Exception ex) { throw new IllegalArgumentException(label + "无效"); } }
    private static String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private static String note(Object value) { String v = text(value); if (v != null && v.length() > 1000) throw new IllegalArgumentException("备注最多 1000 个字符"); return v; }
    private static List<String> tags(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof Collection<?> items)) throw new IllegalArgumentException("标签必须为数组");
        if (items.size() > 30) throw new IllegalArgumentException("标签最多 30 个");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : items) { String tag = text(item); if (tag == null || tag.isBlank() || tag.length() > 50) throw new IllegalArgumentException("标签不能为空且最多 50 个字符"); result.add(tag); }
        return List.copyOf(result);
    }
    private List<String> parseTags(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> values) return values.stream().map(String::valueOf).toList();
        if (value instanceof byte[] bytes) value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (value instanceof String text) {
            try { return json.readValue(text, json.getTypeFactory().constructCollectionType(List.class, String.class)); }
            catch (Exception ignored) { return List.of(); }
        }
        return List.of();
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalStateException("图片元数据处理失败"); } }
    public record StoredImage(byte[] bytes, String contentType, String originalFilename) { }
}
