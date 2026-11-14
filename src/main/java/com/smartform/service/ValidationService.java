package com.smartform.service;

import com.smartform.model.Models.*;
import com.smartform.service.FactExtractor.Concept;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ValidationService {

    public static final String RULE_PREFIX = "__rule_";
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+\\-]+@[\\w\\-]+(\\.[\\w\\-]+)+$");

    public List<Issue> validate(FormAnalysis analysis, Map<String, String> values, List<DocRecord> supporting,
                                List<Inconsistency> inconsistencies) {
        List<Issue> out = new ArrayList<>();
        if (analysis == null) {
            out.add(new Issue(Severity.ERROR, "No application form has been selected and analyzed."));
            return out;
        }
        for (FormField f : analysis.fields()) {
            String v = values.getOrDefault(f.name(), "").strip();
            if (f.type() == FieldType.CHECKBOX) {
                if (f.required() && !"true".equals(v)) {
                    out.add(error("Required checkbox '" + f.label() + "' is not ticked."));
                }
                continue;
            }
            if (v.isEmpty()) {
                if (f.required()) {
                    out.add(error("Required field '" + f.label() + "' is empty."));
                }
                continue;
            }
            Concept c = FactExtractor.conceptOfLabel(f.label());
            if (c == Concept.DATE_OF_BIRTH && !FactExtractor.normalize(c, v).matches("\\d{4}-\\d{2}-\\d{2}")) {
                out.add(error("'" + f.label() + "' is not a recognised date (use DD/MM/YYYY)."));
            } else if (c == Concept.EMAIL && !EMAIL.matcher(v).matches()) {
                out.add(error("'" + f.label() + "' is not a valid email address."));
            } else if (c == Concept.PHONE) {
                int digits = v.replaceAll("\\D", "").length();
                if (digits < 7 || digits > 15) {
                    out.add(error("'" + f.label() + "' is not a valid phone number."));
                }
            }
        }
        for (RequiredDocument d : analysis.documents()) {
            if (!satisfied(d.name(), supporting)) {
                out.add(error("Required document missing: " + d.name()));
            }
        }
        for (int i = 0; i < analysis.rules().size(); i++) {
            ConditionalRule r = analysis.rules().get(i);
            boolean answer = "true".equals(values.get(RULE_PREFIX + i));
            if (!r.applies(answer)) {
                continue;
            }
            if (r.documentName() != null) {
                if (!satisfied(r.documentName(), supporting)) {
                    out.add(error("Conditional document missing (" + describe(r) + "): " + r.documentName()));
                }
            } else {
                out.add(new Issue(Severity.WARNING, "Conditional requirement applies, please confirm it is complete: "
                        + r.requirement() + " (" + describe(r) + ")"));
            }
        }
        for (Inconsistency inc : inconsistencies) {
            StringBuilder sb = new StringBuilder("Inconsistent " + inc.concept() + ": ");
            inc.evidence().forEach(e -> sb.append(e.source()).append(" = '").append(e.value()).append("'; "));
            out.add(error(sb.toString().strip()));
        }
        return out;
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    private static boolean satisfied(String requirement, List<DocRecord> supporting) {
        return supporting.stream().anyMatch(d -> requirement.equalsIgnoreCase(d.satisfies()));
    }

    private static String describe(ConditionalRule r) {
        return (r.negated() ? "unless " : "if ") + r.condition();
    }

    private static Issue error(String message) {
        return new Issue(Severity.ERROR, message);
    }
}
