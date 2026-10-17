package com.lue.erp.images;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageThumbnailerTest {
    @Test void createsIndependentJpegThumbnail() throws Exception {
        byte[] source = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p7sAAAAASUVORK5CYII=");
        byte[] thumbnail = ImageThumbnailer.jpeg(source, 320);
        assertTrue(thumbnail.length > 2);
        assertEquals(0xff, thumbnail[0] & 255);
        assertEquals(0xd8, thumbnail[1] & 255);
    }
}
