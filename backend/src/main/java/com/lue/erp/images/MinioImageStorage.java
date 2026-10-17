package com.lue.erp.images;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class MinioImageStorage implements ImageStorage {
    private final MinioClient client;
    private final ImageStorageProperties properties;

    public MinioImageStorage(ImageStorageProperties properties) {
        if (properties.getAccessKey().isBlank() || properties.getSecretKey().isBlank())
            throw new IllegalStateException("MinIO 图片存储凭据未配置");
        this.properties = properties;
        this.client = MinioClient.builder().endpoint(properties.getEndpoint())
            .credentials(properties.getAccessKey(), properties.getSecretKey())
            .region(properties.getRegion()).build();
    }

    @Override public void put(String objectKey, byte[] content, String contentType) throws IOException {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build()))
                throw new IOException("图片存储 bucket 未初始化");
            client.putObject(PutObjectArgs.builder().bucket(properties.getBucket()).object(objectKey)
                .stream(new ByteArrayInputStream(content), content.length, -1).contentType(contentType).build());
        } catch (Exception ex) { throw new IOException("图片文件存储暂不可用"); }
    }

    @Override public byte[] get(String objectKey) throws IOException {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(properties.getBucket()).object(objectKey).build())) {
            return stream.readAllBytes();
        } catch (Exception ex) { throw new IOException("图片文件读取失败"); }
    }

    @Override public void delete(String objectKey) throws IOException {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(properties.getBucket()).object(objectKey).build()); }
        catch (Exception ex) { throw new IOException("图片文件回收失败"); }
    }
}
