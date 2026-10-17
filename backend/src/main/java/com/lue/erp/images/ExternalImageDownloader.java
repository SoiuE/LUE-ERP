package com.lue.erp.images;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class ExternalImageDownloader {
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/gif");
    private final ImageArchiveProperties properties;
    private final OkHttpClient client;

    public ExternalImageDownloader(ImageArchiveProperties properties) {
        this.properties = properties;
        Dns safeDns = hostname -> validateAddresses(hostname, InetAddress.getAllByName(hostname));
        client = new OkHttpClient.Builder()
            .dns(safeDns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
            .readTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
            .callTimeout(properties.getCallTimeoutMs(), TimeUnit.MILLISECONDS)
            .build();
    }

    public DownloadedImage download(String rawUrl) throws IOException {
        URI current = validateUri(rawUrl);
        for (int redirect = 0; redirect <= properties.getMaxRedirects(); redirect++) {
            validateAddresses(current.getHost(), InetAddress.getAllByName(current.getHost()));
            Request request = new Request.Builder().url(current.toASCIIString())
                .header("Accept", "image/jpeg,image/png,image/gif")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "LUE-ERP-Image-Archiver/1.0")
                .get().build();
            try (Response response = client.newCall(request).execute()) {
                if (isRedirect(response.code())) {
                    if (redirect == properties.getMaxRedirects()) throw new IOException("外链重定向次数超过限制");
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) throw new IOException("外链重定向缺少目标地址");
                    current = validateUri(current.resolve(location).toASCIIString());
                    continue;
                }
                if (!response.isSuccessful()) throw new IOException("外链返回 HTTP " + response.code());
                String contentType = normalizedType(response.header("Content-Type"));
                long declaredLength = parseLength(response.header("Content-Length"));
                if (declaredLength > properties.getMaxFileSize()) throw new IOException("外链图片超过允许的文件大小");
                ResponseBody body = response.body();
                if (body == null) throw new IOException("外链响应没有图片内容");
                byte[] bytes = readBounded(body.byteStream(), properties.getMaxFileSize());
                return new DownloadedImage(bytes, contentType, filename(current, contentType), current.toASCIIString());
            }
        }
        throw new IOException("外链重定向次数超过限制");
    }

    static URI validateUri(String raw) throws IOException {
        try {
            URI uri = URI.create(raw).normalize();
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) ||
                uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IOException("归档地址仅支持不含凭据和片段的 HTTP/HTTPS URL");
            int port = uri.getPort();
            if (port == 0 || port > 65535) throw new IOException("归档地址端口无效");
            return uri;
        } catch (IllegalArgumentException ex) { throw new IOException("归档地址格式无效"); }
    }

    static List<InetAddress> validateAddresses(String hostname, InetAddress[] addresses) throws UnknownHostException {
        if (addresses.length == 0) throw new UnknownHostException("外链域名没有可用地址");
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) throw new UnknownHostException("外链目标不是可公开访问的地址");
        }
        return List.of(addresses);
    }

    static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() ||
            address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            return a == 0 || a == 10 || a == 127 || (a == 100 && b >= 64 && b <= 127) ||
                (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
                (a == 198 && (b == 18 || b == 19)) || a >= 224;
        }
        if (address instanceof Inet6Address ipv6) {
            int first = bytes[0] & 255;
            return (first & 0xfe) == 0xfc || ipv6.isIPv4CompatibleAddress();
        }
        return true;
    }

    private static boolean isRedirect(int code) { return code == 301 || code == 302 || code == 303 || code == 307 || code == 308; }
    private static String normalizedType(String value) throws IOException {
        String type = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new IOException("外链响应不是支持的 JPEG、PNG 或 GIF 图片");
        return type;
    }
    private static long parseLength(String value) throws IOException {
        if (value == null || value.isBlank()) return -1;
        try { long length = Long.parseLong(value); if (length < 0) throw new NumberFormatException(); return length; }
        catch (NumberFormatException ex) { throw new IOException("外链响应长度无效"); }
    }
    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            total += read;
            if (total > maximum) throw new IOException("外链图片超过允许的文件大小");
            output.write(buffer, 0, read);
        }
        if (total == 0) throw new IOException("外链图片内容为空");
        return output.toByteArray();
    }
    private static String filename(URI uri, String type) {
        String path = uri.getPath();
        String leaf = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        leaf = java.net.URLDecoder.decode(leaf, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}\\\\/]", "").trim();
        if (leaf.isBlank() || leaf.length() > 200) leaf = "archived-image";
        String extension = switch (type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".gif"; };
        return leaf.contains(".") ? leaf : leaf + extension;
    }

    public record DownloadedImage(byte[] bytes, String contentType, String filename, String finalUrl) { }
}
