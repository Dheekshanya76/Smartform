package com.smartform.service;

import com.smartform.model.Models.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies fields, required documents and conditional requirements in a form.
 * Uses the PDF's AcroForm fields when present, otherwise text heuristics.
 */
@Service
public class FormAnalyzer {

    private static final Pattern OPTIONAL = Pattern.compile("(?i)\\(\\s*optional\\s*\\)");
    private static final Pattern REQUIRED_TAG = Pattern.compile("(?i)\\(\\s*(required|mandatory)\\s*\\)");
    private static final Pattern ALL_MANDATORY =
            Pattern.compile("(?i)all\\s+(fields|items|sections)\\s+(are\\s+|is\\s+)?(mandatory|required|compulsory)");
    private static final Pattern LINE_BLANK = Pattern.compile(
            "^\\s*(?:\\d+[.)]\\s*)?([A-Za-z][A-Za-z0-9 /'.()\\-]{1,60}?)\\s*(\\*)?\\s*:?\\s*(?:_{3,}|\\.{4,})\\s*(\\*)?\\s*$");
    private static final Pattern LINE_COLON = Pattern.compile(
            "^\\s*(?:\\d+[.)]\\s*)?([A-Za-z][A-Za-z0-9 /'.()\\-]{1,60}?)\\s*(\\*)?\\s*:\\s*(\\*)?\\s*$");
    private static final Pattern NOT_A_FIELD = Pattern.compile(
            "(?i)\\b(documents?|instructions?|notes?|checklist|enclosures?|attachments?|declaration|section|part|following)\\b");

    private static final Pattern ACTION =
            Pattern.compile("(?i)\\b(attach|enclose|submit|provide|upload|include|furnish|produce)\\b");
    private static final Pattern DOC_NOUN = Pattern.compile("(?i)\\b(copy|copies|proof|certificate|statement|letter"
            + "|passport|licen[sc]e|card|photo|photograph|photographs|transcript|receipt|bill|report|affidavit"
            + "|declaration|visa|record|slip|invoice|id|identity|aadhaar|aadhar|pan|marksheet|diploma|degree|contract)\\b");
    private static final Pattern CONDITION = Pattern.compile("(?i)\\b(if|in case|when|where|unless)\\b");
    private static final Pattern COND_FIRST = Pattern.compile(
            "(?i)^\\s*(if|in case(?: of)?|when|where|unless)\\s+(.+?)\\s*,\\s*(?:then\\s+)?(.+)$");
    private static final Pattern COND_LAST = Pattern.compile(
            "(?i)^(.+?)[\\s,(]+\\b(if|in case(?: of)?|when|where|unless)\\b\\s+(.+?)[)\\s.]*$");
    private static final Pattern HEADING = Pattern.compile("(?i)^(required documents?|documents? required|supporting documents?"
            + "|documents? to (be )?(attached|submitted|enclosed|attach|submit|enclose)|enclosures?|attachments?|checklist)\\b.*");
    private static final Pattern BULLET = Pattern.compile(
            "^\\s*(?:[-*\\u2022\\u00B7\\u25AA\\u25CF\\u25CB\\u2610\\u25A1\\[\\]]|\\d+[.)]|[a-z][.)])\\s*(.+)$");
    private static final Pattern CUT = Pattern.compile(
            "(?i)\\s+(?:if|when|where|unless|in case|along with|together with|with the|to the|before|after|so that)\\b.*|[.;(].*$");
    private static final Pattern LEAD = Pattern.compile("(?i)^(?:(?:a|an|the|your|one|two|three|\\d+|self-attested|certified"
            + "|scanned|recent|valid|original|clear|colou?r|photocopy|photocopies|copy|copies|of|attested|duly)\\s+)+");
    private static final Pattern GENERIC = Pattern.compile("(?i)^(documents?|details|information|forms?|application)$");

