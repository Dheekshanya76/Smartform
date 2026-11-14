package com.smartform.repository;

import com.smartform.model.Models.AppRecord;
import com.smartform.model.Models.DocRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ApplicationRepository {

    private final JdbcTemplate jdbc;

    public ApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"ID"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public long createApplication(String name) {
        return insert("INSERT INTO applications(name) VALUES (?)", name);
    }

    public List<AppRecord> applications() {
        return jdbc.query("SELECT id, name FROM applications ORDER BY id DESC",
                (rs, i) -> new AppRecord(rs.getLong(1), rs.getString(2)));
    }

    public void deleteApplication(long id) {
        jdbc.update("DELETE FROM applications WHERE id = ?", id);
    }

    public long addDocument(long appId, String role, String originalName, String storedName, byte[] encryptedText) {
        return insert("INSERT INTO documents(application_id, role, original_name, stored_name, extracted_text) "
                + "VALUES (?,?,?,?,?)", appId, role, originalName, storedName, encryptedText);
    }

    public List<DocRecord> documents(long appId) {
        return jdbc.query("SELECT id, application_id, role, original_name, stored_name, extracted_text, satisfies "
                        + "FROM documents WHERE application_id = ? ORDER BY id",
                (rs, i) -> new DocRecord(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBytes(6), rs.getString(7)), appId);
    }

    public void deleteDocument(long id) {
        jdbc.update("DELETE FROM documents WHERE id = ?", id);
    }

    public void setSatisfies(long id, String satisfies) {
        jdbc.update("UPDATE documents SET satisfies = ? WHERE id = ?", satisfies, id);
    }

    @Transactional
    public void replaceValues(long appId, Map<String, byte[]> values) {
        jdbc.update("DELETE FROM field_values WHERE application_id = ?", appId);
        for (Map.Entry<String, byte[]> e : values.entrySet()) {
            jdbc.update("INSERT INTO field_values(application_id, field_key, field_value) VALUES (?,?,?)",
                    appId, e.getKey(), e.getValue());
        }
    }

    public Map<String, byte[]> values(long appId) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        jdbc.query("SELECT field_key, field_value FROM field_values WHERE application_id = ?",
                rs -> {
                    out.put(rs.getString(1), rs.getBytes(2));
                }, appId);
        return out;
    }
}
