package com.lue.erp.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ImageArchiveService {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final ObjectMapper json;
    private final ImageStorage storage;
    private final ImageContentValidator validator;
    private final ExternalImageDownloader downloader;
    private final Executor executor;

    public ImageArchiveService(JdbcTemplate db, AuthService auth, ObjectMapper json, ImageStorage storage,
            ImageContentValidator validator, ExternalImageDownloader downloader,
            @Qualifier("imageArchiveExecutor") Executor executor) {
        this.db = db; this.auth = auth; this.json = json; this.storage = storage;
        this.validator = validator; this.downloader = downloader; this.executor = executor;
    }

    @Transactional
    public Map<String,Object> request(long id, AuthUser actor, HttpServletRequest request) {
        manage(actor);
        Map<String,Object> asset = state(id);
        if (!"ACTIVE".equals(asset.get("status"))) throw new IllegalArgumentException("图片资产不存在或已停用");
        if ("ARCHIVED_URL".equals(asset.get("sourceType"))) return asset;
        if (!"EXTERNAL_URL".equals(asset.get("sourceType"))) throw new IllegalArgumentException("只有外链图片可以归档");
        String status = String.valueOf(asset.get("archiveStatus"));
        if (!List.of("PENDING", "PROCESSING").contains(status)) {
            int queued = db.update("UPDATE image_assets SET archive_status='PENDING',archive_error=NULL,archive_requested_by=?,archive_requested_at=CURRENT_TIMESTAMP WHERE id=? AND source_type='EXTERNAL_URL' AND status='ACTIVE' AND archive_status NOT IN ('PENDING','PROCESSING')", actor.id(), id);
            audit(actor.id(), actor.username(), request.getRemoteAddr(), "image.archive.request", id, Map.of("retry", "FAILED".equals(status)));
            if (queued == 1) submitAfterCommit(id);
        }
        return state(id);
    }

    @Scheduled(fixedDelayString = "${lue.images.archive.poll-delay-ms:3000}")
    public void dispatchPending() {
        db.update("UPDATE image_assets SET archive_status='PENDING',archive_error='上次处理异常中断，已自动重新排队' WHERE archive_status='PROCESSING' AND archive_last_attempt_at < DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 5 MINUTE)");
        for (Long id : db.query("SELECT id FROM image_assets WHERE archive_status='PENDING' AND status='ACTIVE' ORDER BY archive_requested_at,id LIMIT 20", (rs, row) -> rs.getLong(1))) submit(id);
    }

    private void submit(long id) {
        try { executor.execute(() -> process(id)); }
        catch (RejectedExecutionException ignored) { /* Persistent PENDING state is picked up by the next bounded poll. */ }
    }

    private void submitAfterCommit(long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { submit(id); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { submit(id); }
        });
    }

    void process(long id) {
        if (db.update("UPDATE image_assets SET archive_status='PROCESSING',archive_attempt_count=archive_attempt_count+1,archive_last_attempt_at=CURRENT_TIMESTAMP,archive_error=NULL WHERE id=? AND source_type='EXTERNAL_URL' AND status='ACTIVE' AND archive_status='PENDING'", id) != 1) return;
        Map<String,Object> asset = state(id);
        String sourceUrl = Objects.toString(asset.get("externalUrl"), "");
        String objectKey = null, thumbnailKey = null;
        try {
            ExternalImageDownloader.DownloadedImage downloaded = downloader.download(sourceUrl);
            ImageContentValidator.ValidatedImage image = validator.validate(downloaded.bytes(), downloaded.contentType(), downloaded.filename());
            objectKey = "images/archived/" + YearMonth.now() + "/" + UUID.randomUUID() + extension(image.contentType());
            storage.put(objectKey, image.bytes(), image.contentType());

            String thumbnailStatus = "READY", thumbnailError = null;
            try {
                byte[] thumbnail = ImageThumbnailer.jpeg(image.bytes(), 320);
                thumbnailKey = "images/thumbnails/" + YearMonth.now() + "/" + UUID.randomUUID() + ".jpg";
                storage.put(thumbnailKey, thumbnail, "image/jpeg");
            } catch (Exception ex) {
                if (thumbnailKey != null) try { storage.delete(thumbnailKey); } catch (IOException ignored) { }
                thumbnailStatus = "FAILED";
                thumbnailError = readable(ex, "缩略图生成失败");
                thumbnailKey = null;
            }

            int updated = db.update("UPDATE image_assets SET source_type='ARCHIVED_URL',source_url=?,external_url=NULL,object_key=?,original_filename=?,content_type=?,file_size=?,width=?,height=?,archive_status='SUCCEEDED',archive_error=NULL,archive_completed_at=CURRENT_TIMESTAMP,thumbnail_object_key=?,thumbnail_status=?,thumbnail_error=?,index_status='PENDING',index_error=NULL,index_requested_at=CURRENT_TIMESTAMP WHERE id=? AND source_type='EXTERNAL_URL' AND archive_status='PROCESSING'",
                sourceUrl, objectKey, image.originalFilename(), image.contentType(), image.bytes().length, image.width(), image.height(), thumbnailKey, thumbnailStatus, thumbnailError, id);
            if (updated != 1) throw new IOException("归档状态已变化");
            auditRequester(id, "image.archive.success", Map.of("contentType", image.contentType(), "fileSize", image.bytes().length, "thumbnailStatus", thumbnailStatus));
        } catch (Exception ex) {
            if (thumbnailKey != null) try { storage.delete(thumbnailKey); } catch (IOException ignored) { }
            if (objectKey != null) try { storage.delete(objectKey); } catch (IOException ignored) { }
            db.update("UPDATE image_assets SET archive_status='FAILED',archive_error=?,archive_completed_at=CURRENT_TIMESTAMP WHERE id=? AND source_type='EXTERNAL_URL' AND archive_status='PROCESSING'", readable(ex, "外链归档失败"), id);
            auditRequester(id, "image.archive.failed", Map.of("error", readable(ex, "外链归档失败")));
        }
    }

    private Map<String,Object> state(long id) {
        List<Map<String,Object>> rows = db.queryForList("SELECT id,source_type sourceType,external_url externalUrl,status,archive_status archiveStatus,archive_error archiveError,archive_attempt_count archiveAttemptCount,archive_requested_at archiveRequestedAt,archive_last_attempt_at archiveLastAttemptAt,archive_completed_at archiveCompletedAt,thumbnail_status thumbnailStatus,thumbnail_error thumbnailError,index_status indexStatus,index_version indexVersion,index_error indexError FROM image_assets WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("图片资产不存在");
        return rows.get(0);
    }
    private void auditRequester(long id, String action, Map<String,Object> detail) {
        List<Map<String,Object>> users = db.queryForList("SELECT u.id,u.username FROM image_assets a JOIN users u ON u.id=a.archive_requested_by WHERE a.id=?", id);
        if (!users.isEmpty()) {
            try { audit(((Number) users.get(0).get("id")).longValue(), String.valueOf(users.get(0).get("username")), null, action, id, detail); }
            catch (IllegalStateException ignored) { /* Asset processing state remains the durable operational record. */ }
        }
    }
    private void audit(long userId, String username, String ip, String action, long id, Map<String,Object> detail) {
        try { db.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,?)",
            userId, username, action, "image_asset", String.valueOf(id), json.writeValueAsString(detail), ip, !action.endsWith("failed")); }
        catch (Exception ex) { throw new IllegalStateException("图片归档操作记录写入失败", ex); }
    }
    private void manage(AuthUser actor) { auth.requirePermission(actor.id(), "menu.images"); auth.requirePermission(actor.id(), "button.images.manage"); }
    private static String readable(Exception ex, String fallback) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) message = fallback;
        message = message.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
    private static String extension(String type) { return switch (type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "image/gif" -> ".gif"; default -> throw new IllegalArgumentException("图片类型无效"); }; }
}
