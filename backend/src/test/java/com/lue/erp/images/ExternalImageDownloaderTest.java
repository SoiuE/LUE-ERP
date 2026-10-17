package com.lue.erp.images;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalImageDownloaderTest {
    @Test void rejectsPrivateLoopbackLinkLocalMetadataAndUniqueLocalAddresses() throws Exception {
        assertTrue(ExternalImageDownloader.isForbiddenAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(ExternalImageDownloader.isForbiddenAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(ExternalImageDownloader.isForbiddenAddress(InetAddress.getByName("169.254.169.254")));
        assertTrue(ExternalImageDownloader.isForbiddenAddress(InetAddress.getByName("100.100.100.200")));
        assertTrue(ExternalImageDownloader.isForbiddenAddress(InetAddress.getByName("fc00::1")));
        assertFalse(ExternalImageDownloader.isForbiddenAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test void archiveUriRejectsCredentialsFragmentsAndUnsupportedSchemes() {
        assertThrows(Exception.class, () -> ExternalImageDownloader.validateUri("file:///etc/passwd"));
        assertThrows(Exception.class, () -> ExternalImageDownloader.validateUri("https://user:secret@example.com/image.jpg"));
        assertThrows(Exception.class, () -> ExternalImageDownloader.validateUri("https://example.com/image.jpg#fragment"));
    }
}
