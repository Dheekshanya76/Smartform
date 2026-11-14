package com.smartform.model;

import java.util.List;

public final class Models {
    private Models() {
    }

    public enum FieldType { TEXT, CHECKBOX, CHOICE }

    public enum Severity { ERROR, WARNING }

    public record FormField(String name, String label, FieldType type, boolean required, List<String> options) {
    }

    public record RequiredDocument(String name) {
    }

    /** A requirement that only applies if a condition holds ("unless" rules are negated). */
    public record ConditionalRule(String condition, String requirement, String documentName, boolean negated) {
        public boolean applies(boolean conditionAnswer) {
            return negated != conditionAnswer;
        }
    }

    public record FormAnalysis(List<FormField> fields, List<RequiredDocument> documents,
                               List<ConditionalRule> rules, boolean acroForm) {
    }

    public record AppRecord(long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record DocRecord(long id, long applicationId, String role, String originalName, String storedName,
                            byte[] encryptedText, String satisfies) {
    }

    public record Suggestion(String value, String source) {
    }

    public record Evidence(String source, String value) {
    }

    public record Inconsistency(String concept, List<Evidence> evidence) {
    }

    public record Issue(Severity severity, String message) {
    }
}
