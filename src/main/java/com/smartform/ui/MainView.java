package com.smartform.ui;

import com.smartform.model.Models.*;
import com.smartform.service.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class MainView {

    private static final String NONE = "(none)";

    private final ApplicationService apps;
    private final FormAnalyzer analyzer;
    private final FormFillService filler;
    private final ConsistencyChecker checker;
    private final ValidationService validator;
    private final PackageService packager;

    private Stage stage;
    private FormAnalysis analysis;

    private final ComboBox<AppRecord> appBox = new ComboBox<>();
    private final Label status = new Label("Ready");
    private final ProgressIndicator busy = new ProgressIndicator();
    private final TableView<DocRecord> docTable = new TableView<>();
    private final TableView<FormField> fieldTable = new TableView<>();
    private final ListView<String> requiredDocList = new ListView<>();
    private final ListView<String> ruleList = new ListView<>();
    private final GridPane fillGrid = new GridPane();
    private final VBox ruleBox = new VBox(6);
    private final Map<String, Control> inputs = new LinkedHashMap<>();
    private final List<CheckBox> ruleChecks = new ArrayList<>();
    private final TextArea consistencyArea = new TextArea();
    private final TextArea validationArea = new TextArea();
    private final Button packageBtn = new Button("Generate Submission Package...");

    public MainView(ApplicationService apps, FormAnalyzer analyzer, FormFillService filler,
                    ConsistencyChecker checker, ValidationService validator, PackageService packager) {
        this.apps = apps;
        this.analyzer = analyzer;
        this.filler = filler;
        this.checker = checker;
        this.validator = validator;
        this.packager = packager;
    }

    // ------------------------------------------------------------------ layout

    public BorderPane build(Stage owner) {
        this.stage = owner;
        appBox.setPrefWidth(320);
        Button newBtn = new Button("New Application");
        Button delBtn = new Button("Delete");
        newBtn.setOnAction(e -> newApplication());
        delBtn.setOnAction(e -> deleteApplication());
        ToolBar top = new ToolBar(new Label("Application:"), appBox, newBtn, delBtn);

        TabPane tabs = new TabPane(documentsTab(), analysisTab(), fillTab(), consistencyTab(), validateTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        busy.setMaxSize(18, 18);
        busy.setVisible(false);
        HBox bottom = new HBox(8, busy, status);
        bottom.setPadding(new Insets(6, 10, 6, 10));

        BorderPane root = new BorderPane(tabs);
        root.setTop(top);
        root.setBottom(bottom);

        appBox.valueProperty().addListener((o, a, b) -> loadApplication());
        refreshApps();
        return root;
    }

    private Tab documentsTab() {
        Button pickForm = new Button("Select Application Form...");
        Button add = new Button("Add Supporting Documents...");
        Button type = new Button("Set Document Type...");
        Button remove = new Button("Remove Selected");
        pickForm.setOnAction(e -> selectForm());
        add.setOnAction(e -> addSupporting());
        type.setOnAction(e -> setDocumentType());
        remove.setOnAction(e -> removeSelected());

        docTable.getColumns().add(col("File", DocRecord::originalName));
        docTable.getColumns().add(col("Role", d -> ApplicationService.FORM.equals(d.role()) ? "Application form" : "Supporting"));
        docTable.getColumns().add(col("Satisfies requirement", d -> d.satisfies() == null ? "" : d.satisfies()));
        docTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        VBox box = new VBox(10, new HBox(8, pickForm, add, type, remove), docTable);
        box.setPadding(new Insets(12));
        VBox.setVgrow(docTable, Priority.ALWAYS);
        return new Tab("1. Documents", box);
    }

    private Tab analysisTab() {
        fieldTable.getColumns().add(col("Field", FormField::label));
        fieldTable.getColumns().add(col("Type", f -> f.type().name()));
        fieldTable.getColumns().add(col("Required", f -> f.required() ? "Yes" : "No"));
        fieldTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox box = new VBox(6, new Label("Fields"), fieldTable,
                new Label("Required documents"), requiredDocList,
                new Label("Conditional requirements"), ruleList);
        box.setPadding(new Insets(12));
        VBox.setVgrow(fieldTable, Priority.ALWAYS);
        VBox.setVgrow(requiredDocList, Priority.SOMETIMES);
        VBox.setVgrow(ruleList, Priority.SOMETIMES);
        return new Tab("2. Analysis", box);
    }

    private Tab fillTab() {
        Button auto = new Button("Auto-fill from Documents");
        Button save = new Button("Save");
        auto.setOnAction(e -> autoFill());
        save.setOnAction(e -> {
            saveValues();
            status.setText("Saved.");
        });
        fillGrid.setHgap(10);
        fillGrid.setVgap(8);
        VBox content = new VBox(16, fillGrid, new Separator(), ruleBox);
        content.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        VBox box = new VBox(10, new HBox(8, auto, save), scroll);
        box.setPadding(new Insets(12));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return new Tab("3. Complete Form", box);
    }

    private Tab consistencyTab() {
        Button check = new Button("Check Consistency Across Documents");
        check.setOnAction(e -> checkConsistency());
        consistencyArea.setEditable(false);
        VBox box = new VBox(10, check, consistencyArea);
        box.setPadding(new Insets(12));
        VBox.setVgrow(consistencyArea, Priority.ALWAYS);
        return new Tab("4. Inconsistencies", box);
    }

    private Tab validateTab() {
        Button validate = new Button("Validate Application");
        validate.setOnAction(e -> validateApplication());
        packageBtn.setDisable(true);
        packageBtn.setOnAction(e -> generatePackage());
        validationArea.setEditable(false);
        VBox box = new VBox(10, new HBox(8, validate, packageBtn), validationArea);
        box.setPadding(new Insets(12));
        VBox.setVgrow(validationArea, Priority.ALWAYS);
        return new Tab("5. Validate & Package", box);
    }

    // ------------------------------------------------------------------ applications

    private void refreshApps() {
        appBox.getItems().setAll(apps.applications());
        if (!appBox.getItems().isEmpty() && appBox.getValue() == null) {
            appBox.getSelectionModel().selectFirst();
        }
    }

    private void newApplication() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Application");
        dialog.setHeaderText("Name this application");
        dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).ifPresent(name -> {
            AppRecord created = apps.create(name);
            appBox.getItems().add(0, created);
            appBox.setValue(created);
        });
    }

    private void deleteApplication() {
        AppRecord a = appBox.getValue();
        if (a == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete '" + a.name() + "' and all of its documents?", ButtonType.YES, ButtonType.NO);
        if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            apps.delete(a.id());
            appBox.getItems().remove(a);
            appBox.setValue(null);
            refreshApps();
            loadApplication();
        }
    }

    private void loadApplication() {
        analysis = null;
        packageBtn.setDisable(true);
        consistencyArea.clear();
        validationArea.clear();
        if (appBox.getValue() == null) {
            docTable.getItems().clear();
            updateAnalysisViews();
            buildFillForm();
            return;
        }
        refreshDocs();
        analyze();
    }

    // ------------------------------------------------------------------ documents & analysis

    private void refreshDocs() {
        AppRecord a = appBox.getValue();
        docTable.getItems().setAll(a == null ? List.of() : apps.documents(a.id()));
    }

    private void selectForm() {
        AppRecord a = requireApp();
        if (a == null) return;
        List<File> files = choose(false);
        if (files.isEmpty()) return;
        run("Reading form (OCR can take a while)...", () -> {
            apps.addDocument(a.id(), ApplicationService.FORM, files.get(0).toPath());
            return null;
        }, v -> {
            refreshDocs();
            analyze();
        });
    }

    private void addSupporting() {
        AppRecord a = requireApp();
        if (a == null) return;
        List<File> files = choose(true);
        if (files.isEmpty()) return;
        List<String> names = requiredNames(analysis);
        run("Reading documents (OCR can take a while)...", () -> {
            for (File f : files) {
                apps.addDocument(a.id(), ApplicationService.SUPPORTING, f.toPath());
            }
            apps.autoAssign(a.id(), names);
            return null;
        }, v -> refreshDocs());
    }

    private void setDocumentType() {
        DocRecord d = docTable.getSelectionModel().getSelectedItem();
        if (d == null || !ApplicationService.SUPPORTING.equals(d.role())) {
            info("Select a supporting document in the table first.");
            return;
        }
        List<String> names = requiredNames(analysis);
        if (names.isEmpty()) {
            info("No required documents were detected in the form.");
            return;
        }
        List<String> choices = new ArrayList<>();
        choices.add(NONE);
        choices.addAll(names);
        String current = d.satisfies() != null && names.contains(d.satisfies()) ? d.satisfies() : NONE;
        ChoiceDialog<String> dialog = new ChoiceDialog<>(current, choices);
        dialog.setTitle("Document type");
        dialog.setHeaderText("Which requirement does '" + d.originalName() + "' satisfy?");
        dialog.showAndWait().ifPresent(choice -> {
            apps.setSatisfies(d.id(), NONE.equals(choice) ? null : choice);
            refreshDocs();
        });
    }

    private void removeSelected() {
        DocRecord d = docTable.getSelectionModel().getSelectedItem();
        if (d == null) return;
        apps.removeDocument(d);
        refreshDocs();
        if (ApplicationService.FORM.equals(d.role())) {
            analyze();
        }
    }

    private void analyze() {
        AppRecord a = appBox.getValue();
        if (a == null) return;
        long id = a.id();
        run("Analyzing form...", () -> {
            Optional<DocRecord> form = apps.form(id);
            if (form.isEmpty()) {
                return null;
            }
            FormAnalysis result = analyzer.analyze(apps.bytes(form.get()), form.get().originalName(),
                    apps.text(form.get()));
            apps.autoAssign(id, requiredNames(result));
            return result;
        }, result -> {
            analysis = result;
            refreshDocs();
            updateAnalysisViews();
            buildFillForm();
        });
    }

    private void updateAnalysisViews() {
        if (analysis == null) {
            fieldTable.getItems().clear();
            requiredDocList.getItems().clear();
            ruleList.getItems().clear();
            return;
        }
        fieldTable.getItems().setAll(analysis.fields());
        requiredDocList.getItems().setAll(analysis.documents().stream().map(RequiredDocument::name).toList());
        ruleList.getItems().setAll(analysis.rules().stream().map(this::describeRule).toList());
    }

    private String describeRule(ConditionalRule r) {
        return (r.negated() ? "Unless " : "If ") + r.condition() + "  ->  " + r.requirement()
                + (r.documentName() != null ? "   [document: " + r.documentName() + "]" : "");
    }

    private static List<String> requiredNames(FormAnalysis a) {
        if (a == null) return List.of();
        Set<String> names = new LinkedHashSet<>();
        a.documents().forEach(d -> names.add(d.name()));
        a.rules().forEach(r -> {
            if (r.documentName() != null) names.add(r.documentName());
        });
        return new ArrayList<>(names);
    }

    // ------------------------------------------------------------------ form completion

    private void buildFillForm() {
        fillGrid.getChildren().clear();
        ruleBox.getChildren().clear();
        inputs.clear();
        ruleChecks.clear();
        AppRecord a = appBox.getValue();
        if (analysis == null || a == null) return;
        Map<String, String> saved = apps.loadValues(a.id());

        int row = 0;
        for (FormField f : analysis.fields()) {
            Label label = new Label(f.label() + (f.required() ? " *" : ""));
            Control input;
            switch (f.type()) {
                case CHECKBOX -> {
                    CheckBox cb = new CheckBox();
                    cb.setSelected("true".equals(saved.get(f.name())));
                    input = cb;
                }
                case CHOICE -> {
                    ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(f.options()));
                    combo.setValue(saved.get(f.name()));
                    input = combo;
                }
                default -> {
                    TextField tf = new TextField(saved.getOrDefault(f.name(), ""));
                    tf.setPrefWidth(420);
                    input = tf;
                }
            }
            inputs.put(f.name(), input);
            fillGrid.addRow(row++, label, input);
        }
        if (!analysis.rules().isEmpty()) {
            ruleBox.getChildren().add(new Label("Conditional requirements - tick those that apply to you:"));
        }
        for (int i = 0; i < analysis.rules().size(); i++) {
            ConditionalRule r = analysis.rules().get(i);
            CheckBox cb = new CheckBox((r.negated() ? "Exception applies (unless " : "Applies (if ") + r.condition()
                    + ")  ->  " + r.requirement());
            cb.setWrapText(true);
            cb.setSelected("true".equals(saved.get(ValidationService.RULE_PREFIX + i)));
            ruleChecks.add(cb);
            ruleBox.getChildren().add(cb);
        }
    }

    private Map<String, String> collectValues() {
        Map<String, String> values = new LinkedHashMap<>();
        inputs.forEach((name, c) -> values.put(name, valueOf(c)));
        for (int i = 0; i < ruleChecks.size(); i++) {
            values.put(ValidationService.RULE_PREFIX + i, String.valueOf(ruleChecks.get(i).isSelected()));
        }
        return values;
    }

    private static String valueOf(Control c) {
        if (c instanceof CheckBox cb) return String.valueOf(cb.isSelected());
        if (c instanceof ComboBox<?> combo) return combo.getValue() == null ? "" : combo.getValue().toString();
        if (c instanceof TextField tf) return tf.getText() == null ? "" : tf.getText().strip();
        return "";
    }

    private void saveValues() {
        AppRecord a = appBox.getValue();
        if (a != null && analysis != null) {
            apps.saveValues(a.id(), collectValues());
        }
    }

    private void autoFill() {
        AppRecord a = requireApp();
        if (a == null || analysis == null) return;
        List<FormField> empty = analysis.fields().stream()
                .filter(f -> f.type() != FieldType.CHECKBOX && valueOf(inputs.get(f.name())).isEmpty()).toList();
        run("Looking up values in your documents...", () -> {
            Map<String, String> texts = docTexts(a.id());
            Map<String, Suggestion> found = new LinkedHashMap<>();
            for (FormField f : empty) {
                Suggestion s = filler.suggest(f, texts);
                if (s != null) found.put(f.name(), s);
            }
            return found;
        }, found -> {
            found.forEach((name, s) -> {
                Control c = inputs.get(name);
                if (c instanceof TextField tf) tf.setText(s.value());
                else if (c instanceof ComboBox<?> combo) {
                    @SuppressWarnings("unchecked") ComboBox<String> cs = (ComboBox<String>) combo;
                    cs.setValue(s.value());
                }
                c.setTooltip(new Tooltip("Suggested from " + s.source()));
            });
            status.setText("Filled " + found.size() + " field(s) from your documents. Please review them.");
        });
    }

    // ------------------------------------------------------------------ checks & package

    private void checkConsistency() {
        AppRecord a = requireApp();
        if (a == null) return;
        saveValues();
        FormAnalysis snapshot = analysis;
        Map<String, String> values = collectValues();
        run("Comparing documents...", () -> checker.check(snapshot, values, docTexts(a.id())), found -> {
            if (found.isEmpty()) {
                consistencyArea.setText("No inconsistencies found across the form and supporting documents.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Inconsistency inc : found) {
                sb.append(inc.concept()).append(" does not match:\n");
                inc.evidence().forEach(e -> sb.append("   ").append(e.source()).append("  ->  ").append(e.value()).append('\n'));
                sb.append('\n');
            }
            consistencyArea.setText(sb.toString());
        });
    }

    private List<Issue> computeIssues(long appId, FormAnalysis a, Map<String, String> values) {
        List<Inconsistency> inc = checker.check(a, values, docTexts(appId));
        return validator.validate(a, values, apps.supporting(appId), inc);
    }

    private void validateApplication() {
        AppRecord a = requireApp();
        if (a == null) return;
        saveValues();
        FormAnalysis snapshot = analysis;
        Map<String, String> values = collectValues();
        run("Validating...", () -> computeIssues(a.id(), snapshot, values), issues -> {
            boolean errors = ValidationService.hasErrors(issues);
            StringBuilder sb = new StringBuilder();
            sb.append(errors ? "The application is NOT ready for submission.\n\n"
                    : "The application is valid and ready to be packaged.\n\n");
            issues.forEach(i -> sb.append('[').append(i.severity()).append("] ").append(i.message()).append('\n'));
            validationArea.setText(sb.toString());
            packageBtn.setDisable(errors);
        });
    }

    private void generatePackage() {
        AppRecord a = requireApp();
        if (a == null) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose where to save the submission package");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;
        saveValues();
        FormAnalysis snapshot = analysis;
        Map<String, String> values = collectValues();
        run("Generating package...", () -> {
            if (ValidationService.hasErrors(computeIssues(a.id(), snapshot, values))) {
                throw new IllegalStateException("The application no longer passes validation. Validate again.");
            }
            DocRecord form = apps.form(a.id()).orElseThrow();
            return packager.build(dir.toPath(), a.name(), snapshot, values, form, apps.supporting(a.id()));
        }, (Path out) -> {
            status.setText("Package created: " + out);
            info("Submission package created:\n" + out);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> docTexts(long appId) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (DocRecord d : apps.supporting(appId)) {
            String key = texts.containsKey(d.originalName()) ? d.originalName() + " (" + d.id() + ")" : d.originalName();
            texts.put(key, apps.text(d));
        }
        return texts;
    }

    private List<File> choose(boolean multiple) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF and images",
                "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.tif", "*.tiff", "*.bmp", "*.gif"));
        if (multiple) {
            List<File> files = chooser.showOpenMultipleDialog(stage);
            return files == null ? List.of() : files;
        }
        File f = chooser.showOpenDialog(stage);
        return f == null ? List.of() : List.of(f);
    }

    private AppRecord requireApp() {
        AppRecord a = appBox.getValue();
        if (a == null) {
            info("Create or select an application first.");
        }
        return a;
    }

    private <T> void run(String message, Callable<T> job, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return job.call();
            }
        };
        status.setText(message);
        busy.setVisible(true);
        task.setOnSucceeded(e -> {
            busy.setVisible(false);
            status.setText("Ready");
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            busy.setVisible(false);
            status.setText("Failed");
            Throwable t = task.getException();
            new Alert(Alert.AlertType.ERROR, t.getMessage() == null ? t.toString() : t.getMessage()).showAndWait();
        });
        Thread thread = new Thread(task, "smartform-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void info(String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }

    private static <T> TableColumn<T, String> col(String title, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(value.apply(d.getValue())));
        return c;
    }
}
