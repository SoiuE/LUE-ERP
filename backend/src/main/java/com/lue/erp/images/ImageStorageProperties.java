package com.lue.erp.images;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lue.images.storage")
public class ImageStorageProperties {
    private String type = "none";
    private String endpoint = "http://localhost:9000";
    private String bucket = "lue-erp-images";
    private String accessKey = "";
    private String secretKey = "";
    private String region = "us-east-1";

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
