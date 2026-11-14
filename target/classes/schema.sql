CREATE TABLE IF NOT EXISTS applications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  created TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS documents (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  application_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  original_name VARCHAR(300) NOT NULL,
  stored_name VARCHAR(100) NOT NULL,
  extracted_text BLOB,
  satisfies VARCHAR(300),
  FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS field_values (
  application_id BIGINT NOT NULL,
  field_key VARCHAR(300) NOT NULL,
  field_value BLOB,
  PRIMARY KEY (application_id, field_key),
  FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE
);
