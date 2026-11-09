package com.starkgroup.vms.dev;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Tiny but valid demo files for seeded documents: a one-page PDF (with a correct cross-reference table) and a
 * small PNG. Only used by {@link DemoDataSeeder} in the local profile.
 */
final class DemoFiles {

    private DemoFiles() {
    }

    /** A small PNG (a coloured card with a dark band), generated with ImageIO - no binary files in the repo. */
    static byte[] png() {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0xE8, 0xEE, 0xF6));
            graphics.fillRect(0, 0, 120, 80);
            graphics.setColor(new Color(0x1F, 0x3A, 0x5F));
            graphics.fillRect(0, 0, 120, 18);
            graphics.fillRect(12, 32, 96, 6);
            graphics.fillRect(12, 48, 64, 6);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** A one-page PDF showing {@code text} (ASCII letters, digits, spaces and hyphens). */
    static byte[] pdf(String text) {
        String safeText = text.replaceAll("[^A-Za-z0-9 \\-]", "");
        String stream = "BT /F1 16 Tf 24 80 Td (" + safeText + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 180] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xrefOffset = out.size();
        StringBuilder xref = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            xref.append(String.format("%010d 00000 n \n", offset));
        }
        write(out, xref.toString());
        write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefOffset
                + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
