package com.smartform.service;

import com.smartform.model.Models.AppRecord;
import com.smartform.model.Models.DocRecord;
import com.smartform.repository.ApplicationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Application cases, encrypted document vault and encrypted field values. */
@Service
public class ApplicationService {

    public static final String FORM = "FORM";
    public static final String SUPPORTING = "SUPPORTING";
    private static final Set<String> EXTENSIONS =
            Set.of("pdf", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif");
    private static final Set<String> STOP = Set.of("copy", "copies", "proof", "the", "and", "for", "your", "with",
            "self", "attested", "certified", "scanned", "recent", "valid", "original", "document", "documents",
            "any", "one", "two", "issued", "form", "size", "sized", "pdf", "png", "jpg", "jpeg");

    private final ApplicationRepository repo;
    private final CryptoService crypto;
    private final TextExtractionService extractor;
    private final Path vault;

    public ApplicationService(ApplicationRepository repo, CryptoService crypto, TextExtractionService extractor,
                              @Value("${smartform.data-dir}") String dataDir) throws IOException {
        this.repo = repo;
        this.crypto = crypto;
        this.extractor = extractor;
        this.vault = Path.of(dataDir, "vault");
        Files.createDirectories(vault);
    }

    public List<AppRecord> applications() {
        return repo.applications();
    }

    public AppRecord create(String name) {
        return new AppRecord(repo.createApplication(name), name);
    }

    public void delete(long appId) {
        repo.documents(appId).forEach(this::deleteFile);
        repo.deleteApplication(appId);
    }

    public DocRecord addDocument(long appId, String role, Path file) {
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: " + name + " (use PDF or an image).");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = extractor.extract(bytes, name);
            if (FORM.equals(role)) {
                repo.documents(appId).stream().filter(d -> FORM.equals(d.role())).forEach(this::removeDocument);
            }
            String stored = UUID.randomUUID() + ".enc";
            Files.write(vault.resolve(stored), crypto.encrypt(bytes));
            byte[] encText = crypto.encryptString(text);
            long id = repo.addDocument(appId, role, name, stored, encText);
            return new DocRecord(id, appId, role, name, stored, encText, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<DocRecord> documents(long appId) {
        return repo.documents(appId);
    }

    public Optional<DocRecord> form(long appId) {
        return repo.documents(appId).stream().filter(d -> FORM.equals(d.role())).findFirst();
    }

    public List<DocRecord> supporting(long appId) {
        return repo.documents(appId).stream().filter(d -> SUPPORTING.equals(d.role())).toList();
    }

    public byte[] bytes(DocRecord doc) {
        try {
            return crypto.decrypt(Files.readAllBytes(vault.resolve(doc.storedName())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String text(DocRecord doc) {
        return doc.encryptedText() == null ? "" : crypto.decryptString(doc.encryptedText());
    }

    public void removeDocument(DocRecord doc) {
        deleteFile(doc);
        repo.deleteDocument(doc.id());
    }

    public void setSatisfies(long docId, String requirement) {
        repo.setSatisfies(docId, requirement);
    }

    /** Matches unassigned supporting documents to required documents by file name / title words. */
    public void autoAssign(long appId, List<String> requirements) {
        for (DocRecord d : supporting(appId)) {
            if (d.satisfies() != null) {
                continue;
            }
            String head = text(d).lines().map(String::strip).filter(s -> !s.isEmpty()).limit(3)
                    .reduce("", (a, b) -> a + " " + b);
            Set<String> have = tokens(d.originalName() + " " + head);
            String best = null;
            double bestScore = 0;
            for (String req : requirements) {
                Set<String> need = tokens(req);
                if (need.isEmpty()) {
                    continue;
                }
                long hit = need.stream().filter(have::contains).count();
                double score = (double) hit / need.size();
                if (score >= 0.5 && score > bestScore) {
                    best = req;
                    bestScore = score;
                }
            }
            if (best != null) {
                repo.setSatisfies(d.id(), best);
            }
        }
    }

    public void saveValues(long appId, Map<String, String> values) {
        Map<String, byte[]> enc = new LinkedHashMap<>();
        values.forEach((k, v) -> enc.put(k, crypto.encrypt(v.getBytes(StandardCharsets.UTF_8))));
        repo.replaceValues(appId, enc);
    }

    public Map<String, String> loadValues(long appId) {
        Map<String, String> out = new LinkedHashMap<>();
        repo.values(appId).forEach((k, v) -> out.put(k, v == null ? "" : crypto.decryptString(v)));
        return out;
    }

    private void deleteFile(DocRecord doc) {
        try {
            Files.deleteIfExists(vault.resolve(doc.storedName()));
        } catch (IOException ignored) {
            // file already gone
        }
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (t.length() >= 3 && !STOP.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
