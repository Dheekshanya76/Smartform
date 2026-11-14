package com.smartform;

import com.smartform.service.CryptoService;
import com.smartform.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class SmartFormApp extends Application {

    private ConfigurableApplicationContext context;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        context = new SpringApplicationBuilder(SmartFormApplication.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) {
        if (!unlock(context.getBean(CryptoService.class))) {
            Platform.exit();
            return;
        }
        MainView view = context.getBean(MainView.class);
        stage.setTitle("SmartForm - Intelligent Form and Document Assistant");
        stage.setScene(new Scene(view.build(stage), 1150, 760));
        stage.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }

    private boolean unlock(CryptoService crypto) {
        boolean first = !crypto.isInitialized();
        while (true) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("SmartForm");
            dialog.setHeaderText(first
                    ? "Create a passphrase to encrypt your documents.\nIt cannot be recovered if lost."
                    : "Enter your passphrase to unlock SmartForm.");
            PasswordField pw = new PasswordField();
            PasswordField confirm = new PasswordField();
            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(8);
            grid.add(new Label("Passphrase"), 0, 0);
            grid.add(pw, 1, 0);
            if (first) {
                grid.add(new Label("Confirm"), 0, 1);
                grid.add(confirm, 1, 1);
            }
            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return false;
            }
            try {
                if (first && (pw.getText().length() < 8 || !pw.getText().equals(confirm.getText()))) {
                    throw new IllegalArgumentException(
                            "Passphrase must be at least 8 characters and match the confirmation.");
                }
                crypto.unlock(pw.getText().toCharArray());
                return true;
            } catch (IllegalArgumentException e) {
                new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
            }
        }
    }
}
