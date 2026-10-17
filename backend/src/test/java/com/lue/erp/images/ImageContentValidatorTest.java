package com.lue.erp.images;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ImageContentValidatorTest {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p7sAAAAASUVORK5CYII=");

    @Test void acceptsImageBySignatureAndRecordsDimensions() {
        var file = new MockMultipartFile("file", "../photo.png", "image/png", PNG);
        var image = new ImageContentValidator(1024, 100, 10).validate(file);
        assertEquals("image/png", image.contentType());
        assertEquals("photo.png", image.originalFilename());
        assertEquals(1, image.width());
        assertEquals(1, image.height());
    }

    @Test void rejectsMismatchedContentTypeAndOversizedDimensions() {
        var file = new MockMultipartFile("file", "photo.png", "image/jpeg", PNG);
        assertThrows(IllegalArgumentException.class, () -> new ImageContentValidator(1024, 100, 10).validate(file));
        var tooSmallMax = new MockMultipartFile("file", "photo.png", "image/png", PNG);
        assertThrows(IllegalArgumentException.class, () -> new ImageContentValidator(1024, 100, 0).validate(tooSmallMax));
    }

    @Test void externalUrlMustUseHttpOrHttpsAndCannotContainUserInfo() {
        var validator = new ImageContentValidator(1024, 100, 10);
        assertEquals("https://example.test/a.png", validator.validateExternalUrl("https://example.test/a.png"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateExternalUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateExternalUrl("https://user:password@example.test/a.png"));
    }

    @Test void downloadedBytesStillRequireMatchingMimeSignatureAndPixelLimits() {
        var validator = new ImageContentValidator(1024, 100, 10);
        assertEquals("image/png", validator.validate(PNG, "image/png", "remote.png").contentType());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(PNG, "image/jpeg", "remote.jpg"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new byte[2048], "image/png", "large.png"));
    }
}
