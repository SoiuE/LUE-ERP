package com.lue.erp.images;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class ImageSearchClient {
    private final RestClient client;
    private final ImageSearchProperties properties;

    public ImageSearchClient(@Qualifier("imageSearchRestClient") RestClient client, ImageSearchProperties properties) {
        this.client = client; this.properties = properties;
    }

    public IndexResponse index(long assetId, ImageAssetService.StoredImage image) {
        MultipartBodyBuilder body = multipart(image.bytes(), image.contentType(), image.originalFilename());
        return call(() -> client.post().uri(uri -> uri.path("/internal/index").queryParam("asset_id", assetId).build())
            .header("X-Internal-Token", token()).contentType(MediaType.MULTIPART_FORM_DATA).body(body.build()).retrieve().body(IndexResponse.class));
    }

    public void delete(long assetId) {
        call(() -> client.delete().uri("/internal/index/{id}", assetId).header("X-Internal-Token", token()).retrieve().toBodilessEntity());
    }

    public SearchResponse search(byte[] bytes, String contentType, String filename, int limit) {
        MultipartBodyBuilder body = multipart(bytes, contentType, filename);
        SearchResponse result = call(() -> client.post().uri(uri -> uri.path("/internal/search").queryParam("limit", limit).build())
            .header("X-Internal-Token", token()).contentType(MediaType.MULTIPART_FORM_DATA).body(body.build()).retrieve().body(SearchResponse.class));
        return result == null ? new SearchResponse(List.of(), 0, 0, "unknown") : result;
    }

    public Map<?,?> reset() {
        return call(() -> client.post().uri("/internal/index/reset").header("X-Internal-Token", token()).retrieve().body(Map.class));
    }

    public Map<?,?> backup() {
        return call(() -> client.post().uri("/internal/index/backup").header("X-Internal-Token", token()).retrieve().body(Map.class));
    }

    public Map<?,?> health() {
        return call(() -> client.get().uri("/health").retrieve().body(Map.class));
    }

    private MultipartBodyBuilder multipart(byte[] bytes, String contentType, String filename) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return filename == null ? "image" : filename; } })
            .contentType(MediaType.parseMediaType(contentType));
        return body;
    }
    private String token() {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) throw new ImageSearchUnavailableException("图片搜索服务内部令牌未配置");
        return properties.getInternalToken();
    }
    private <T> T call(java.util.concurrent.Callable<T> action) {
        try { return action.call(); }
        catch (ImageSearchUnavailableException ex) { throw ex; }
        catch (RestClientException ex) { throw new ImageSearchUnavailableException("图片搜索服务暂不可用，请使用手工搜索", ex); }
        catch (Exception ex) { throw new ImageSearchUnavailableException("图片搜索服务调用失败，请使用手工搜索", ex); }
    }

    public record Hit(long assetId, double score) { }
    public record SearchResponse(List<Hit> items, int count, int dimension, String modelVersion) { }
    public record IndexResponse(long assetId, int count, int dimension, String modelVersion) { }
}
