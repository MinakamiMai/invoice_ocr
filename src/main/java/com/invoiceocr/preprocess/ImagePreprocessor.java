package com.invoiceocr.preprocess;

import com.invoiceocr.ocr.OcrException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Pure-Java image preprocessing (no OpenCV native dependency required):
 * red stamp suppression, grayscale, median denoise, Sauvola binarization and deskew.
 */
@Slf4j
@Component
public class ImagePreprocessor {

    /** Baidu vat_invoice accepts base64 encoded images up to 8MB and 4096px on the long side. */
    private static final int API_MAX_DIMENSION = 4000;
    private static final int API_MAX_BYTES = 5_500_000;

    /**
     * Preparation for commercial OCR APIs: keep the original image untouched when it already
     * fits the API limits (binarization destroys thin strokes in photos), otherwise downscale
     * and re-encode as JPEG so the base64 payload stays within the limit.
     */
    public byte[] prepareForApi(byte[] input) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
            if (original == null) {
                throw new OcrException("不支持的图片格式");
            }
            int max = Math.max(original.getWidth(), original.getHeight());
            if (input.length <= API_MAX_BYTES && max <= API_MAX_DIMENSION) {
                return input;
            }
            BufferedImage image = downscaleIfNeeded(toRgb(original), API_MAX_DIMENSION);
            byte[] encoded = encodeJpeg(image, 0.92f);
            if (encoded.length > API_MAX_BYTES) {
                encoded = encodeJpeg(downscaleIfNeeded(image, 3000), 0.85f);
            }
            if (encoded.length > API_MAX_BYTES) {
                encoded = encodeJpeg(downscaleIfNeeded(image, 2000), 0.8f);
            }
            return encoded;
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("图像预处理失败：" + e.getMessage(), e);
        }
    }

    public byte[] process(byte[] input, boolean deskew, boolean stampSuppression) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
            if (original == null) {
                throw new OcrException("不支持的图片格式");
            }
            BufferedImage image = toRgb(original);
            image = downscaleIfNeeded(image, 3200);
            // Phone photos of invoices are often small; upscale so thin strokes survive
            // the median filter and binarization instead of disappearing.
            image = upscaleIfSmall(image, 1600);
            if (stampSuppression) {
                suppressRedStamp(image);
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] gray = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    gray[y * width + x] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                }
            }
            gray = median3x3(gray, width, height);
            int[] binary = sauvola(gray, width, height, 25, 0.22);
            if (deskew) {
                double angle = estimateSkew(binary, width, height);
                if (Math.abs(angle) >= 0.5 && Math.abs(angle) <= 8) {
                    binary = rotateBinary(binary, width, height, -angle, width, height);
                    log.debug("Deskew applied: {} degrees", angle);
                }
            }
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            result.getRaster().setPixels(0, 0, width, height, binary);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(result, "png", output);
            return output.toByteArray();
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("图像预处理失败：" + e.getMessage(), e);
        }
    }

    private BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage converted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = converted.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return converted;
    }

    private BufferedImage downscaleIfNeeded(BufferedImage image, int maxDimension) {
        int width = image.getWidth();
        int height = image.getHeight();
        int max = Math.max(width, height);
        if (max <= maxDimension) {
            return image;
        }
        double scale = (double) maxDimension / max;
        int newWidth = Math.max(1, (int) (width * scale));
        int newHeight = Math.max(1, (int) (height * scale));
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return scaled;
    }

    private BufferedImage upscaleIfSmall(BufferedImage image, int minMaxDimension) {
        int max = Math.max(image.getWidth(), image.getHeight());
        if (max >= minMaxDimension) {
            return image;
        }
        double scale = Math.min(3.0, (double) minMaxDimension / Math.max(1, max));
        int newWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int newHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return scaled;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private void suppressRedStamp(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 110 && r > g * 1.35 && r > b * 1.35) {
                    image.setRGB(x, y, 0xFFFFFF);
                }
            }
        }
    }

    private int[] median3x3(int[] gray, int width, int height) {
        int[] result = new int[gray.length];
        int[] window = new int[9];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(height - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(width - 1, Math.max(0, x + dx));
                        window[count++] = gray[yy * width + xx];
                    }
                }
                java.util.Arrays.sort(window);
                result[y * width + x] = window[4];
            }
        }
        return result;
    }

    private int[] sauvola(int[] gray, int width, int height, int windowSize, double k) {
        int radius = Math.max(1, windowSize / 2);
        long[] integral = new long[(width + 1) * (height + 1)];
        long[] integralSq = new long[(width + 1) * (height + 1)];
        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            long rowSumSq = 0;
            for (int x = 0; x < width; x++) {
                int value = gray[y * width + x];
                rowSum += value;
                rowSumSq += (long) value * value;
                integral[(y + 1) * (width + 1) + (x + 1)] = integral[y * (width + 1) + (x + 1)] + rowSum;
                integralSq[(y + 1) * (width + 1) + (x + 1)] = integralSq[y * (width + 1) + (x + 1)] + rowSumSq;
            }
        }
        int[] binary = new int[gray.length];
        double R = 128.0;
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                int area = (y1 - y0 + 1) * (x1 - x0 + 1);
                long sum = rectSum(integral, width, x0, y0, x1, y1);
                long sumSq = rectSum(integralSq, width, x0, y0, x1, y1);
                double mean = sum / (double) area;
                double variance = (sumSq / (double) area) - mean * mean;
                double std = Math.sqrt(Math.max(0, variance));
                double threshold = mean * (1.0 + k * (std / R - 1.0));
                binary[y * width + x] = gray[y * width + x] > threshold ? 255 : 0;
            }
        }
        return binary;
    }

    private long rectSum(long[] integral, int width, int x0, int y0, int x1, int y1) {
        int stride = width + 1;
        return integral[(y1 + 1) * stride + (x1 + 1)]
                - integral[y0 * stride + (x1 + 1)]
                - integral[(y1 + 1) * stride + x0]
                + integral[y0 * stride + x0];
    }

    private double estimateSkew(int[] binary, int width, int height) {
        int scale = Math.max(1, (int) Math.ceil(Math.max(width, height) / 900.0));
        int sw = width / scale;
        int sh = height / scale;
        if (sw < 20 || sh < 20) {
            return 0;
        }
        double bestAngle = 0;
        double bestScore = -1;
        for (double angle = -8; angle <= 8; angle += 1.0) {
            double rad = Math.toRadians(angle);
            double sin = Math.sin(rad);
            double cos = Math.cos(rad);
            int[] histogram = new int[sh * 2];
            for (int y = 0; y < height; y += scale) {
                for (int x = 0; x < width; x += scale) {
                    if (binary[y * width + x] != 0) {
                        continue;
                    }
                    int rotatedY = (int) Math.round(y * cos - x * sin);
                    int index = rotatedY + sh;
                    if (index >= 0 && index < histogram.length) {
                        histogram[index]++;
                    }
                }
            }
            double mean = 0;
            for (int value : histogram) {
                mean += value;
            }
            mean /= histogram.length;
            double variance = 0;
            for (int value : histogram) {
                variance += (value - mean) * (value - mean);
            }
            if (variance > bestScore) {
                bestScore = variance;
                bestAngle = angle;
            }
        }
        return bestAngle;
    }

    private int[] rotateBinary(int[] binary, int width, int height, double angle, int newWidth, int newHeight) {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        source.getRaster().setPixels(0, 0, width, height, binary);
        BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = rotated.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        AffineTransform transform = new AffineTransform();
        transform.rotate(Math.toRadians(angle), width / 2.0, height / 2.0);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, transform, null);
        graphics.dispose();
        int[] result = new int[width * height];
        rotated.getRaster().getPixels(0, 0, width, height, result);
        return result;
    }

    public boolean isSupportedImage(byte[] content) {
        if (content.length < 4) {
            return false;
        }
        int b0 = content[0] & 0xFF;
        int b1 = content[1] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) {
            return true;
        }
        if (b0 == 0x89 && b1 == 0x50 && content[2] == 0x4E && content[3] == 0x47) {
            return true;
        }
        return b0 == 0x42 && b1 == 0x4D;
    }
}