    public FormAnalysis analyze(byte[] data, String fileName, String text) {
        boolean pdf = fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean allMandatory = ALL_MANDATORY.matcher(text).find();
        List<FormField> fields = new ArrayList<>();
        boolean acro = false;
        if (pdf) {
            try (PDDocument doc = Loader.loadPDF(data)) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                if (form != null) {
                    fields = acroFields(form, text, allMandatory);
                    acro = !fields.isEmpty();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (!acro) {
            fields = textFields(text, allMandatory);
        }
        List<String> docs = new ArrayList<>();
        List<ConditionalRule> rules = new ArrayList<>();
        findRequirements(text, docs, rules);
        return new FormAnalysis(fields, docs.stream().map(RequiredDocument::new).toList(), rules, acro);
    }

    // ---------- fields ----------

    private List<FormField> acroFields(PDAcroForm form, String text, boolean allMandatory) {
        List<FormField> out = new ArrayList<>();
        for (PDField f : form.getFieldTree()) {
            if (!(f instanceof PDTerminalField) || f instanceof PDPushButton || f instanceof PDSignatureField) {
                continue;
            }
            String alt = f.getAlternateFieldName();
            String label = (alt != null && !alt.isBlank()) ? alt.strip() : f.getFullyQualifiedName();
            FieldType type = FieldType.TEXT;
            List<String> options = List.of();
            if (f instanceof PDCheckBox) {
                type = FieldType.CHECKBOX;
            } else if (f instanceof PDChoice choice) {
                type = FieldType.CHOICE;
                options = choice.getOptions();
            } else if (f instanceof PDRadioButton radio) {
                type = FieldType.CHOICE;
                options = radio.getExportValues();
            }
            boolean starred = Pattern.compile(Pattern.quote(label) + "\\s*\\*", Pattern.CASE_INSENSITIVE)
                    .matcher(text).find() || label.contains("*");
            boolean required = f.isRequired() || starred || allMandatory;
            out.add(new FormField(f.getFullyQualifiedName(), label.replace("*", "").strip(), type, required, options));
        }
        return out;
    }

    private List<FormField> textFields(String text, boolean allMandatory) {
        Map<String, FormField> out = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean optional = OPTIONAL.matcher(line).find();
            boolean tagged = REQUIRED_TAG.matcher(line).find();
            line = REQUIRED_TAG.matcher(OPTIONAL.matcher(line).replaceAll("")).replaceAll("").strip();
            String label;
            boolean star;
            Matcher m = LINE_BLANK.matcher(line);
            if (m.matches()) {
                label = m.group(1);
                star = m.group(2) != null || m.group(3) != null;
            } else {
                m = LINE_COLON.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                label = m.group(1);
                star = m.group(2) != null || m.group(3) != null;
                if (label.trim().split("\\s+").length > 6 || NOT_A_FIELD.matcher(label).find()) {
                    continue;
                }
            }
            label = label.strip();
            boolean required = !optional && (star || tagged || allMandatory);
            out.putIfAbsent(label.toLowerCase(Locale.ROOT),
                    new FormField(label, label, FieldType.TEXT, required, List.of()));
        }
        return new ArrayList<>(out.values());
    }

    // ---------- required documents and conditional requirements ----------

    private record Unit(String text, boolean listItem) {
    }

    private void findRequirements(String text, List<String> docs, List<ConditionalRule> rules) {
        for (Unit u : units(text)) {
            String s = u.text();
            boolean conditional = CONDITION.matcher(s).find();
            if (u.listItem()) {
                if (!(conditional && parseRule(s, true, rules))) {
                    addAll(extractNames(s, false), docs);
                }
            } else if (ACTION.matcher(s).find()) {
                if (conditional) {
                    parseRule(s, false, rules);
                } else {
                    addAll(extractNames(s, true), docs);
                }
            }
        }
    }

    private List<Unit> units(String text) {
        List<Unit> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDocs = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flush(cur, out);
                inDocs = false;
                continue;
            }
            if (line.length() < 80 && HEADING.matcher(line).matches()) {
                flush(cur, out);
                inDocs = true;
                continue;
            }
            Matcher b = BULLET.matcher(line);
            if (inDocs && b.matches()) {
                flush(cur, out);
                out.add(new Unit(b.group(1).strip(), true));
                continue;
            }
            inDocs = false;
            if (cur.length() > 0 && Character.isLowerCase(line.charAt(0))) {
                cur.append(' ').append(line);
            } else {
                flush(cur, out);
                cur.append(line);
            }
        }
        flush(cur, out);
        return out;
    }

    private void flush(StringBuilder cur, List<Unit> out) {
        if (cur.length() > 0) {
            for (String s : cur.toString().split("(?<=[.;])\\s+(?=[A-Z])")) {
                if (!s.isBlank()) {
                    out.add(new Unit(s.strip(), false));
                }
            }
            cur.setLength(0);
        }
    }

    private boolean parseRule(String s, boolean listItem, List<ConditionalRule> rules) {
        String keyword;
        String condition;
        String action;
        Matcher m = COND_FIRST.matcher(s);
        if (m.matches()) {
            keyword = m.group(1);
            condition = m.group(2);
            action = m.group(3);
        } else {
            m = COND_LAST.matcher(s);
            if (!m.matches()) {
                return false;
            }
            action = m.group(1);
            keyword = m.group(2);
            condition = m.group(3);
        }
        action = action.replaceAll("^[\\s,;(]+|[\\s,;.)]+$", "");
        condition = condition.replaceAll("^[\\s,;(]+|[\\s,;.)]+$", "");
        if (action.isEmpty() || condition.isEmpty()) {
            return false;
        }
        List<String> names = extractNames(action, !listItem);
        rules.add(new ConditionalRule(condition, action, names.isEmpty() ? null : names.get(0),
                keyword.equalsIgnoreCase("unless")));
        return true;
    }

    private List<String> extractNames(String sentence, boolean requireNoun) {
        String t = sentence;
        Matcher a = ACTION.matcher(t);
        if (a.find()) {
            t = t.substring(a.end());
        }
        Matcher cut = CUT.matcher(t.strip());
        t = cut.find() ? t.strip().substring(0, cut.start()) : t.strip();
        List<String> out = new ArrayList<>();
        for (String part : t.split("\\s*,\\s*")) {
            if (requireNoun && !DOC_NOUN.matcher(part).find()) {
                continue;
            }
            String name = LEAD.matcher(part.strip()).replaceFirst("").strip();
            if (name.isEmpty() || GENERIC.matcher(name).matches()) {
                continue;
            }
            if (name.length() > 80) {
                name = name.substring(0, 80).strip();
            }
            out.add(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
        return out;
    }

    private void addAll(List<String> names, List<String> docs) {
        for (String n : names) {
            if (docs.stream().noneMatch(d -> d.equalsIgnoreCase(n))) {
                docs.add(n);
            }
        }
    }
}
