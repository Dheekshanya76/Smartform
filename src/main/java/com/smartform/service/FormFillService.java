package com.smartform.service;

import com.smartform.model.Models.*;
import com.smartform.service.FactExtractor.Concept;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Suggests field values taken from the supporting documents. */
@Service
public class FormFillService {

    public Suggestion suggest(FormField field, Map<String, String> docTexts) {
        if (field.type() == FieldType.CHECKBOX) {
            return null;
        }
        Pattern direct = Pattern.compile("(?im)^\\s*" + Pattern.quote(field.label()) + "\\s*[:\\-]\\s*(.+?)\\s*$");
        for (Map.Entry<String, String> e : docTexts.entrySet()) {
            Matcher m = direct.matcher(e.getValue());
            if (m.find() && !m.group(1).isBlank() && allowed(field, m.group(1).strip())) {
                return new Suggestion(m.group(1).strip(), e.getKey());
            }
        }
        Concept c = FactExtractor.conceptOfLabel(field.label());
        if (c != null) {
            for (Map.Entry<String, String> e : docTexts.entrySet()) {
                List<String> found = FactExtractor.extract(c, e.getValue());
                if (!found.isEmpty() && allowed(field, found.get(0))) {
                    return new Suggestion(found.get(0), e.getKey());
                }
            }
        }
        return null;
    }

    private boolean allowed(FormField field, String value) {
        return field.type() != FieldType.CHOICE || field.options().contains(value);
    }
}
