package com.smartform.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/** Extracts text with PDFBox; falls back to Tesseract OCR for scanned PDFs and images. */
@Service
public class TextExtractionService {

    private final String tessdata;
    private final String language;

    public TextExtractionService(@Value("${smartform.tessdata:}") String tessdata,
                                 @Value("${smartform.ocr-language:eng}") String language) {
        this.tessdata = tessdata;
        this.language = language;
    }

    public synchronized String extract(byte[] data, String fileName) {
        try {
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                return extractPdf(data);
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new IllegalArgumentException("Unreadable image: " + fileName);
            }
            return ocr(image);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String extractPdf(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            String text = new PDFTextStripper().getText(doc);
            if (text.replaceAll("\\s", "").length() >= 30) {
                return text;
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                sb.append(ocr(renderer.renderImageWithDPI(i, 300))).append('\n');
            }
            return sb.toString();
        }
    }

    private String ocr(BufferedImage image) {
        try {
            Tesseract tesseract = new Tesseract();
            if (tessdata != null && !tessdata.isBlank()) {
                tesseract.setDatapath(tessdata);
            }
            tesseract.setLanguage(language);
            return tesseract.doOCR(image);
        } catch (TesseractException | UnsatisfiedLinkError e) {
            throw new IllegalStateException("OCR failed. Check that Tesseract is installed and that "
                    + "smartform.tessdata points to your tessdata folder. (" + e.getMessage() + ")", e);
        }
    }
}
