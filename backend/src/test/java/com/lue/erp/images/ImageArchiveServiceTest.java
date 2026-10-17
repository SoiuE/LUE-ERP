package com.lue.erp.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageArchiveServiceTest {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p7sAAAAASUVORK5CYII=");
    @Test void repeatedRequestQueuesOnlyOneWorker() {
        FakeJdbc db = new FakeJdbc("NOT_REQUESTED");
        AtomicInteger submissions = new AtomicInteger();
        ImageArchiveService service = service(db, command -> submissions.incrementAndGet(), failingDownloader());
        AuthUser user = new AuthUser(7, "operator", "Operator", List.of(), List.of());

        service.request(1, user, new MockHttpServletRequest());
        service.request(1, user, new MockHttpServletRequest());

        assertEquals(1, submissions.get());
        assertEquals("PENDING", db.asset.get("archiveStatus"));
    }

    @Test void failedDownloadKeepsExternalUrlAndRecordsReadableFailure() {
        FakeJdbc db = new FakeJdbc("PENDING");
        ImageArchiveService service = service(db, Runnable::run, failingDownloader());

        service.process(1);

        assertEquals("EXTERNAL_URL", db.asset.get("sourceType"));
        assertEquals("https://example.com/image.png", db.asset.get("externalUrl"));
        assertEquals("FAILED", db.asset.get("archiveStatus"));
        assertEquals("download failed", db.asset.get("archiveError"));
    }

    @Test void successfulArchiveStoresOriginalAndThumbnailWhileKeepingSourceUrl() {
        FakeJdbc db = new FakeJdbc("PENDING");
        MemoryStorage storage = new MemoryStorage();
        ExternalImageDownloader downloader = new ExternalImageDownloader(new ImageArchiveProperties()) {
            @Override public DownloadedImage download(String rawUrl) {
                return new DownloadedImage(PNG, "image/png", "remote.png", rawUrl);
            }
        };
        AuthService auth = new AuthService(null, null) { @Override public void requirePermission(long userId, String permission) { } };
        ImageArchiveService service = new ImageArchiveService(db, auth, new ObjectMapper(), storage,
            new ImageContentValidator(1024, 100, 10), downloader, Runnable::run);

        service.process(1);

        assertEquals("ARCHIVED_URL", db.asset.get("sourceType"));
        assertEquals("https://example.com/image.png", db.asset.get("sourceUrl"));
        assertEquals(null, db.asset.get("externalUrl"));
        assertEquals("SUCCEEDED", db.asset.get("archiveStatus"));
        assertEquals("READY", db.asset.get("thumbnailStatus"));
        assertEquals(2, storage.files.size());
    }

    @Test void thumbnailStorageFailureDoesNotDiscardArchivedOriginal() {
        FakeJdbc db = new FakeJdbc("PENDING");
        MemoryStorage storage = new MemoryStorage() {
            @Override public void put(String key, byte[] content, String type) throws IOException {
                if (key.contains("/thumbnails/")) throw new IOException("thumbnail unavailable");
                super.put(key, content, type);
            }
        };
        ExternalImageDownloader downloader = new ExternalImageDownloader(new ImageArchiveProperties()) {
            @Override public DownloadedImage download(String rawUrl) { return new DownloadedImage(PNG, "image/png", "remote.png", rawUrl); }
        };
        AuthService auth = new AuthService(null, null) { @Override public void requirePermission(long userId, String permission) { } };
        ImageArchiveService service = new ImageArchiveService(db, auth, new ObjectMapper(), storage,
            new ImageContentValidator(1024, 100, 10), downloader, Runnable::run);

        service.process(1);

        assertEquals("SUCCEEDED", db.asset.get("archiveStatus"));
        assertEquals("FAILED", db.asset.get("thumbnailStatus"));
        assertEquals(1, storage.files.size());
    }

    private static ImageArchiveService service(FakeJdbc db, java.util.concurrent.Executor executor, ExternalImageDownloader downloader) {
        AuthService auth = new AuthService(null, null) {
            @Override public void requirePermission(long userId, String permission) { }
        };
        return new ImageArchiveService(db, auth, new ObjectMapper(), null, null, downloader, executor);
    }

    private static ExternalImageDownloader failingDownloader() {
        return new ExternalImageDownloader(new ImageArchiveProperties()) {
            @Override public DownloadedImage download(String rawUrl) throws IOException { throw new IOException("download failed"); }
        };
    }

    private static final class FakeJdbc extends JdbcTemplate {
        final Map<String,Object> asset = new HashMap<>();
        FakeJdbc(String archiveStatus) {
            asset.put("id", 1L); asset.put("sourceType", "EXTERNAL_URL"); asset.put("externalUrl", "https://example.com/image.png");
            asset.put("status", "ACTIVE"); asset.put("archiveStatus", archiveStatus); asset.put("archiveAttemptCount", 0);
            asset.put("thumbnailStatus", "NOT_GENERATED"); asset.put("indexStatus", "NOT_INDEXED");
        }
        @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
            if (sql.startsWith("SELECT id,source_type")) return List.of(new HashMap<>(asset));
            if (sql.startsWith("SELECT u.id")) return List.of();
            throw new AssertionError("Unexpected query: " + sql);
        }
        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO operation_logs")) return 1;
            if (sql.startsWith("UPDATE image_assets SET source_type='ARCHIVED_URL'")) {
                asset.put("sourceType", "ARCHIVED_URL"); asset.put("sourceUrl", args[0]); asset.put("externalUrl", null);
                asset.put("objectKey", args[1]); asset.put("archiveStatus", "SUCCEEDED");
                asset.put("thumbnailStatus", args[8]); asset.put("indexStatus", "PENDING"); return 1;
            }
            if (sql.contains("archive_status='PENDING'") && sql.contains("archive_requested_by")) {
                if (List.of("PENDING", "PROCESSING").contains(asset.get("archiveStatus"))) return 0;
                asset.put("archiveStatus", "PENDING"); return 1;
            }
            if (sql.contains("archive_status='FAILED'")) {
                asset.put("archiveStatus", "FAILED"); asset.put("archiveError", args[0]); return 1;
            }
            if (sql.contains("SET archive_status='PROCESSING'")) {
                if (!"PENDING".equals(asset.get("archiveStatus"))) return 0;
                asset.put("archiveStatus", "PROCESSING"); return 1;
            }
            throw new AssertionError("Unexpected update: " + sql);
        }
    }

    private static class MemoryStorage implements ImageStorage {
        final Map<String,byte[]> files = new HashMap<>();
        @Override public void put(String key, byte[] content, String type) throws IOException { files.put(key, content); }
        @Override public byte[] get(String key) throws IOException { byte[] value=files.get(key); if(value==null)throw new IOException("missing"); return value; }
        @Override public void delete(String key) { files.remove(key); }
    }
}
