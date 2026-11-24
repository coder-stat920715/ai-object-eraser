package com.example.objecteraser.service.provider;

import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.exception.InpaintingException;
import com.example.objecteraser.util.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * Calls a Stability-compatible {@code POST /v2beta/stable-image/edit/erase} endpoint
 * (api.stability.ai or stability.comfy.org - configurable through {@code ai.eraser.cloud.base-url}).
 */
@Component
@ConditionalOnProperty(name = "ai.eraser.provider", havingValue = "cloud")
public class CloudEraseInpaintingProvider implements InpaintingProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudEraseInpaintingProvider.class);
    static final String ERASE_PATH = "/v2beta/stable-image/edit/erase";

    private final EraserProperties.Cloud cfg;
    private final RestClient client;

    public CloudEraseInpaintingProvider(EraserProperties props) {
        this.cfg = props.cloud();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(cfg.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(stripTrailingSlash(cfg.baseUrl()))
                .requestFactory(factory)
                .build();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            log.warn("ai.eraser.provider=cloud but ai.eraser.cloud.api-key is empty - erase calls will fail until it is set.");
        } else {
            log.info("Cloud inpainting provider ready, base-url={}", cfg.baseUrl());
        }
    }

    @Override
    public String name() {
        return "cloud";
    }

    @Override
    public BufferedImage inpaint(BufferedImage image, BufferedImage mask) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new InpaintingException("The cloud provider has no API key. Set ai.eraser.cloud.api-key (or STABILITY_API_KEY).");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", part(ImageUtils.encode(image, "png"), "image.png"));
        body.add("mask", part(ImageUtils.encode(mask, "png"), "mask.png"));
        body.add("grow_mask", String.valueOf(cfg.growMask()));
        body.add("output_format", cfg.outputFormat());

        byte[] response;
        try {
            response = client.post()
                    .uri(ERASE_PATH)
                    .header(cfg.apiKeyHeader(), (cfg.apiKeyPrefix() == null ? "" : cfg.apiKeyPrefix()) + cfg.apiKey())
                    .accept(MediaType.parseMediaType("image/*"))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "...";
            }
            String hint = (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                    ? " (check ai.eraser.cloud.api-key / api-key-header / api-key-prefix)" : "";
            throw new InpaintingException("Cloud erase API returned HTTP " + e.getStatusCode().value() + hint + ": " + detail, e);
        } catch (RestClientException e) {
            throw new InpaintingException("Could not reach the cloud erase API at " + cfg.baseUrl() + ": " + e.getMessage(), e);
        }
        if (response == null || response.length == 0) {
            throw new InpaintingException("The cloud erase API returned an empty response.");
        }
        try {
            BufferedImage result = ImageIO.read(new ByteArrayInputStream(response));
            if (result == null) {
                throw new InpaintingException("The cloud erase API returned data that is not a decodable image "
                        + "(output-format '" + cfg.outputFormat() + "' may not be readable by the JVM; try png).");
            }
            return result;
        } catch (IOException e) {
            throw new InpaintingException("Could not decode the cloud erase API response.", e);
        }
    }

    private static ByteArrayResource part(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
