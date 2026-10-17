package com.lue.erp.images;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ImageSearchProperties.class)
public class ImageSearchConfiguration {
    @Bean("imageSearchRestClient")
    RestClient imageSearchRestClient(ImageSearchProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        return RestClient.builder().baseUrl(properties.getUrl()).requestFactory(requestFactory).build();
    }
}
