package com.smartform.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds, normalises and compares key facts (name, date of birth, address, ...) in document text. */
public final class FactExtractor {

    private FactExtractor() {
    }

    public enum Concept {
        NAME("Name", "Full Name",
                "(?im)^\\s*(?:full\\s+name|applicant(?:'s)?\\s+name|name\\s+of\\s+(?:the\\s+)?(?:applicant|holder|candidate)|name)\\s*[:\\-]\\s*(.+?)\\s*$"),
        DATE_OF_BIRTH("Date of birth", "Date of Birth",
                "(?im)^\\s*(?:date\\s+of\\s+birth|birth\\s+date|d\\.?o\\.?b\\.?)\\s*[:\\-]\\s*(.+?)\\s*$"),
        ADDRESS("Address", "Address",
                "(?im)^\\s*(?:(?:residential|permanent|current|mailing|home)\\s+)?address\\s*[:\\-]\\s*(.+?)\\s*$"),
        ID_NUMBER("ID number", "ID Number",
                "(?im)^\\s*(?:passport|aadhaar|aadhar|pan|ssn|driving\\s+licen[sc]e|licen[sc]e|national\\s+id|id|identity(?:\\s+card)?)\\s*(?:no\\.?|number|num\\.?|#)?\\s*[:\\-]\\s*([A-Za-z0-9][A-Za-z0-9\\- ]{3,30}?)\\s*$"),
        EMAIL("Email", "Email",
                "(?im)^\\s*e-?mail(?:\\s+(?:id|address))?\\s*[:\\-]\\s*([\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+)"),
        PHONE("Phone", "Phone",
                "(?im)^\\s*(?:phone|mobile|telephone|tel|contact)(?:\\s*(?:no\\.?|number|num\\.?))?\\s*[:\\-]\\s*(\\+?\\d[\\d\\s\\-()]{6,20}\\d)");

        private final String label;
        private final String canonical;
        private final Pattern pattern;

        Concept(String label, String canonical, String regex) {
            this.label = label;
            this.canonical = canonical;
            this.pattern = Pattern.compile(regex);
        }

        public String label() {
            return label;
        }

        public String canonical() {
            return canonical;
        }
    }

    private static final Pattern NOT_APPLICANT_NAME = Pattern.compile(
            "(?i).*\\b(father|mother|spouse|guardian|parent|company|school|college|employer|husband|wife|maiden"
                    + "|nominee|reference|institution|bank|branch)\\b.*");
    private static final Pattern ID_LABEL = Pattern.compile(
            "(?i).*\\b(passport|aadhaar|aadhar|ssn|licen[sc]e|national id|id (no|number)|identity|pan)\\b.*");
    private static final Pattern NAME_LABEL = Pattern.compile("(?i).*\\bname\\b.*");

    private static final List<DateTimeFormatter> DATE_FORMATS = new ArrayList<>();

    static {
        for (String p : new String[]{"d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "yyyy-M-d", "yyyy/M/d",
                "d MMMM yyyy", "d MMM yyyy", "MMMM d, yyyy", "MMM d, yyyy"}) {
            DATE_FORMATS.add(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                    .toFormatter(Locale.ENGLISH));
        }
    }

    public static List<String> extract(Concept concept, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = concept.pattern.matcher(text);
        while (m.find()) {
            String v = m.group(1).strip();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** Maps a form field label to a concept, or null if it is not one of the known facts. */
    public static Concept conceptOfLabel(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("birth") || l.matches(".*\\bdob\\b.*")) return Concept.DATE_OF_BIRTH;
        if (l.contains("email") || l.contains("e-mail")) return Concept.EMAIL;
        if (l.contains("phone") || l.contains("mobile") || l.contains("telephone") || l.contains("contact no")) {
            return Concept.PHONE;
        }
        if (l.contains("address")) return Concept.ADDRESS;
        if (ID_LABEL.matcher(l).matches()) return Concept.ID_NUMBER;
        if (NAME_LABEL.matcher(l).matches() && !NOT_APPLICANT_NAME.matcher(l).matches()) return Concept.NAME;
        return null;
    }

    public static String normalize(Concept c, String value) {
        String v = value.strip();
        switch (c) {
            case NAME:
                return v.toLowerCase(Locale.ROOT)
                        .replaceAll("\\b(mr|mrs|ms|miss|dr|shri|smt|prof)\\b\\.?", " ")
                        .replaceAll("[^\\p{L}]+", " ").strip();
            case DATE_OF_BIRTH:
                for (DateTimeFormatter f : DATE_FORMATS) {
                    try {
                        return LocalDate.parse(v, f).toString();
                    } catch (RuntimeException ignored) {
                        // try next format
                    }
                }
                return v.toLowerCase(Locale.ROOT);
            case ADDRESS:
                return v.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
            case ID_NUMBER:
                return v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            case PHONE:
                String digits = v.replaceAll("\\D", "");
                return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
            default:
                return v.toLowerCase(Locale.ROOT);
        }
    }

    public static boolean similar(Concept c, String a, String b) {
        String na = normalize(c, a);
        String nb = normalize(c, b);
        if (na.equals(nb)) {
            return true;
        }
        Set<String> ta = new HashSet<>(Arrays.asList(na.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(nb.split(" ")));
        if (c == Concept.NAME) {
            return ta.containsAll(tb) || tb.containsAll(ta);
        }
        if (c == Concept.ADDRESS) {
            Set<String> inter = new HashSet<>(ta);
            inter.retainAll(tb);
            Set<String> union = new HashSet<>(ta);
            union.addAll(tb);
            return !union.isEmpty() && (double) inter.size() / union.size() >= 0.75;
        }
        return false;
    }
}
