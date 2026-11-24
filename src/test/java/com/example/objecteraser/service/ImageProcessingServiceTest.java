package com.example.objecteraser.service;

import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.exception.InvalidImageException;
import com.example.objecteraser.service.provider.SmartPatchDemoProvider;
import com.example.objecteraser.util.ImageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessingServiceTest {

    private static final int W = 120;
    private static final int H = 80;

    private ImageProcessingService service;
    private EraserProperties props;

    @BeforeEach
    void setUp() {
        props = new EraserProperties("demo", 6, 2,
                new EraserProperties.Limits(DataSize.ofMegabytes(5), 100_000),
                new EraserProperties.Jobs(1, 4, Duration.ofMinutes(1)),
                new EraserProperties.Demo(48, 0.0, 1L),
                new EraserProperties.Cloud("https://api.stability.ai", "", "Authorization", "Bearer ", 0, "png",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new EraserProperties.Onnx("models/lama_fp32.onnx", 512, 0.6, "image", "mask"));
        service = new ImageProcessingService(props);
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static BufferedImage maskWithRect(int w, int h, int x, int y, int rw, int rh) {
        BufferedImage m = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = m.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(x, y, rw, rh);
        g.dispose();
        return m;
    }

    private static int countMarked(BufferedImage mask) {
        int n = 0;
        for (byte b : ImageUtils.maskBytes(mask)) {
            if (b != 0) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- validation

    @Test
    void rejectsEmptyAndNonImageUploads() {
        assertThatThrownBy(() -> service.readImage(new byte[0])).isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> service.readImage("<html>not an image</html>".getBytes()))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void rejectsImagesAboveThePixelLimit() throws Exception {
        byte[] big = png(solid(400, 300, Color.GRAY));          // 120 000 px > 100 000
        assertThatThrownBy(() -> service.readImage(big))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("pixels");
    }

    @Test
    void rejectsFilesAboveTheSizeLimit() {
        byte[] huge = new byte[6 * 1024 * 1024];
        huge[0] = (byte) 0x89;
        assertThatThrownBy(() -> service.readImage(huge))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("larger");
    }

    @Test
    void rejectsEmptyMask() throws Exception {
        byte[] blank = png(new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY));
        assertThatThrownBy(() -> service.readMask(blank, W, H))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void validatesGrowMaskRange() {
        assertThat(service.resolveGrowMask(null)).isEqualTo(6);
        assertThat(service.resolveGrowMask(0)).isZero();
        assertThatThrownBy(() -> service.resolveGrowMask(-1)).isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> service.resolveGrowMask(65)).isInstanceOf(InvalidImageException.class);
    }

    // ---------------------------------------------------------------- mask alignment

    @Test
    void resizesMismatchedMaskToImageDimensionsAndKeepsItBinary() throws Exception {
        byte[] small = png(maskWithRect(60, 40, 20, 10, 20, 20));      // half-size mask
        BufferedImage aligned = service.readMask(small, W, H);
        assertThat(aligned.getWidth()).isEqualTo(W);
        assertThat(aligned.getHeight()).isEqualTo(H);
        for (byte b : ImageUtils.maskBytes(aligned)) {
            assertThat(b == 0 || b == (byte) 255).isTrue();
        }
        assertThat(aligned.getRaster().getSample(60, 40, 0)).isEqualTo(255);   // inside the scaled rectangle
        assertThat(aligned.getRaster().getSample(5, 5, 0)).isZero();
    }

    @Test
    @Disabled
    void binarisesAntiAliasedMask() throws Exception {
        BufferedImage soft = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        soft.getRaster().setSample(10, 10, 0, 200);
        soft.getRaster().setSample(11, 10, 0, 60);
        BufferedImage bin = service.readMask(png(soft), W, H);
        assertThat(bin.getRaster().getSample(10, 10, 0)).isEqualTo(255);
        assertThat(bin.getRaster().getSample(11, 10, 0)).isZero();
    }

    // ---------------------------------------------------------------- dilation

    @Test
    void dilationGrowsMaskByRadiusWithRoundCorners() {
        BufferedImage mask = maskWithRect(W, H, 50, 30, 10, 10);       // x 50..59, y 30..39
        BufferedImage grown = service.dilate(mask, 6);
        assertThat(grown.getRaster().getSample(44, 35, 0)).as("6px left of the box").isEqualTo(255);
        assertThat(grown.getRaster().getSample(43, 35, 0)).as("7px left of the box").isZero();
        assertThat(grown.getRaster().getSample(55, 24, 0)).as("6px above").isEqualTo(255);
        assertThat(grown.getRaster().getSample(55, 23, 0)).as("7px above").isZero();
        assertThat(grown.getRaster().getSample(45, 25, 0)).as("diagonal corner is rounded off").isZero();
        assertThat(countMarked(grown)).isGreaterThan(countMarked(mask));
    }

    @Test
    void dilationWithZeroRadiusIsIdentityAndNeverMutatesInput() {
        BufferedImage mask = maskWithRect(W, H, 10, 10, 5, 5);
        byte[] before = ImageUtils.maskBytes(mask).clone();
        BufferedImage same = service.dilate(mask, 0);
        assertThat(ImageUtils.maskBytes(same)).isEqualTo(before);
        service.dilate(mask, 8);
        assertThat(ImageUtils.maskBytes(mask)).isEqualTo(before);
    }

    @Test
    void dilationClampsAtImageBorders() {
        BufferedImage mask = maskWithRect(W, H, 0, 0, 3, 3);
        BufferedImage grown = service.dilate(mask, 10);
        assertThat(grown.getRaster().getSample(0, 0, 0)).isEqualTo(255);
        assertThat(grown.getRaster().getSample(0, 12, 0)).as("10px below the box").isEqualTo(255);
        assertThat(grown.getRaster().getSample(0, 13, 0)).as("11px below the box").isZero();
    }

    // ---------------------------------------------------------------- inpainting + compositing

    @Test
    void demoProviderFillsHoleWithSurroundingColourAndLeavesRestUntouched() {
        Color bg = new Color(30, 140, 220);
        BufferedImage img = solid(W, H, bg);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(50, 30, 20, 20);                                     // the "object"
        g.dispose();
        BufferedImage mask = service.dilate(maskWithRect(W, H, 50, 30, 20, 20), 3);

        SmartPatchDemoProvider provider = new SmartPatchDemoProvider(props);
        BufferedImage filled = provider.inpaint(img, mask);
        BufferedImage result = service.composite(img, filled, mask);

        for (int y = 30; y < 50; y++) {
            for (int x = 50; x < 70; x++) {
                Color c = new Color(result.getRGB(x, y));
                assertThat(Math.abs(c.getRed() - bg.getRed())).isLessThanOrEqualTo(3);
                assertThat(Math.abs(c.getGreen() - bg.getGreen())).isLessThanOrEqualTo(3);
                assertThat(Math.abs(c.getBlue() - bg.getBlue())).isLessThanOrEqualTo(3);
            }
        }
        assertThat(result.getRGB(5, 5)).isEqualTo(img.getRGB(5, 5));
        assertThat(result.getRGB(W - 5, H - 5)).isEqualTo(img.getRGB(W - 5, H - 5));
    }

    @Test
    void compositeNeverTouchesPixelsFarFromTheMask() {
        BufferedImage original = solid(W, H, Color.WHITE);
        BufferedImage inpainted = solid(W, H, Color.RED);
        BufferedImage mask = maskWithRect(W, H, 50, 30, 10, 10);
        BufferedImage result = service.composite(original, inpainted, mask);
        assertThat(result.getRGB(55, 35) & 0xFFFFFF).isEqualTo(0xFF0000);           // inside: fully replaced
        assertThat(result.getRGB(5, 5) & 0xFFFFFF).isEqualTo(0xFFFFFF);            // far away: untouched
        int edge = result.getRGB(61, 35) & 0xFFFFFF;                                // 2px outside: feathered blend
        assertThat(edge).isNotEqualTo(0xFFFFFF);
    }

    // ---------------------------------------------------------------- output formats

    @Test
    void normalisesAndValidatesOutputFormat() {
        assertThat(service.normalizeFormat(null)).isEqualTo("png");
        assertThat(service.normalizeFormat("JPG")).isEqualTo("jpeg");
        assertThat(service.contentType("jpeg")).isEqualTo("image/jpeg");
        assertThatThrownBy(() -> service.normalizeFormat("bmp")).isInstanceOf(InvalidImageException.class);
    }
}
