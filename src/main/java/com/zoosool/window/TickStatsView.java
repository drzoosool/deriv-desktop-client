package com.zoosool.window;

import com.zoosool.analyze.Resetable;
import com.zoosool.analyze.TickStatsSink;
import com.zoosool.enums.TickDecision;
import com.zoosool.enums.TickStatsState;
import com.zoosool.model.TickStatsSnapshot;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One row per symbol. Columns are aligned to window width (percent widths).
 * Rows are kept sorted alphabetically by symbol.
 * Updates are thread-safe via uiExecutor (Platform::runLater).
 *
 * Reset behavior:
 * - Does NOT remove rows from UI.
 * - Only clears per-row values to placeholders.
 * This avoids "UI disappears on disconnect" and removes the need for reset dedup logic.
 */
public final class TickStatsView implements TickStatsSink, Resetable {

    private final Consumer<Runnable> ui;

    private final VBox root = new VBox(6);
    private final VBox rowsBox = new VBox(4);

    // We keep rows here; visual order is maintained separately in rowsBox.
    private final Map<String, RowUi> rowsBySymbol = new LinkedHashMap<>();

    // Alphabetical, case-insensitive (stpRNG2/stpRNG10 won't be "natural", but ok for now).
    private static final Comparator<String> SYMBOL_ORDER = String.CASE_INSENSITIVE_ORDER;

    public TickStatsView(Consumer<Runnable> uiExecutor) {
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

        // Find insertion index by comparing against existing row symbols.
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
        header.add(headerLabel("State"), 1, 0);
        header.add(headerLabel("Decision"), 2, 0);
        header.add(headerLabel("ADL(L/S)"), 3, 0);
        header.add(headerLabel("XMA(L/S)"), 4, 0);
        header.add(headerLabel("zS"), 5, 0);

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
                col(24, HPos.LEFT),
                col(16, HPos.LEFT),
                col(16, HPos.LEFT),
                col(18, HPos.LEFT),
                col(18, HPos.LEFT),
                col(8, HPos.RIGHT)
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

    /**
     * Reset = clear values only (keep rows).
     * This prevents "only last row left" effects during reconnect storms.
     */
    @Override
    public void reset() {
        ui.accept(() -> rowsBySymbol.values().forEach(RowUi::clearValues));
    }

    /**
     * If you ever truly need a full wipe (e.g., symbol list changed),
     * call this explicitly from connector/controller, NOT from per-symbol calculators.
     */
    public void clearAllRows() {
        ui.accept(() -> {
            rowsBySymbol.clear();
            rowsBox.getChildren().clear();
        });
    }

    private final class RowUi {
        final GridPane grid = createGridRow();

        final String symbolText;

        final Label symbol = cellText("—", true);
        final Label state = badge("—");
        final Label decision = badge("—");
        final Label adl = cellText("NA/NA", true);
        final Label xma = cellText("NA/NA", true);
        final Label zS = cellText("0", true);

        RowUi(String symbolText) {
            this.symbolText = Objects.requireNonNull(symbolText, "symbolText");

            grid.setPadding(new Insets(3, 6, 3, 6));
            grid.setStyle(rowStyleNormal());
            grid.setUserData(this.symbolText);

            symbol.setText(this.symbolText);

            grid.add(symbol, 0, 0);
            grid.add(state, 1, 0);
            grid.add(decision, 2, 0);
            grid.add(adl, 3, 0);
            grid.add(xma, 4, 0);
            grid.add(zS, 5, 0);

            GridPane.setFillWidth(symbol, true);
            GridPane.setFillWidth(state, true);
            GridPane.setFillWidth(decision, true);
            GridPane.setFillWidth(adl, true);
            GridPane.setFillWidth(xma, true);
            GridPane.setFillWidth(zS, true);

            state.setMaxWidth(Double.MAX_VALUE);
            decision.setMaxWidth(Double.MAX_VALUE);
            state.setAlignment(Pos.CENTER_LEFT);
            decision.setAlignment(Pos.CENTER_LEFT);
        }

        void apply(TickStatsSnapshot s) {
            setBadge(state, s.state().name(), stateColor(s.state()));
            setBadge(decision, s.decision().name(), decisionColor(s.decision()));

            adl.setText(fmt2orNA(s.adlLong()) + "/" + fmt2orNA(s.adlShort()));
            xma.setText(intOrNA(s.xmaLong()) + "/" + intOrNA(s.xmaShort()));
            zS.setText(Integer.toString(s.zeroShort()));

            grid.setStyle(s.state() == TickStatsState.BANNED ? rowStyleBanned() : rowStyleNormal());
        }

        void clearValues() {
            // Keep symbol text, clear everything else.
            setBadge(state, "—", "rgba(255,255,255,0.10)");
            setBadge(decision, "—", "rgba(255,255,255,0.10)");
            adl.setText("NA/NA");
            xma.setText("NA/NA");
            zS.setText("0");
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

    private static Label badge(String text) {
        Label l = new Label(text);
        setBadge(l, text, "rgba(255,255,255,0.10)");
        return l;
    }

    private static void setBadge(Label l, String text, String bg) {
        l.setText(text);
        l.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.92);
                -fx-font-size: 10px;
                -fx-font-weight: 800;
                -fx-padding: 2 7 2 7;
                -fx-background-radius: 999;
                -fx-background-color: %s;
                -fx-border-color: rgba(255,255,255,0.10);
                -fx-border-radius: 999;
                """.formatted(bg));
    }

    private static String fmt2orNA(Double v) {
        if (v == null || v.isNaN()) return "NA";
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String intOrNA(Integer v) {
        return v == null ? "NA" : Integer.toString(v);
    }

    private static String decisionColor(TickDecision d) {
        if (d == null) return "rgba(255,255,255,0.10)";
        return switch (d) {
            case TRADE -> "rgba(34,197,94,0.22)";
            case CAUTION -> "rgba(234,179,8,0.22)";
            case NO_TRADE -> "rgba(239,68,68,0.22)";
            case NA -> "rgba(255,255,255,0.10)";
        };
    }

    private static String stateColor(TickStatsState s) {
        if (s == null) return "rgba(255,255,255,0.10)";
        return switch (s) {
            case OK -> "rgba(34,197,94,0.18)";
            case WARMUP_L, WARMUP_S -> "rgba(148,163,184,0.18)";
            case BANNED -> "rgba(239,68,68,0.18)";
        };
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
