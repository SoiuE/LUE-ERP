package com.lue.erp.images;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lue.images.archive")
public class ImageArchiveProperties {
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 7000;
    private int callTimeoutMs = 12000;
    private long maxFileSize = 10 * 1024 * 1024;
    private int maxRedirects = 3;
    private int workerCount = 2;
    private int queueCapacity = 20;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = positive(value, "连接超时"); }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int value) { readTimeoutMs = positive(value, "读取超时"); }
    public int getCallTimeoutMs() { return callTimeoutMs; }
    public void setCallTimeoutMs(int value) { callTimeoutMs = positive(value, "总超时"); }
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long value) { if (value < 1024 || value > 50L * 1024 * 1024) throw new IllegalArgumentException("归档大小限制无效"); maxFileSize = value; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int value) { if (value < 0 || value > 5) throw new IllegalArgumentException("归档重定向限制无效"); maxRedirects = value; }
    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int value) { if (value < 1 || value > 8) throw new IllegalArgumentException("归档线程数无效"); workerCount = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { if (value < 1 || value > 200) throw new IllegalArgumentException("归档队列容量无效"); queueCapacity = value; }
    private static int positive(int value, String name) { if (value < 100 || value > 60000) throw new IllegalArgumentException(name + "配置无效"); return value; }
}
