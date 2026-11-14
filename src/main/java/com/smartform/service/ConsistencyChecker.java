package com.smartform.service;

import com.smartform.model.Models.*;
import com.smartform.service.FactExtractor.Concept;
import org.springframework.stereotype.Service;

import java.util.*;

/** Compares key facts entered in the form against every supporting document. */
@Service
public class ConsistencyChecker {

    public static final String FORM_SOURCE = "Application form (entered values)";

    public List<Inconsistency> check(FormAnalysis analysis, Map<String, String> values, Map<String, String> docTexts) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (analysis != null) {
            StringBuilder sb = new StringBuilder();
            for (FormField f : analysis.fields()) {
                String v = values.getOrDefault(f.name(), "").strip();
                Concept c = FactExtractor.conceptOfLabel(f.label());
                if (c != null && !v.isEmpty() && f.type() != FieldType.CHECKBOX) {
                    sb.append(c.canonical()).append(": ").append(v).append('\n');
                }
            }
            sources.put(FORM_SOURCE, sb.toString());
        }
        sources.putAll(docTexts);

        List<Inconsistency> out = new ArrayList<>();
        for (Concept c : Concept.values()) {
            List<Evidence> evidence = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                List<String> found = FactExtractor.extract(c, e.getValue());
                if (!found.isEmpty()) {
                    evidence.add(new Evidence(e.getKey(), found.get(0)));
                }
            }
            boolean conflict = false;
            for (int i = 0; i < evidence.size(); i++) {
                for (int j = i + 1; j < evidence.size(); j++) {
                    if (!FactExtractor.similar(c, evidence.get(i).value(), evidence.get(j).value())) {
                        conflict = true;
                    }
                }
            }
            if (conflict) {
                out.add(new Inconsistency(c.label(), evidence));
            }
        }
        return out;
    }
}
