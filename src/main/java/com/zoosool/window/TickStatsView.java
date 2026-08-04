package com.zoosool.window;

import com.zoosool.analyze.Resetable;
import com.zoosool.analyze.TickStatsSink;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickStatsSnapshot;
import com.zoosool.state.TradeWindowState;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class TickStatsView implements TickStatsSink, Resetable {

    private final TradeWindowState tradeWindowState;
    private final Consumer<Runnable> ui;

    private final VBox root = new VBox(6);
    private final VBox rowsBox = new VBox(4);

    private final Map<String, RowUi> rowsBySymbol = new LinkedHashMap<>();

    private static final Comparator<String> SYMBOL_ORDER = String.CASE_INSENSITIVE_ORDER;

    public TickStatsView(Consumer<Runnable> uiExecutor, TradeWindowState tradeWindowState) {
        this.tradeWindowState = Objects.requireNonNull(tradeWindowState, "tradeWindowState");
        this.ui = Objects.requireNonNull(uiExecutor, "uiExecutor");
        buildUi();
    }

    public Node getNode() {
        return root;
    }

    @Override
    public void onSnapshot(TickStatsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ui.accept(() -> upsertRow(snapshot));
    }

    private void upsertRow(TickStatsSnapshot s) {
        RowUi row = rowsBySymbol.get(s.symbol());
        if (row == null) {
            row = new RowUi(s.symbol());
            rowsBySymbol.put(s.symbol(), row);
            insertRowSorted(row);
        }
        row.apply(s);
    }

    private void insertRowSorted(RowUi newRow) {
        String newSymbol = newRow.symbolText;

        List<Node> children = rowsBox.getChildren();
        int idx = 0;

        while (idx < children.size()) {
            Node n = children.get(idx);
            if (n instanceof GridPane gp) {
                Object ud = gp.getUserData();
                if (ud instanceof String existingSymbol) {
                    if (SYMBOL_ORDER.compare(newSymbol, existingSymbol) <= 0) {
                        break;
                    }
                }
            }
            idx++;
        }

        children.add(idx, newRow.grid);
    }

    private void buildUi() {
        root.setPadding(new Insets(10));
        root.setFillWidth(true);
        root.setMaxWidth(Double.MAX_VALUE);

        root.setStyle("""
                -fx-background-color: rgba(255,255,255,0.06);
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-border-color: rgba(255,255,255,0.10);
                """);

        Label title = new Label("Analyzer");
        title.setStyle("""
                -fx-text-fill: white;
                -fx-font-size: 12px;
                -fx-font-weight: 700;
                """);

        GridPane header = createGridRow();
        header.setPadding(new Insets(3, 6, 3, 6));
        header.setStyle("""
                -fx-background-color: rgba(255,255,255,0.04);
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                -fx-border-color: rgba(255,255,255,0.08);
                """);

        header.add(headerLabel("Symbol"), 0, 0);
        header.add(headerLabel("MA50 Age"), 1, 0);
        header.add(headerLabel("Exhaust"), 2, 0);
        header.add(headerLabel("ADL(L/S)"), 3, 0);
        header.add(headerLabel("XMA(L/S)"), 4, 0);

        rowsBox.setFillWidth(true);
        rowsBox.setMaxWidth(Double.MAX_VALUE);

        root.getChildren().addAll(title, header, rowsBox);
    }

    private static GridPane createGridRow() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(0);
        g.setMaxWidth(Double.MAX_VALUE);

        g.getColumnConstraints().addAll(
                col(26, HPos.LEFT),
                col(16, HPos.LEFT),
                col(14, HPos.LEFT),
                col(22, HPos.LEFT),
                col(22, HPos.LEFT)
        );

        return g;
    }

    private static ColumnConstraints col(double percent, HPos align) {
        ColumnConstraints c = new ColumnConstraints();
        c.setPercentWidth(percent);
        c.setHgrow(Priority.ALWAYS);
        c.setHalignment(align);
        return c;
    }

    private static Label headerLabel(String text) {
        Label l = new Label(text);
        l.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.68);
                -fx-font-size: 10px;
                -fx-font-weight: 700;
                """);
        return l;
    }

    @Override
    public void reset() {
        ui.accept(() -> rowsBySymbol.values().forEach(RowUi::clearValues));
    }

    public void clearAllRows() {
        ui.accept(() -> {
            rowsBySymbol.clear();
            rowsBox.getChildren().clear();
        });
    }

    private final class RowUi {

        final GridPane grid = createGridRow();

        final String symbolText;

        final Label symbol     = cellText("—", true);
        final Label ma50Age    = cellText("NA", true);
        final Label exhaustion = cellText("NA", true);
        final Label adl        = cellText("NA/NA", true);
        final Label xma        = cellText("NA/NA", true);

        RowUi(String symbolText) {
            this.symbolText = Objects.requireNonNull(symbolText, "symbolText");

            grid.setPadding(new Insets(3, 6, 3, 6));
            grid.setStyle(rowStyleNormal());
            grid.setUserData(this.symbolText);

            symbol.setText(this.symbolText);

            adl.setStyle(adl.getStyle() + "-fx-cursor: hand;");
            adl.setOnMouseClicked(e -> {
                tradeWindowState.getSymbols().stream()
                        .filter(s -> s.symbol().equals(this.symbolText))
                        .findFirst()
                        .ifPresent(tradeWindowState::setSelectedAsset);
            });

            grid.add(symbol,     0, 0);
            grid.add(ma50Age,    1, 0);
            grid.add(exhaustion, 2, 0);
            grid.add(adl,        3, 0);
            grid.add(xma,        4, 0);

            GridPane.setFillWidth(symbol,     true);
            GridPane.setFillWidth(ma50Age,    true);
            GridPane.setFillWidth(exhaustion, true);
            GridPane.setFillWidth(adl,        true);
            GridPane.setFillWidth(xma,        true);
        }

        void apply(TickStatsSnapshot s) {
            ma50Age.setText(
                    s.secondsSinceMa50Cross() == null
                            ? "NA"
                            : s.secondsSinceMa50Cross() + "s"
            );

            exhaustion.setText(
                    s.ma50ExhaustionScore() == null
                            ? "NA"
                            : Integer.toString(s.ma50ExhaustionScore())
            );

            adl.setText(
                    fmt2orNA(s.adlLong())
                            + "/"
                            + fmt2orNA(s.adlShort())
            );

            xma.setText(
                    intOrNA(s.xmaLong())
                            + "/"
                            + intOrNA(s.xmaShort())
            );

            grid.setStyle(
                    s.state() == TickStatsState.BANNED
                            ? rowStyleBanned()
                            : rowStyleNormal()
            );
        }

        void clearValues() {
            ma50Age.setText("NA");
            exhaustion.setText("NA");
            adl.setText("NA/NA");
            xma.setText("NA/NA");

            grid.setStyle(rowStyleNormal());
        }
    }

    private static Label cellText(String text, boolean mono) {
        Label l = new Label(text);
        l.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.92);
                -fx-font-size: 11px;
                -fx-font-weight: 700;
                """);

        if (mono) {
            l.setFont(Font.font("Monospaced", 11));
        }

        return l;
    }

    private static String fmt2orNA(Double v) {
        if (v == null || v.isNaN()) {
            return "NA";
        }

        return String.format(
                java.util.Locale.US,
                "%.2f",
                v
        );
    }

    private static String intOrNA(Integer v) {
        return v == null
                ? "NA"
                : Integer.toString(v);
    }

    private static String rowStyleNormal() {
        return """
                -fx-background-color: rgba(255,255,255,0.03);
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                -fx-border-color: rgba(255,255,255,0.06);
                """;
    }

    private static String rowStyleBanned() {
        return """
                -fx-background-color: rgba(239,68,68,0.10);
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                -fx-border-color: rgba(239,68,68,0.22);
                """;
    }
}
