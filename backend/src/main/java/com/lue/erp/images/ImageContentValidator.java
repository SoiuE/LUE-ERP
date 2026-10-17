package com.lue.erp.images;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

@Component
public class ImageContentValidator {
    private final long maxFileSize;
    private final long maxPixels;
    private final int maxDimension;

    public ImageContentValidator(@Value("${lue.images.max-file-size:10485760}") long maxFileSize,
                                 @Value("${lue.images.max-pixels:20000000}") long maxPixels,
                                 @Value("${lue.images.max-dimension:8000}") int maxDimension) {
        this.maxFileSize = maxFileSize;
        this.maxPixels = maxPixels;
        this.maxDimension = maxDimension;
    }

    public ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片文件");
        if (file.getSize() > maxFileSize) throw new IllegalArgumentException("图片超过允许的文件大小");
        final byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (Exception ex) { throw new IllegalArgumentException("图片文件读取失败"); }
        return validate(bytes, file.getContentType(), file.getOriginalFilename());
    }

    public ValidatedImage validate(byte[] bytes, String declaredContentType, String originalFilename) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图片内容为空");
        if (bytes.length > maxFileSize) throw new IllegalArgumentException("图片超过允许的文件大小");
        String format = sniffFormat(bytes);
        String declared = declaredContentType == null ? "" : declaredContentType.toLowerCase(Locale.ROOT);
        String contentType = switch (format) {
            case "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> throw new IllegalArgumentException("仅支持 JPEG、PNG 或 GIF 图片");
        };
        if (!declared.isBlank() && !declared.equals(contentType) && !(format.equals("jpeg") && declared.equals("image/jpg")))
            throw new IllegalArgumentException("图片内容与文件类型不匹配");

        int width;
        int height;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无效或格式不支持");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String readerFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (format.equals("jpeg") && !readerFormat.equals("jpeg") && !readerFormat.equals("jpg"))
                    throw new IllegalArgumentException("图片内容无效");
                if (!readerFormat.startsWith(format)) throw new IllegalArgumentException("图片内容无效");
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > maxDimension || height > maxDimension || (long) width * height > maxPixels)
                    throw new IllegalArgumentException("图片像素尺寸超过允许范围");
                if (reader.read(0) == null) throw new IllegalArgumentException("图片内容无效");
            } finally { reader.dispose(); }
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("图片内容无效或无法解码"); }
        String originalName = safeFilename(originalFilename);
        return new ValidatedImage(bytes, contentType, originalName, width, height);
    }

    public String validateExternalUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048)
            throw new IllegalArgumentException("图片 URL 必填且不能超过 2048 个字符");
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) ||
                uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null)
                throw new IllegalArgumentException("图片 URL 仅支持不含用户名/密码的 HTTP 或 HTTPS 地址");
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("图片 URL 格式无效，仅支持 HTTP 或 HTTPS"); }
    }

    private static String sniffFormat(byte[] data) {
        if (data.length >= 3 && (data[0] & 255) == 0xff && (data[1] & 255) == 0xd8 && (data[2] & 255) == 0xff) return "jpeg";
        if (data.length >= 8 && (data[0] & 255) == 0x89 && data[1] == 0x50 && data[2] == 0x4e && data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a && data[6] == 0x1a && data[7] == 0x0a) return "png";
        if (data.length >= 6) {
            String header = new String(data, 0, 6, StandardCharsets.US_ASCII);
            if (header.equals("GIF87a") || header.equals("GIF89a")) return "gif";
        }
        throw new IllegalArgumentException("仅支持内容有效的 JPEG、PNG 或 GIF 图片");
    }

    private static String safeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "image";
        String leaf = raw.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (leaf.isBlank()) return "image";
        return leaf.length() > 255 ? leaf.substring(leaf.length() - 255) : leaf;
    }

    public record ValidatedImage(byte[] bytes, String contentType, String originalFilename, int width, int height) { }
}
