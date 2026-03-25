package au.com.dingwall.mark.bitbrush.service;

import au.com.dingwall.mark.bitbrush.config.BitbrushProperties;
import au.com.dingwall.mark.bitbrush.model.Pixel;
import au.com.dingwall.mark.bitbrush.repository.PixelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Generates a PNG snapshot of the current canvas state.
 *
 * The exported image is 2x the logical canvas size (e.g. 250x250 canvas
 * becomes a 500x500 PNG) for readability. Each logical pixel occupies a
 * 2x2 block of image pixels.
 */
@Service
public class CanvasExportService {

    private static final Logger log = LoggerFactory.getLogger(CanvasExportService.class);
    private static final int SCALE = 2;

    private final PixelRepository pixelRepository;
    private final List<String> colorPalette;
    private final BitbrushProperties bitbrushProperties;

    public CanvasExportService(PixelRepository pixelRepository,
                               List<String> colorPalette,
                               BitbrushProperties bitbrushProperties) {
        this.pixelRepository = pixelRepository;
        this.colorPalette = colorPalette;
        this.bitbrushProperties = bitbrushProperties;
    }

    /**
     * Generates a PNG image of the current canvas state.
     *
     * @return PNG image bytes
     * @throws RuntimeException if ImageIO.write fails
     */
    public byte[] generatePng() {
        int width = bitbrushProperties.canvas().width();
        int height = bitbrushProperties.canvas().height();
        int imgWidth = width * SCALE;
        int imgHeight = height * SCALE;

        BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);

        // Fill background with black
        for (int y = 0; y < imgHeight; y++) {
            for (int x = 0; x < imgWidth; x++) {
                image.setRGB(x, y, 0x000000);
            }
        }

        List<Pixel> pixels = pixelRepository.findCurrentCanvasState();
        log.debug("Generating PNG: {} pixels on {}x{} canvas ({}x{} image)",
                pixels.size(), width, height, imgWidth, imgHeight);

        for (Pixel pixel : pixels) {
            if (pixel.getPaletteIndex() < 0 || pixel.getPaletteIndex() >= colorPalette.size()) {
                log.warn("Skipping pixel at ({}, {}) with invalid paletteIndex {}",
                        pixel.getX(), pixel.getY(), pixel.getPaletteIndex());
                continue;
            }
            if (pixel.getX() < 0 || pixel.getX() >= width || pixel.getY() < 0 || pixel.getY() >= height) {
                log.warn("Skipping pixel at ({}, {}) outside canvas bounds {}x{}",
                        pixel.getX(), pixel.getY(), width, height);
                continue;
            }
            int rgb = hexToRgbInt(colorPalette.get(pixel.getPaletteIndex()));
            int px = pixel.getX() * SCALE;
            int py = pixel.getY() * SCALE;
            // Fill 2x2 block
            for (int dy = 0; dy < SCALE; dy++) {
                for (int dx = 0; dx < SCALE; dx++) {
                    image.setRGB(px + dx, py + dy, rgb);
                }
            }
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] pngBytes = baos.toByteArray();
            log.debug("PNG generated: {} bytes", pngBytes.length);
            return pngBytes;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate PNG", e);
        }
    }

    /**
     * Converts a hex color string (e.g. "#FF6633") to an RGB int (0xRRGGBB).
     */
    private int hexToRgbInt(String hex) {
        return Integer.parseInt(hex.substring(1), 16);
    }
}
