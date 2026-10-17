package com.lue.erp.images;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class ImageThumbnailer {
    private ImageThumbnailer() { }

    public static byte[] jpeg(byte[] source, int maximumDimension) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException("图片无法解码");
        double scale = Math.min(1.0, Math.min((double) maximumDimension / image.getWidth(), (double) maximumDimension / image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(small, "jpeg", output)) throw new IOException("JPEG 缩略图编码不可用");
        return output.toByteArray();
    }
}
