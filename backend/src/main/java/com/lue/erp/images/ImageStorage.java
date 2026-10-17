package com.lue.erp.images;

import java.io.IOException;

public interface ImageStorage {
    void put(String objectKey, byte[] content, String contentType) throws IOException;
    byte[] get(String objectKey) throws IOException;
    void delete(String objectKey) throws IOException;
}
