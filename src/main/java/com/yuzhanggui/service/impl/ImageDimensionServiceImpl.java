package com.yuzhanggui.service.impl;

import com.yuzhanggui.service.ImageDimensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads public CDN images once and extracts their real dimensions. */
@Slf4j
@Service
public class ImageDimensionServiceImpl implements ImageDimensionService {

    private static final int MAX_DOWNLOAD_SIZE = 10 * 1024 * 1024;
    private static final int MAX_CACHE_ENTRIES = 512;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<String, ImageDimensions> cache = new ConcurrentHashMap<>();

    @Override
    public ImageDimensions resolve(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return ImageDimensions.unknown();
        }

        String url = imageUrl.trim();
        ImageDimensions cached = cache.get(url);
        if (cached != null) {
            return cached;
        }

        ImageDimensions dimensions;
        try {
            dimensions = readRemoteDimensions(url);
        } catch (Exception e) {
            log.warn("读取图片尺寸失败，将使用未知尺寸: url={}, reason={}", url, e.getMessage());
            dimensions = ImageDimensions.unknown();
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(url, dimensions);
        return dimensions;
    }

    private ImageDimensions readRemoteDimensions(String imageUrl) throws Exception {
        URI imageUri = validatePublicImageUri(imageUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(imageUri)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("HTTP " + response.statusCode());
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > MAX_DOWNLOAD_SIZE) {
            response.body().close();
            throw new IllegalArgumentException("图片超过10MB");
        }
        try (InputStream input = response.body()) {
            byte[] data = input.readNBytes(MAX_DOWNLOAD_SIZE + 1);
            if (data.length > MAX_DOWNLOAD_SIZE) {
                throw new IllegalArgumentException("图片超过10MB");
            }
            return readDimensions(data);
        }
    }

    static ImageDimensions readDimensions(byte[] data) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IllegalArgumentException("不是可识别的图片");
        }
        return new ImageDimensions(image.getWidth(), image.getHeight());
    }

    private URI validatePublicImageUri(String imageUrl) throws Exception {
        if (imageUrl.startsWith("//")) {
            imageUrl = "https:" + imageUrl;
        }
        URI uri = URI.create(imageUrl);
        if (uri.getUserInfo() != null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("图片地址必须是公网HTTP或HTTPS地址");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] raw = address.getAddress();
            boolean uniqueLocalIpv6 = raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalIpv6) {
                throw new IllegalArgumentException("图片地址必须解析到公网地址");
            }
        }
        return uri;
    }
}
