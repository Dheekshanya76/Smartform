package com.smartform.service;

import com.smartform.model.Models.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the submission package: completed form followed by the supporting documents, as one PDF. */
@Service
public class PackageService {

    private final ApplicationService apps;

    public PackageService(ApplicationService apps) {
        this.apps = apps;
    }

    public Path build(Path outDir, String applicationName, FormAnalysis analysis, Map<String, String> values,
                      DocRecord form, List<DocRecord> supporting) throws IOException {
        Files.createDirectories(outDir);
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument dest = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setAcroFormMergeMode(PDFMergerUtility.AcroFormMergeMode.JOIN_FORM_FIELDS_MODE);

            byte[] formBytes = apps.bytes(form);
            if (isPdf(form)) {
                PDDocument src = Loader.loadPDF(formBytes);
                sources.add(src);
                if (analysis.acroForm()) {
                    fill(src, analysis, values);
                }
                merger.appendDocument(dest, src);
            } else {
                appendImage(dest, formBytes, form.originalName());
            }
            if (!analysis.acroForm()) {
                addDataPages(dest, applicationName, analysis, values);
            }
            for (DocRecord d : supporting) {
                byte[] bytes = apps.bytes(d);
                if (isPdf(d)) {
                    PDDocument src = Loader.loadPDF(bytes);
                    sources.add(src);
                    merger.appendDocument(dest, src);
                } else {
                    appendImage(dest, bytes, d.originalName());
                }
            }
            Path out = outDir.resolve(applicationName.replaceAll("[^A-Za-z0-9._-]+", "_") + "_Submission_Package.pdf");
            dest.save(out.toFile());
            return out;
        } finally {
            for (PDDocument s : sources) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static boolean isPdf(DocRecord d) {
        return d.originalName().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private void fill(PDDocument doc, FormAnalysis analysis, Map<String, String> values) throws IOException {
        PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
        if (acro == null) {
            return;
        }
        for (FormField f : analysis.fields()) {
            PDField field = acro.getField(f.name());
            String v = values.getOrDefault(f.name(), "");
            if (field == null || v.isBlank()) {
                continue;
            }
            if (field instanceof PDCheckBox box) {
                if ("true".equalsIgnoreCase(v)) {
                    box.check();
                } else {
                    box.unCheck();
                }
            } else {
                field.setValue(v);
            }
        }
        try {
            acro.flatten();
        } catch (IOException | RuntimeException e) {
            // keep the fields editable if flattening is not possible for this form
        }
    }

    private void addDataPages(PDDocument dest, String name, FormAnalysis analysis, Map<String, String> values)
            throws IOException {
        try (TextPages pages = new TextPages(dest)) {
            pages.line("Completed Application: " + name, true);
            pages.line(" ", false);
            for (FormField f : analysis.fields()) {
                String v = values.getOrDefault(f.name(), "");
                if (f.type() == FieldType.CHECKBOX) {
                    v = "true".equals(v) ? "Yes" : "No";
                }
                pages.line(f.label() + ": " + v, false);
            }
        }
    }

    private void appendImage(PDDocument dest, byte[] data, String name) throws IOException {
        PDImageXObject image = PDImageXObject.createFromByteArray(dest, data, name);
        PDPage page = new PDPage(PDRectangle.A4);
        dest.addPage(page);
        float maxW = page.getMediaBox().getWidth() - 60;
        float maxH = page.getMediaBox().getHeight() - 60;
        float scale = Math.min(maxW / image.getWidth(), maxH / image.getHeight());
        float w = image.getWidth() * scale;
        float h = image.getHeight() * scale;
        try (PDPageContentStream cs = new PDPageContentStream(dest, page)) {
            cs.drawImage(image, (page.getMediaBox().getWidth() - w) / 2, (page.getMediaBox().getHeight() - h) / 2, w, h);
        }
    }

    /** Minimal line-by-line text writer with wrapping and page breaks. */
    private static final class TextPages implements AutoCloseable {
        private static final float MARGIN = 50;
        private static final float LEADING = 15;
        private static final float SIZE = 11;

        private final PDDocument doc;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream cs;
        private float y;

        TextPages(PDDocument doc) {
            this.doc = doc;
        }

        void line(String text, boolean isBold) throws IOException {
            PDType1Font font = isBold ? bold : regular;
            for (String part : wrap(clean(text), font)) {
                if (cs == null || y < MARGIN) {
                    newPage();
                }
                cs.beginText();
                cs.setFont(font, SIZE);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(part);
                cs.endText();
                y -= LEADING;
            }
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        private List<String> wrap(String text, PDType1Font font) throws IOException {
            float max = PDRectangle.A4.getWidth() - 2 * MARGIN;
            List<String> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String word : text.split(" ")) {
                while (word.length() > 70) {
                    lines.add(word.substring(0, 70));
                    word = word.substring(70);
                }
                String candidate = cur.length() == 0 ? word : cur + " " + word;
                if (font.getStringWidth(candidate) / 1000 * SIZE > max && cur.length() > 0) {
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(candidate);
                }
            }
            lines.add(cur.toString());
            return lines;
        }

        private static String clean(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                sb.append((c >= 32 && c <= 126) || (c >= 160 && c <= 255) ? c : (Character.isWhitespace(c) ? ' ' : '?'));
            }
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            if (cs != null) {
                cs.close();
            }
        }
    }
}
