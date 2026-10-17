package com.lue.erp.images;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "lue.images.storage", name = "type", havingValue = "minio")
    ImageStorage minioImageStorage(ImageStorageProperties properties) { return new MinioImageStorage(properties); }

    @Bean
    @ConditionalOnMissingBean(ImageStorage.class)
    ImageStorage unavailableImageStorage() {
        return new ImageStorage() {
            private IOException unavailable() { return new IOException("图片文件存储未配置"); }
            @Override public void put(String objectKey, byte[] content, String contentType) throws IOException { throw unavailable(); }
            @Override public byte[] get(String objectKey) throws IOException { throw unavailable(); }
            @Override public void delete(String objectKey) throws IOException { throw unavailable(); }
        };
    }
}
