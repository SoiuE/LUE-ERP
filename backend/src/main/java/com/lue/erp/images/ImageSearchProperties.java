package com.lue.erp.images;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lue.images.search")
public class ImageSearchProperties {
    private String url = "http://127.0.0.1:8090";
    private String internalToken = "";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 15000;
    private double minimumScore = 0.20;
    private boolean includePackaging;

    public String getUrl() { return url; }
    public void setUrl(String value) { url = value; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String value) { internalToken = value; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int value) { readTimeoutMs = value; }
    public double getMinimumScore() { return minimumScore; }
    public void setMinimumScore(double value) { if (value < -1 || value > 1) throw new IllegalArgumentException("图片相似度阈值无效"); minimumScore = value; }
    public boolean isIncludePackaging() { return includePackaging; }
    public void setIncludePackaging(boolean value) { includePackaging = value; }
}
