# SmartForm Test Plan

## What you need
- The setup you already have: JDK, Maven, Tesseract (with `smartform.tessdata` set), internet for the first Maven run.
- A PDF viewer (to inspect the generated package).
- The `testdata` folder in this project.

| Folder | Contents |
|---|---|
| `testdata/1_forms/student_visa_form.pdf` | Plain-text form (no fillable fields) with required fields, an optional field, required documents and 3 conditional rules |
| `testdata/1_forms/fillable_form.pdf` | Form with real PDF form fields (text, drop-down, checkbox) |
| `testdata/scenario_A_valid/` | Consistent documents for Priya Sharma: passport_copy, address_proof, bank_statement, photograph.png, police_clearance_certificate, marriage_certificate |
| `testdata/scenario_B_inconsistent/` | passport_copy.pdf (wrong DOB and passport number) and address_proof.pdf (different address) |
| `testdata/scenario_C_scanned/` | scanned_address_proof.png and scanned_passport_copy.pdf (images only, to force OCR) |

---
## Part 1 - Automated tests (about 1 minute)
```
cd C:\smartform\smartform
mvn test
```
**Expected:** `Tests run: 21, Failures: 0, Errors: 0`.
These cover form analysis, required/optional/conditional detection, date/name/phone/ID normalisation, consistency checking, auto-fill suggestions, validation rules, document matching and AES encryption (round trip, wrong passphrase, tamper detection, restart).
They do **not** cover the UI, the database, OCR or PDF generation, so do Part 2.

---
## Part 2 - Manual end-to-end tests
Run `mvn javafx:run`. "Pass" means the result matches the **Expected** line exactly.

### T1. First-run passphrase (fresh start: delete `%USERPROFILE%\.smartform` first)
1. Enter `short` and `short` -> **Expected:** error, passphrase must be at least 8 characters.
2. Enter `longenough1` and `different1` -> **Expected:** error, passphrases do not match.
3. Enter `longenough1` twice -> **Expected:** main window opens.

### T2. Form analysis (text form)
1. New Application -> `T2`. Documents tab -> Select Application Form -> `student_visa_form.pdf`.
2. Analysis tab. **Expected:**
   - 8 fields. Required = Yes for Full Name, Date of Birth (DD/MM/YYYY), Address, Passport Number, Email, Phone Number. Required = No for Occupation and Spouse Name.
   - Required documents: Passport copy / Proof of address / Passport size photograph / Bank statement.
   - 3 conditional requirements:
     - `If you are married -> ... [document: Marriage certificate]`
     - `Unless you are under 18 years of age -> ... [document: Police clearance certificate]`
     - `If you have previously applied -> provide your previous application number` (no document)

### T3. Supporting documents and auto-matching
1. Add Supporting Documents -> select these 5 from `scenario_A_valid`: passport_copy.pdf, address_proof.pdf, bank_statement.pdf, photograph.png, police_clearance_certificate.pdf.
2. **Expected "Satisfies requirement" column:** Passport copy / Proof of address / Bank statement / Passport size photograph / Police clearance certificate (matching the file names).
3. Select a row -> Set Document Type... -> change it, then change it back. **Expected:** the column updates.

### T4. Form assistance
1. Complete Form tab -> Auto-fill from Documents. **Expected:**
   - Full Name = `Priya Sharma`
   - Date of Birth = `14 March 1995`
   - Address = `12 Gandhi Road, Trichy, Tamil Nadu 620001`
   - Passport Number = `P1234567`
   - Email = `priya.sharma@example.com`
   - Phone Number = `+91 98765 43210`
   - Occupation and Spouse Name stay empty.
2. Hover a filled field -> tooltip "Suggested from <file name>".
3. Click Save. Close the app, reopen it, enter the passphrase. **Expected:** application T2, its documents and the saved values are all still there.

### T5. Clean validation and package (happy path)
1. Inconsistencies tab -> Check. **Expected:** "No inconsistencies found...".
2. Validate & Package tab -> Validate Application. **Expected:** "The application is valid and ready to be packaged." and no `[ERROR]` lines. The *Generate Submission Package...* button is now enabled.
3. Generate -> choose a folder. **Expected:** `T2_Submission_Package.pdf`, **7 pages**:
   - p1 original form
   - p2 "Completed Application: T2" listing every field with the values you entered
   - p3 passport, p4 address proof, p5 bank statement, p6 photograph (image on page), p7 police certificate

### T6. Conditional logic
1. Complete Form -> tick **"Applies (if you are married)"** -> Validate. **Expected:** `[ERROR] Conditional document missing (if you are married): Marriage certificate`; Generate button disabled.
2. Add `marriage_certificate.pdf` -> Validate. **Expected:** no errors.
3. Tick **"Applies (if you have previously applied)"** -> Validate. **Expected:** only a `[WARNING]` line, still valid.
4. Tick **"Exception applies (unless you are under 18...)"** and remove police_clearance_certificate.pdf (Documents tab -> Remove Selected) -> Validate. **Expected:** valid (not required for under-18s).
5. Untick that exception -> Validate. **Expected:** `[ERROR] Conditional document missing (unless you are under 18 years of age): Police clearance certificate`.

