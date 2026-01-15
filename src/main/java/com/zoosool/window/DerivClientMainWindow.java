package com.zoosool.window;

import com.zoosool.deriv.DerivOperations;
import com.zoosool.model.ActiveSymbol;
import com.zoosool.model.Contract;
import com.zoosool.model.DerivSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.math.BigDecimal;
import java.util.List;

import static javafx.collections.FXCollections.observableArrayList;

public class DerivClientMainWindow {

    private final DerivOperations operations;
    private final AppLogView logView;

    private final VBox visualArea = new VBox(10);

    private final TextField stakeField = new TextField();

    private final ComboBox<ActiveSymbol> selectorCurrentAsset = new ComboBox<>();
    private final ComboBox<Integer> selectorDurationTicks = new ComboBox<>(observableArrayList(2, 4, 6, 8, 10));
    private final ComboBox<String> selectorBasis = new ComboBox<>(observableArrayList("payout", "stake"));

    private final Button buyButton = new Button("BUY");
    private final Button sellButton = new Button("SELL");
    private final Button buySellButton = new Button("BUY/SELL");

    private static final String DURATION_UNIT = "t";

    public DerivClientMainWindow(DerivOperations operations, DerivSession derivSession, AppLogView logView, TickStatsView statsView) {
        this.operations = operations;
        this.logView = logView;

        // Root styling (dark background)
        visualArea.setPadding(new Insets(14));
        visualArea.setStyle("""
                -fx-background-color: #0f172a;
                """);

        // Header (title + currency)
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 12, 10, 12));
        header.setStyle("""
                -fx-background-color: rgba(255,255,255,0.06);
                -fx-background-radius: 14;
                -fx-border-radius: 14;
                -fx-border-color: rgba(255,255,255,0.10);
                """);

        Label title = new Label("Deriv Desktop Client");
        title.setStyle("""
                -fx-text-fill: white;
                -fx-font-size: 16px;
                -fx-font-weight: 700;
                """);

        Label badge = new Label("MVP");
        badge.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.70);
                -fx-font-size: 12px;
                -fx-padding: 2 8 2 8;
                -fx-background-color: rgba(255,255,255,0.08);
                -fx-background-radius: 999;
                """);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label status = new Label("Currency: " + safe(derivSession.currency()));
        status.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.78);
                -fx-font-size: 12px;
                -fx-padding: 6 10 6 10;
                -fx-background-color: rgba(34,197,94,0.14);
                -fx-background-radius: 999;
                -fx-border-color: rgba(34,197,94,0.30);
                -fx-border-radius: 999;
                """);

        header.getChildren().addAll(title, badge, spacer, status);

        // Card container for form + actions
        VBox card = new VBox(12);
        card.setPadding(new Insets(12));
        card.setStyle("""
                -fx-background-color: rgba(255,255,255,0.06);
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-border-color: rgba(255,255,255,0.10);
                """);
        card.setEffect(new DropShadow(18, Color.color(0, 0, 0, 0.35)));

        Label cardTitle = new Label("Trade settings");
        cardTitle.setStyle("""
                -fx-text-fill: white;
                -fx-font-size: 13px;
                -fx-font-weight: 700;
                """);

        // Form grid (2 columns)
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(120);
        c1.setHgrow(Priority.NEVER);

        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        c2.setFillWidth(true);

        form.getColumnConstraints().addAll(c1, c2);

        // ----- Data from session
        List<ActiveSymbol> activeSymbols = derivSession.stepIndices();

        // ---- Current asset selector (mandatory) ----
        selectorCurrentAsset.getItems().setAll(activeSymbols);
        selectorCurrentAsset.setEditable(false);

        selectorCurrentAsset.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(ActiveSymbol item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayName());
                setStyle("""
                        -fx-text-fill: white;
                        -fx-background-color: #111827;
                        """);
            }
        });
        selectorCurrentAsset.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ActiveSymbol item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayName());
                setStyle("""
                        -fx-text-fill: white;
                        -fx-background-color: transparent;
                        """);
            }
        });

        // Default selection (first item), enforce non-null value
        if (!activeSymbols.isEmpty()) {
            selectorCurrentAsset.getSelectionModel().selectFirst();
        }
        selectorCurrentAsset.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) {
                selectorCurrentAsset.setValue(oldV != null ? oldV : (activeSymbols.isEmpty() ? null : activeSymbols.get(0)));
            }
        });

        // Common control styling / sizing
        styleComboBox(selectorCurrentAsset);
        styleComboBox(selectorBasis);
        styleComboBox(selectorDurationTicks);

        // Fix: selected text was black-on-dark (apply dark cell styles)
        applyDarkComboBoxCells(selectorBasis);
        applyDarkComboBoxCells(selectorDurationTicks);

        selectorCurrentAsset.setPrefHeight(34);
        selectorBasis.setPrefHeight(34);
        selectorDurationTicks.setPrefHeight(34);

        selectorCurrentAsset.setMaxWidth(Double.MAX_VALUE);
        selectorBasis.setMaxWidth(Double.MAX_VALUE);
        selectorDurationTicks.setMaxWidth(Double.MAX_VALUE);

        stakeField.setPromptText("Stake amount");
        stakeField.setPrefHeight(34);
        stakeField.setStyle(inputStyle());

        // Duration selector (mandatory)
        selectorDurationTicks.getSelectionModel().selectFirst();
        selectorDurationTicks.setEditable(false);
        selectorDurationTicks.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) selectorDurationTicks.setValue(oldV != null ? oldV : 2);
        });

        // Basis selector (mandatory)
        selectorBasis.getSelectionModel().selectFirst();
        selectorBasis.setEditable(false);
        selectorBasis.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) selectorBasis.setValue(oldV != null ? oldV : "payout");
        });

        // Form rows
        form.add(fieldLabel("Current asset"), 0, 0);
        form.add(selectorCurrentAsset, 1, 0);

        form.add(fieldLabel("Pay mode"), 0, 1);
        form.add(selectorBasis, 1, 1);

        form.add(fieldLabel("Duration"), 0, 2);
        form.add(selectorDurationTicks, 1, 2);

        form.add(fieldLabel("Stake"), 0, 3);
        form.add(stakeField, 1, 3);

        // Buttons row
        HBox buttons = new HBox(10, buySellButton, buyButton, sellButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        styleButtons();

        // Handlers (unchanged logic + symbol kept in Contract)
        buyButton.setOnAction(e -> {
            BigDecimal stake = parseStakeOrLog();
            if (stake == null) return;

            ActiveSymbol asset = selectorCurrentAsset.getValue();
            if (asset == null) {
                logView.log("Current asset is not selected");
                return;
            }

            logView.log("BUY clicked. stake=" + stake + ", asset=" + asset.symbol());

            this.operations.buy(new Contract(
                    asset.symbol(),
                    stake,
                    selectorDurationTicks.getValue(),
                    DURATION_UNIT,
                    selectorBasis.getValue()
            ));

            stakeField.clear();
        });

        sellButton.setOnAction(e -> {
            BigDecimal stake = parseStakeOrLog();
            if (stake == null) return;

            ActiveSymbol asset = selectorCurrentAsset.getValue();
            if (asset == null) {
                logView.log("Current asset is not selected");
                return;
            }

            logView.log("SELL clicked. stake=" + stake + ", asset=" + asset.symbol());

            this.operations.sell(new Contract(
                    asset.symbol(),
                    stake,
                    selectorDurationTicks.getValue(),
                    DURATION_UNIT,
                    selectorBasis.getValue()
            ));

            stakeField.clear();
        });

        buySellButton.setOnAction(e -> {
            BigDecimal stake = parseStakeOrLog();
            if (stake == null) return;

            ActiveSymbol asset = selectorCurrentAsset.getValue();
            if (asset == null) {
                logView.log("Current asset is not selected");
                return;
            }

            logView.log("BUY/SELL clicked. stake=" + stake + ", asset=" + asset.symbol());

            this.operations.buySell(new Contract(
                    asset.symbol(),
                    stake,
                    selectorDurationTicks.getValue(),
                    DURATION_UNIT,
                    selectorBasis.getValue()
            ));

            stakeField.clear();
        });

        // Log (wrap into a titled pane to look cleaner)
        VBox logBox = new VBox(8);
        logBox.setPadding(new Insets(10));
        logBox.setStyle("""
                -fx-background-color: rgba(255,255,255,0.04);
                -fx-background-radius: 14;
                -fx-border-radius: 14;
                -fx-border-color: rgba(255,255,255,0.10);
                """);
        logBox.getChildren().add(logView.getNode());

        TitledPane logPane = new TitledPane("Log", logBox);
        logPane.setExpanded(true);
        logPane.setCollapsible(true);
        logPane.setStyle("""
                -fx-text-fill: white;
                """);

        card.getChildren().addAll(cardTitle, form, buttons);
        visualArea.getChildren().addAll(header, statsView.getNode(), card, logPane);
    }

    public Parent getVisualArea() {
        return visualArea;
    }

    private BigDecimal parseStakeOrLog() {
        String raw = stakeField.getText();
        if (raw == null || raw.isBlank()) {
            logView.log("Stake is empty");
            return null;
        }
        try {
            BigDecimal stake = new BigDecimal(raw.trim());
            if (stake.signum() <= 0) {
                logView.log("Stake must be > 0");
                return null;
            }
            return stake;
        } catch (NumberFormatException ex) {
            logView.log("Invalid stake: " + raw);
            return null;
        }
    }

    private void styleButtons() {
        stylePrimary(buySellButton, "#7c3aed"); // violet
        stylePrimary(buyButton, "#22c55e");     // green
        stylePrimary(sellButton, "#ef4444");    // red

        buySellButton.setPrefHeight(36);
        buyButton.setPrefHeight(36);
        sellButton.setPrefHeight(36);

        buySellButton.setMinWidth(120);
        buyButton.setMinWidth(90);
        sellButton.setMinWidth(90);
    }

    private void stylePrimary(Button b, String color) {
        b.setStyle("""
                -fx-background-color: %s;
                -fx-text-fill: white;
                -fx-font-weight: 800;
                -fx-background-radius: 12;
                -fx-padding: 8 14 8 14;
                -fx-cursor: hand;
                """.formatted(color));

        b.setOnMouseEntered(e -> b.setOpacity(0.92));
        b.setOnMouseExited(e -> b.setOpacity(1.0));
    }

    private void styleComboBox(ComboBox<?> cb) {
        cb.setStyle("""
                -fx-background-color: rgba(255,255,255,0.08);
                -fx-background-radius: 10;
                -fx-border-radius: 10;
                -fx-border-color: rgba(255,255,255,0.12);
                -fx-padding: 2 8 2 8;
                """);
    }

    private static <T> void applyDarkComboBoxCells(ComboBox<T> cb) {
        cb.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toString());
                setStyle("""
                        -fx-text-fill: white;
                        -fx-background-color: #111827;
                        """);
            }
        });

        cb.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toString());
                setStyle("""
                        -fx-text-fill: white;
                        -fx-background-color: transparent;
                        """);
            }
        });
    }

    private String inputStyle() {
        return """
                -fx-background-color: rgba(255,255,255,0.08);
                -fx-background-radius: 10;
                -fx-border-radius: 10;
                -fx-border-color: rgba(255,255,255,0.12);
                -fx-padding: 8 10 8 10;
                -fx-text-fill: white;
                -fx-prompt-text-fill: rgba(255,255,255,0.45);
                """;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text + ":");
        l.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.78);
                -fx-font-size: 12px;
                """);
        return l;
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "N/A" : s;
    }
}
