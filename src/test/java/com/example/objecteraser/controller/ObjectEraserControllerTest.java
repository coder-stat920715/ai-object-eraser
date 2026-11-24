package com.example.objecteraser.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ObjectEraserControllerTest {

    private static final int W = 160;
    private static final int H = 120;

    @Autowired
    private MockMvc mvc;

    // ------------------------------------------------------------------ fixtures

    private static byte[] photoWithObject() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(90, 160, 90));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.fillOval(60, 40, 40, 40);
        g.dispose();
        return png(img);
    }

    private static byte[] maskOverObject() throws Exception {
        BufferedImage m = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = m.createGraphics();
        g.setColor(Color.WHITE);
        g.fillOval(60, 40, 40, 40);
        g.dispose();
        return png(m);
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile part(String name, byte[] data) {
        return new MockMultipartFile(name, name + ".png", "image/png", data);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void infoReportsActiveProvider() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("demo"))
                .andExpect(jsonPath("$.defaultGrowMask").value(6));
    }

    @Test
    void syncEraseReturnsProcessedPngWithSameDimensionsAndRemovesTheObject() throws Exception {
        MvcResult res = mvc.perform(multipart("/api/v1/erase")
                        .file(part("image", photoWithObject()))
                        .file(part("mask", maskOverObject()))
                        .param("growMask", "4"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Eraser-Provider", "demo"))
                .andReturn();

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(res.getResponse().getContentAsByteArray()));
        assertThat(out.getWidth()).isEqualTo(W);
        assertThat(out.getHeight()).isEqualTo(H);
        Color centre = new Color(out.getRGB(80, 60));
        assertThat(centre.getRed()).isBetween(80, 100);
        assertThat(centre.getGreen()).isBetween(150, 170);
    }

    @Test
    void syncEraseCanReturnJpeg() throws Exception {
        mvc.perform(multipart("/api/v1/erase")
                        .file(part("image", photoWithObject()))
                        .file(part("mask", maskOverObject()))
                        .param("format", "jpeg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void missingMaskPartYieldsProblemDetail() throws Exception {
        mvc.perform(multipart("/api/v1/erase").file(part("image", photoWithObject())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void garbageUploadYieldsProblemDetail() throws Exception {
        mvc.perform(multipart("/api/v1/erase")
                        .file(new MockMultipartFile("image", "x.png", "image/png", "definitely not a picture".getBytes()))
                        .file(part("mask", maskOverObject())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid image or mask"))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void emptyMaskYieldsProblemDetail() throws Exception {
        byte[] blank = png(new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY));
        mvc.perform(multipart("/api/v1/erase")
                        .file(part("image", photoWithObject()))
                        .file(part("mask", blank)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("mask is empty")));
    }

    @Test
    void outOfRangeGrowMaskIsRejected() throws Exception {
        mvc.perform(multipart("/api/v1/erase")
                        .file(part("image", photoWithObject()))
                        .file(part("mask", maskOverObject()))
                        .param("growMask", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void asyncJobCompletesAndResultCanBeDownloaded() throws Exception {
        MvcResult created = mvc.perform(multipart("/api/v1/erase/async")
                        .file(part("image", photoWithObject()))
                        .file(part("mask", maskOverObject())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.streamUrl").isNotEmpty())
                .andReturn();

        String body = created.getResponse().getContentAsString();
        String jobId = body.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        byte[] png = null;
        for (int i = 0; i < 100 && png == null; i++) {
            MvcResult res = mvc.perform(get("/api/v1/erase/result/" + jobId)).andReturn();
            if (res.getResponse().getStatus() == 200) {
                png = res.getResponse().getContentAsByteArray();
            } else {
                assertThat(res.getResponse().getStatus()).isEqualTo(409);   // still running
                Thread.sleep(100);
            }
        }
        assertThat(png).as("job finished within 10 s").isNotNull();
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isEqualTo(W);
    }

    @Test
    void unknownJobIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/erase/result/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/erase/stream/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesTheFrontend() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Object Eraser")));
    }
}