### T7. Field and format validation
Clear Full Name, set Email `abc`, Date of Birth `99/99/1995`, Phone `123` -> Validate. **Expected errors:**
- `Required field 'Full Name' is empty.`
- `'Email' is not a valid email address.`
- `'Date of Birth (DD/MM/YYYY)' is not a recognised date (use DD/MM/YYYY).`
- `'Phone Number' is not a valid phone number.`

Generate button disabled. Restore valid values afterwards.

### T8. Missing document
Remove bank_statement.pdf -> Validate. **Expected:** `[ERROR] Required document missing: Bank statement`.

### T9. Inconsistency detection
1. New Application `T9`, select the same form. Add `scenario_B_inconsistent/passport_copy.pdf`, `scenario_B_inconsistent/address_proof.pdf`, and from scenario_A: bank_statement.pdf, photograph.png, police_clearance_certificate.pdf.
2. Type these values by hand (do not use auto-fill): Full Name `Priya Sharma`, DOB `14/03/1995`, Address `12 Gandhi Road, Trichy, Tamil Nadu 620001`, Passport Number `P1234567`, Email `priya.sharma@example.com`, Phone `9876543210`.
3. Inconsistencies tab -> Check. **Expected exactly three mismatches:**
   - **Date of birth:** form 14/03/1995 vs passport_copy.pdf 15 March 1995
   - **Address:** form and bank statement (12 Gandhi Road) vs address_proof.pdf (45 Nehru Street, Madurai)
   - **ID number:** form P1234567 vs passport_copy.pdf P7654321
   - **Name must NOT be flagged** ("Priya Kumari Sharma" contains "Priya Sharma"). Phone and email must not be flagged either.
4. Validate. **Expected:** three `[ERROR] Inconsistent ...` lines; Generate button disabled.

### T10. OCR (scanned documents)
1. New Application `T10`, same form. Add `scenario_C_scanned/scanned_address_proof.png` and `scanned_passport_copy.pdf`. (Wait for the progress indicator; OCR takes a few seconds each.)
2. **Expected:** matched to "Proof of address" and "Passport copy". Auto-fill gives Address = `12 Gandhi Road, Trichy 620001`, DOB `14 March 1995`, Passport Number `P1234567`.
3. If you get an "OCR failed" dialog: recheck `smartform.tessdata` in `application.properties` (folder containing `eng.traineddata`, forward slashes).

### T11. Real PDF form fields
1. New Application `T11`. Select `fillable_form.pdf`. **Expected Analysis:**
   - 7 fields. Required: Full Name, Date of Birth (DD/MM/YYYY), Address, Passport Number, "I declare that the information is true" (CHECKBOX). Not required: Email.
   - Visa Type is a CHOICE with options Student / Work / Tourist.
   - Required documents: Passport copy, Proof of address. One conditional: if married -> Marriage certificate.
2. Add passport_copy.pdf and address_proof.pdf (scenario_A). Auto-fill. Select Visa Type. Leave the checkbox unticked -> Validate. **Expected:** `Required checkbox 'I declare that the information is true' is not ticked.`
3. Tick it -> Validate -> Generate. **Expected:** 3 pages (form, passport, address). Open the PDF: your values are visible in the form on page 1 (no extra "Completed Application" page for fillable forms).

### T12. Encryption and persistence
1. Open `%USERPROFILE%\.smartform\vault`. **Expected:** only `.enc` files; opening one in Notepad shows unreadable binary, and renaming to `.pdf` does not open.
2. Close and reopen the app, enter a **wrong** passphrase. **Expected:** "Wrong passphrase." and the window does not open. Correct passphrase -> data is there.
3. Note: application names and original file names are stored unencrypted; document contents, extracted text and entered values are encrypted.

### T13. Error handling
1. Try to add a `.docx` or `.txt` file. **Expected:** error "Unsupported file type".
2. Select a second form for the same application. **Expected:** it replaces the first form and the analysis refreshes.
3. Remove the form (Documents tab). **Expected:** Analysis and Complete Form tabs clear; Validate shows "No application form has been selected and analyzed."
4. Delete an application. **Expected:** it disappears and its `.enc` files are removed from the vault folder.

---
## Known limitations to keep in mind when judging results
- Field/document/condition detection is rule-based. It is reliable for clearly written forms such as the samples, but a form with unusual wording can be partly missed. Always glance at the Analysis tab.
- Consistency checks only look at lines written as `Label: value` (Name, Date of birth, Address, ID number, Email, Phone). Abbreviations such as "Road" vs "Rd" in an address are reported as a mismatch.
- The submission package contains the completed form followed by **every** supporting document you added, including ones that are not required.
- Dates are read day-first (05/03/1990 = 5 March).
