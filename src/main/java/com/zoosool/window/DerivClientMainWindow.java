package com.zoosool.window;

import com.zoosool.deriv.DerivOperations;
import com.zoosool.deriv.DerivTradingService;
import com.zoosool.enums.TradeMode;
import com.zoosool.model.ActiveSymbol;
import com.zoosool.model.Contract;
import com.zoosool.model.DerivSession;
import com.zoosool.safari.SafariBridge;
import com.zoosool.state.TradeWindowState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;

import static javafx.collections.FXCollections.observableArrayList;

public class DerivClientMainWindow {

    private final DerivOperations operations;
    private final AppLogView logView;
    private final TradeWindowState state;
    private final SafariBridge safariBridge;

    /*
     * Главный контейнер.
     *
     * Когда график скрыт:
     *
     *   [ rightColumn ]
     *
     * Когда открыт:
     *
     *   [ chart ][ rightColumn ]
     *
     * Никаких spacer'ов между контентом и краями окна нет.
     */
    private final HBox visualArea = new HBox(12);

    private final TextField stakeField = new TextField();

    private final ComboBox<ActiveSymbol> selectorCurrentAsset =
            new ComboBox<>();

    private final ComboBox<Integer> selectorDurationTicks =
            new ComboBox<>(
                    observableArrayList(
                            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 19,
                            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 43, 57, 61)
            );

    private final ComboBox<String> selectorBasis =
            new ComboBox<>(observableArrayList("payout", "stake"));

    private final Button buyButton = new Button("BUY");
    private final Button sellButton = new Button("SELL");
    private final Button buySellButton = new Button("BUY/SELL");
    private final Button buySellSmartButton = new Button("BUY/SELL/s");

    private final Button directionButton = new Button("UP");

    /*
     * График.
     */
    private final StackPane chartHolder = new StackPane();
    private final Button chartToggleButton = new Button("📈 Chart");

    private boolean chartVisible = false;

    /*
     * Ширина самого графика.
     *
     * Правая колонка не прибита к конкретной ширине:
     * JavaFX вычисляет необходимую ширину по форме.
     */
    private static final double CHART_WIDTH = 1000;

    private static final String DURATION_UNIT_T = "t";
    private static final String DURATION_UNIT_S = "s";


    public DerivClientMainWindow(
            DerivOperations operations,
            DerivSession derivSession,
            AppLogView logView,
            TickStatsView statsView,
            TickChartView chartView,
            TradeWindowState state,
            SafariBridge safariBridge
    ) {
        this.operations = operations;
        this.logView = logView;
        this.state = state;
        this.safariBridge = safariBridge;

        /*
         * ─────────────────────────────────────────────────────────────
         * ROOT
         * ─────────────────────────────────────────────────────────────
         */

        visualArea.setPadding(new Insets(14));
        visualArea.setFillHeight(true);
        visualArea.setAlignment(Pos.TOP_LEFT);

        visualArea.setStyle("""
                -fx-background-color: #0f172a;
                """);

        /*
         * ─────────────────────────────────────────────────────────────
         * HEADER
         * ─────────────────────────────────────────────────────────────
         */

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

        styleChartToggle(chartToggleButton);

        chartToggleButton.setOnAction(e -> toggleChart());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Label status = new Label(
                "Currency: " + safe(derivSession.currency())
        );

        status.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.78);
                -fx-font-size: 12px;
                -fx-padding: 6 10 6 10;
                -fx-background-color: rgba(34,197,94,0.14);
                -fx-background-radius: 999;
                -fx-border-color: rgba(34,197,94,0.30);
                -fx-border-radius: 999;
                """);

        // ── MA selector (для графика) ─────────────────────────────────────
        Label maLabel = new Label("MA:");
        maLabel.setStyle("""
        -fx-text-fill: rgba(255,255,255,0.78);
        -fx-font-size: 12px;
        """);

        ComboBox<Integer> maSelector = new ComboBox<>(observableArrayList(16, 20, 50));
        maSelector.setValue(state.getSelectedMaPeriod());
        maSelector.setEditable(false);
        maSelector.setFocusTraversable(false);
        maSelector.setPrefHeight(28);
        styleComboBox(maSelector);
        applyDarkComboBoxCells(maSelector);

        maSelector.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) {
                maSelector.setValue(oldV != null ? oldV : 16);
            } else if (newV != state.getSelectedMaPeriod()) {
                state.setSelectedMaPeriod(newV);
            }
        });

        state.selectedMaPeriodProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && newV.intValue() != (maSelector.getValue() == null ? -1 : maSelector.getValue())) {
                Platform.runLater(() -> maSelector.setValue(newV.intValue()));
            }
        });

        header.getChildren().addAll(title, badge, chartToggleButton, maLabel, maSelector, status);

        /*
         * ─────────────────────────────────────────────────────────────
         * TRADE CARD
         * ─────────────────────────────────────────────────────────────
         */

        VBox card = new VBox(12);

        card.setPadding(new Insets(12));

        card.setStyle("""
                -fx-background-color: rgba(255,255,255,0.06);
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-border-color: rgba(255,255,255,0.10);
                """);

        card.setEffect(
                new DropShadow(
                        18,
                        Color.color(0, 0, 0, 0.35)
                )
        );

        Label cardTitle = new Label("Trade settings");

        cardTitle.setStyle("""
                -fx-text-fill: white;
                -fx-font-size: 13px;
                -fx-font-weight: 700;
                """);

        /*
         * ─────────────────────────────────────────────────────────────
         * FORM
         * ─────────────────────────────────────────────────────────────
         */

        GridPane form = new GridPane();

        form.setHgap(10);
        form.setVgap(10);

        ColumnConstraints labelColumn =
                new ColumnConstraints();

        labelColumn.setMinWidth(120);
        labelColumn.setHgrow(Priority.NEVER);

        ColumnConstraints inputColumn =
                new ColumnConstraints();

        inputColumn.setHgrow(Priority.ALWAYS);
        inputColumn.setFillWidth(true);

        form.getColumnConstraints().addAll(
                labelColumn,
                inputColumn
        );

        /*
         * Asset selector
         */

        List<ActiveSymbol> activeSymbols =
                derivSession.stepIndices();

        selectorCurrentAsset
                .getItems()
                .setAll(activeSymbols);

        selectorCurrentAsset.setEditable(false);

        selectorCurrentAsset.setCellFactory(cb ->
                new ListCell<>() {

                    @Override
                    protected void updateItem(
                            ActiveSymbol item,
                            boolean empty
                    ) {
                        super.updateItem(item, empty);

                        setText(
                                empty || item == null
                                        ? ""
                                        : item.displayName()
                        );

                        setStyle("""
                                -fx-text-fill: white;
                                -fx-background-color: #111827;
                                """);
                    }
                }
        );

        selectorCurrentAsset.setButtonCell(
                new ListCell<>() {

                    @Override
                    protected void updateItem(
                            ActiveSymbol item,
                            boolean empty
                    ) {
                        super.updateItem(item, empty);

                        setText(
                                empty || item == null
                                        ? ""
                                        : item.displayName()
                        );

                        setStyle("""
                                -fx-text-fill: white;
                                -fx-background-color: transparent;
                                """);
                    }
                }
        );

        selectorCurrentAsset
                .valueProperty()
                .addListener((obs, oldV, newV) -> {

                    if (newV == null) {

                        selectorCurrentAsset.setValue(
                                oldV != null
                                        ? oldV
                                        : (
                                        activeSymbols.isEmpty()
                                                ? null
                                                : activeSymbols.get(0)
                                )
                        );

                    } else if (
                            !newV.equals(
                                    state.getSelectedAsset()
                            )
                    ) {
                        state.setSelectedAsset(newV);
                    }
                });

        state.selectedAssetProperty()
                .addListener((obs, oldV, newV) -> {

                    if (newV == null) {
                        return;
                    }

                    if (!newV.equals(
                            selectorCurrentAsset.getValue()
                    )) {
                        Platform.runLater(
                                () -> selectorCurrentAsset
                                        .setValue(newV)
                        );
                    }

                    safariBridge.redirectIfEnabled();
                });

        if (!activeSymbols.isEmpty()) {
            state.setSelectedAsset(
                    activeSymbols.get(0)
            );
        }

        /*
         * Basis selector
         */

        selectorBasis
                .getSelectionModel()
                .selectFirst();

        selectorBasis.setEditable(false);

        selectorBasis
                .valueProperty()
                .addListener((obs, oldV, newV) -> {

                    if (newV == null) {

                        selectorBasis.setValue(
                                oldV != null
                                        ? oldV
                                        : "payout"
                        );

                    } else if (
                            !newV.equals(state.getBasis())
                    ) {
                        state.setBasis(newV);
                    }
                });

        state.basisProperty()
                .addListener((obs, oldV, newV) -> {

                    if (
                            newV != null
                                    && !newV.equals(
                                    selectorBasis.getValue()
                            )
                    ) {
                        Platform.runLater(
                                () -> selectorBasis
                                        .setValue(newV)
                        );
                    }
                });

        /*
         * Duration
         */

        selectorDurationTicks
                .getSelectionModel()
                .selectFirst();

        selectorDurationTicks.setEditable(false);

        selectorDurationTicks
                .valueProperty()
                .addListener((obs, oldV, newV) -> {

                    if (newV == null) {

                        selectorDurationTicks.setValue(
                                oldV != null
                                        ? oldV
                                        : 2
                        );

                    } else if (
                            newV != state.getDuration()
                    ) {
                        state.setDuration(newV);
                    }
                });

        state.durationProperty()
                .addListener((obs, oldV, newV) -> {

                    if (
                            newV != null
                                    && !newV.equals(
                                    selectorDurationTicks
                                            .getValue()
                            )
                    ) {
                        Platform.runLater(
                                () -> selectorDurationTicks
                                        .setValue(
                                                newV.intValue()
                                        )
                        );
                    }
                });

        /*
         * Stake
         */

        stakeField.setPromptText("Stake amount");
        stakeField.setPrefHeight(34);
        stakeField.setStyle(inputStyle());

        stakeField
                .textProperty()
                .addListener((obs, oldV, newV) -> {

                    if (!newV.equals(state.getStake())) {
                        state.setStake(newV);
                    }
                });

        stakeField.setTextFormatter(
                new TextFormatter<>(change -> {

                    String text = change.getText();

                    if (
                            text != null
                                    && text.contains(" ")
                    ) {
                        change.setText(
                                text.replace(" ", "")
                        );
                    }

                    return change;
                })
        );

        state.stakeProperty()
                .addListener((obs, oldV, newV) -> {

                    if (
                            newV != null
                                    && !newV.equals(
                                    stakeField.getText()
                            )
                    ) {
                        Platform.runLater(
                                () -> stakeField
                                        .setText(newV)
                        );
                    }
                });

        /*
         * Styles
         */

        styleComboBox(selectorCurrentAsset);
        styleComboBox(selectorBasis);
        styleComboBox(selectorDurationTicks);

        applyDarkComboBoxCells(selectorBasis);
        applyDarkComboBoxCells(
                selectorDurationTicks
        );

        selectorCurrentAsset.setPrefHeight(34);
        selectorBasis.setPrefHeight(34);
        selectorDurationTicks.setPrefHeight(34);

        selectorCurrentAsset.setMaxWidth(
                Double.MAX_VALUE
        );

        selectorBasis.setMaxWidth(
                Double.MAX_VALUE
        );

        selectorDurationTicks.setMaxWidth(
                Double.MAX_VALUE
        );

        form.add(
                fieldLabel("Current asset"),
                0,
                0
        );

        form.add(
                selectorCurrentAsset,
                1,
                0
        );

        form.add(
                fieldLabel("Pay mode"),
                0,
                1
        );

        form.add(
                selectorBasis,
                1,
                1
        );

        form.add(
                fieldLabel("Duration"),
                0,
                2
        );

        form.add(
                selectorDurationTicks,
                1,
                2
        );

        form.add(
                fieldLabel("Stake"),
                0,
                3
        );

        form.add(
                stakeField,
                1,
                3
        );

        /*
         * ─────────────────────────────────────────────────────────────
         * CHECKBOXES
         * ─────────────────────────────────────────────────────────────
         */

        CheckBox autoTradeCheckBox =
                new CheckBox("Enable auto-trade");

        autoTradeCheckBox.setSelected(
                state.isAutoTradeEnabled()
        );

        autoTradeCheckBox
                .selectedProperty()
                .addListener((obs, oldV, newV) -> {

                    applyCheckBoxStyle(
                            autoTradeCheckBox,
                            newV
                    );

                    if (
                            newV !=
                                    state.isAutoTradeEnabled()
                    ) {
                        state.setAutoTradeEnabled(newV);
                    }
                });

        state.autoTradeEnabledProperty()
                .addListener((obs, oldV, newV) -> {

                    if (
                            newV !=
                                    autoTradeCheckBox
                                            .isSelected()
                    ) {
                        Platform.runLater(() -> {

                            autoTradeCheckBox
                                    .setSelected(newV);

                            applyCheckBoxStyle(
                                    autoTradeCheckBox,
                                    newV
                            );
                        });
                    }

                    logView.log(
                            "Auto-trade: "
                                    + (
                                    newV
                                            ? "ENABLED ✅"
                                            : "DISABLED ⛔"
                            )
                    );
                });

        applyCheckBoxStyle(
                autoTradeCheckBox,
                autoTradeCheckBox.isSelected()
        );

        /*
         * Redirect
         */

        CheckBox redirectCheckBox =
                new CheckBox("Enable redirect");

        redirectCheckBox.setSelected(
                state.isRedirectEnabled()
        );

        redirectCheckBox
                .selectedProperty()
                .addListener((obs, oldV, newV) -> {

                    applyCheckBoxStyle(
                            redirectCheckBox,
                            newV
                    );

                    if (
                            newV !=
                                    state.isRedirectEnabled()
                    ) {
                        state.setRedirectEnabled(newV);
                    }
                });

        state.redirectEnabledProperty()
                .addListener((obs, oldV, newV) -> {

                    if (
                            newV !=
                                    redirectCheckBox
                                            .isSelected()
                    ) {
                        Platform.runLater(() -> {

                            redirectCheckBox
                                    .setSelected(newV);

                            applyCheckBoxStyle(
                                    redirectCheckBox,
                                    newV
                            );
                        });
                    }

                    logView.log(
                            "Redirect: "
                                    + (
                                    newV
                                            ? "ENABLED ✅"
                                            : "DISABLED ⛔"
                            )
                    );
                });

        applyCheckBoxStyle(
                redirectCheckBox,
                redirectCheckBox.isSelected()
        );

        /*
         * Allow equals
         */

        CheckBox allowEqualsCheckBox =
                new CheckBox("Allow equals");

        allowEqualsCheckBox.setSelected(
                state.isAllowEquals()
        );

        allowEqualsCheckBox
                .selectedProperty()
                .addListener((obs, oldV, newV) -> {

                    applyCheckBoxStyle(
                            allowEqualsCheckBox,
                            newV
                    );

                    if (
                            newV !=
                                    state.isAllowEquals()
                    ) {
                        state.setAllowEquals(newV);
                    }
                });

        state.allowEqualsProperty()
                .addListener((obs, oldV, newV) -> {

                    if (
                            newV !=
                                    allowEqualsCheckBox
                                            .isSelected()
                    ) {
                        Platform.runLater(() -> {

                            allowEqualsCheckBox
                                    .setSelected(newV);

                            applyCheckBoxStyle(
                                    allowEqualsCheckBox,
                                    newV
                            );
                        });
                    }
                });

        applyCheckBoxStyle(
                allowEqualsCheckBox,
                allowEqualsCheckBox.isSelected()
        );

        /*
         * ─────────────────────────────────────────────────────────────
         * BUTTONS
         * ─────────────────────────────────────────────────────────────
         */

        HBox buttons = new HBox(
                10,
                buySellSmartButton,
                buySellButton,
                buyButton,
                sellButton
        );

        buttons.setAlignment(Pos.CENTER_LEFT);

        HBox checkBoxes = new HBox(
                16,
                autoTradeCheckBox,
                redirectCheckBox,
                allowEqualsCheckBox
        );

        checkBoxes.setAlignment(Pos.CENTER_LEFT);

        /*
         * Strategy
         */

        ToggleGroup modeGroup =
                new ToggleGroup();

        RadioButton snapRadio =
                new RadioButton("Snap");

        RadioButton metronomeRadio =
                new RadioButton("Metronome");

        snapRadio.setToggleGroup(modeGroup);
        metronomeRadio.setToggleGroup(modeGroup);

        snapRadio.setUserData(
                TradeMode.SNAP
        );

        metronomeRadio.setUserData(
                TradeMode.METRONOME
        );

        applyRadioStyle(snapRadio);
        applyRadioStyle(metronomeRadio);

        if (
                state.getTradeMode()
                        == TradeMode.METRONOME
        ) {
            metronomeRadio.setSelected(true);
        } else {
            snapRadio.setSelected(true);
        }

        modeGroup
                .selectedToggleProperty()
                .addListener((obs, oldT, newT) -> {

                    if (newT == null) {
                        return;
                    }

                    TradeMode mode =
                            (TradeMode) newT.getUserData();

                    if (
                            mode != state.getTradeMode()
                    ) {
                        state.setTradeMode(mode);

                        logView.log(
                                "Trade mode: " + mode
                        );
                    }
                });

        state.tradeModeProperty()
                .addListener((obs, oldV, newV) -> {

                    if (newV == null) {
                        return;
                    }

                    Platform.runLater(() -> {

                        if (
                                newV ==
                                        TradeMode.METRONOME
                                        && !metronomeRadio
                                        .isSelected()
                        ) {
                            metronomeRadio
                                    .setSelected(true);

                        } else if (
                                newV ==
                                        TradeMode.SNAP
                                        && !snapRadio
                                        .isSelected()
                        ) {
                            snapRadio
                                    .setSelected(true);
                        }
                    });
                });

        applyDirectionButtonStyle(
                directionButton,
                state.getDirection()
        );

        directionButton.setPrefHeight(34);
        directionButton.setMinWidth(90);

        directionButton.setOnAction(e -> {

            DerivTradingService.Direction current =
                    state.getDirection();

            DerivTradingService.Direction next =
                    current ==
                            DerivTradingService.Direction.UP
                            ? DerivTradingService.Direction.DOWN
                            : DerivTradingService.Direction.UP;

            state.setDirection(next);
        });

        state.directionProperty()
                .addListener((obs, oldV, newV) -> {

                    if (newV == null) {
                        return;
                    }

                    Platform.runLater(
                            () -> applyDirectionButtonStyle(
                                    directionButton,
                                    newV
                            )
                    );
                });

        Label modeLabel =
                new Label("Strategy:");

        modeLabel.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.78);
                -fx-font-size: 12px;
                """);

        HBox strategyRow = new HBox(
                12,
                modeLabel,
                snapRadio,
                metronomeRadio,
                directionButton
        );

        strategyRow.setAlignment(
                Pos.CENTER_LEFT
        );

        VBox buttonsBox = new VBox(
                8,
                buttons,
                checkBoxes,
                strategyRow
        );

        styleButtons();

        /*
         * ─────────────────────────────────────────────────────────────
         * BUTTON HANDLERS
         * ─────────────────────────────────────────────────────────────
         */

        buyButton.setOnAction(e -> {

            Contract contract =
                    buildContractOrLog();

            if (contract == null) {
                return;
            }

            logView.log(
                    "BUY clicked. stake="
                            + state.getStake()
                            + ", asset="
                            + state
                            .getSelectedAsset()
                            .symbol()
            );

            operations.buy(contract);

            state.setStake("");
        });

        sellButton.setOnAction(e -> {

            Contract contract =
                    buildContractOrLog();

            if (contract == null) {
                return;
            }

            logView.log(
                    "SELL clicked. stake="
                            + state.getStake()
                            + ", asset="
                            + state
                            .getSelectedAsset()
                            .symbol()
            );

            operations.sell(contract);

            state.setStake("");
        });

        buySellButton.setOnAction(e -> {

            Contract contract =
                    buildContractOrLog();

            if (contract == null) {
                return;
            }

            logView.log(
                    "BUY/SELL clicked. stake="
                            + state.getStake()
                            + ", asset="
                            + state
                            .getSelectedAsset()
                            .symbol()
            );

            operations.buySell(contract);

            state.setStake("");
        });

        buySellSmartButton.setOnAction(e -> {

            Contract contract =
                    buildSmartContractOrLog();

            if (contract == null) {
                return;
            }

            logView.log(
                    "BUY/SELL smart clicked. stake="
                            + state.getStake()
                            + ", asset="
                            + state
                            .getSelectedAsset()
                            .symbol()
            );

            operations.buySellS(contract);

            state.setStake("");
        });

        /*
         * ─────────────────────────────────────────────────────────────
         * LOG
         * ─────────────────────────────────────────────────────────────
         */

        VBox logBox = new VBox(8);

        logBox.setPadding(
                new Insets(10)
        );

        logBox.setStyle("""
                -fx-background-color: rgba(255,255,255,0.04);
                -fx-background-radius: 14;
                -fx-border-radius: 14;
                -fx-border-color: rgba(255,255,255,0.10);
                """);

        logBox.getChildren()
                .add(logView.getNode());

        TitledPane logPane =
                new TitledPane(
                        "Log",
                        logBox
                );

        logPane.setExpanded(true);
        logPane.setCollapsible(true);

        logPane.setStyle("""
                -fx-text-fill: white;
                """);

        /*
         * Card contents
         */

        card.getChildren()
                .addAll(
                        cardTitle,
                        form,
                        buttonsBox
                );

        /*
         * ─────────────────────────────────────────────────────────────
         * RIGHT COLUMN
         * ─────────────────────────────────────────────────────────────
         *
         * ВАЖНО:
         *
         * здесь нет жёсткого maxWidth.
         *
         * Колонка получает ширину, необходимую её содержимому.
         * Поэтому окно после sizeToScene() будет ровно по форме.
         */

        VBox rightColumn = new VBox(10);

        rightColumn.getChildren()
                .addAll(
                        header,
                        statsView.getNode(),
                        card,
                        logPane
                );

        rightColumn.setFillWidth(true);

        /*
         * Небольшая нижняя граница нужна только для того,
         * чтобы интерфейс не мог схлопнуться совсем.
         *
         * Реальная ширина определяется содержимым.
         */
        rightColumn.setMinWidth(500);

        HBox.setHgrow(
                rightColumn,
                Priority.NEVER
        );

        /*
         * ─────────────────────────────────────────────────────────────
         * CHART
         * ─────────────────────────────────────────────────────────────
         */

        Node chartNode =
                chartView.getNode();

        chartHolder.getChildren()
                .add(chartNode);

        chartHolder.setStyle("""
                -fx-background-color: rgba(255,255,255,0.04);
                -fx-background-radius: 16;
                """);

        /*
         * Стартуем БЕЗ графика.
         *
         * managed=false — ключевая вещь.
         * JavaFX полностью игнорирует chartHolder
         * при расчёте размера HBox.
         */
        hideChart();

        HBox.setHgrow(
                chartHolder,
                Priority.NEVER
        );

        /*
         * НИКАКОГО hSpacer здесь больше нет.
         */
        visualArea.getChildren()
                .addAll(
                        chartHolder,
                        rightColumn
                );

        /*
         * ─────────────────────────────────────────────────────────────
         * INITIAL WINDOW SIZE
         * ─────────────────────────────────────────────────────────────
         *
         * Как только visualArea попадёт в Scene/Stage,
         * автоматически подгоняем Stage под фактический контент.
         *
         * Поэтому внешний код больше не обязан заранее знать,
         * какой ширины должно быть окно.
         */

        /*
         * ─────────────────────────────────────────────────────────────
         * FOCUS
         * ─────────────────────────────────────────────────────────────
         */

        buyButton.setFocusTraversable(false);
        sellButton.setFocusTraversable(false);
        buySellButton.setFocusTraversable(false);
        buySellSmartButton.setFocusTraversable(false);
        directionButton.setFocusTraversable(false);

        autoTradeCheckBox.setFocusTraversable(false);
        redirectCheckBox.setFocusTraversable(false);
        allowEqualsCheckBox.setFocusTraversable(false);

        snapRadio.setFocusTraversable(false);
        metronomeRadio.setFocusTraversable(false);

        selectorCurrentAsset.setFocusTraversable(false);
        selectorBasis.setFocusTraversable(false);
        selectorDurationTicks.setFocusTraversable(false);
    }


    public Parent getVisualArea() {
        return visualArea;
    }


    /*
     * ═════════════════════════════════════════════════════════════════
     * CHART
     * ═════════════════════════════════════════════════════════════════
     */

    private void toggleChart() {

        chartVisible = !chartVisible;

        if (chartVisible) {
            showChart();

            chartToggleButton.setText(
                    "📈 Chart ◀"
            );
        } else {
            hideChart();

            chartToggleButton.setText(
                    "📈 Chart"
            );
        }

        /*
         * Layout должен сначала увидеть новое managed/visible состояние.
         */
        visualArea.requestLayout();

        /*
         * После layout пересчитываем Stage.
         */
        Platform.runLater(
                this::fitStageToContentKeepingRightEdge
        );
    }


    private void showChart() {

        chartHolder.setMinWidth(CHART_WIDTH);
        chartHolder.setPrefWidth(CHART_WIDTH);
        chartHolder.setMaxWidth(CHART_WIDTH);

        /*
         * Сначала managed, затем visible.
         */
        chartHolder.setManaged(true);
        chartHolder.setVisible(true);
    }


    private void hideChart() {

        /*
         * Скрываем и полностью исключаем из layout.
         */
        chartHolder.setVisible(false);
        chartHolder.setManaged(false);

        chartHolder.setMinWidth(0);
        chartHolder.setPrefWidth(0);
        chartHolder.setMaxWidth(0);
    }


    /*
     * Подгоняет размер окна к текущему содержимому,
     * сохраняя положение ПРАВОГО края.
     *
     * Было:
     *
     *             ┌──── form ────┐
     *             │              │
     *             └──────────────┘
     *
     * Chart ON:
     *
     * ┌──── chart ────┬──── form ────┐
     * │               │              │
     * └───────────────┴──────────────┘
     *
     * Правая граница остаётся там же.
     */
    private void fitStageToContentKeepingRightEdge() {

        Stage stage = getStage();

        if (stage == null) {
            return;
        }

        double oldRightEdge =
                stage.getX()
                        + stage.getWidth();

        /*
         * CSS + layout перед определением pref size.
         */
        Scene scene = visualArea.getScene();

        if (scene != null) {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }

        /*
         * JavaFX сам вычисляет размер Stage
         * по текущему managed-контенту.
         */
        stage.sizeToScene();

        /*
         * Возвращаем правую границу на прежнее место.
         */
        double newX =
                oldRightEdge
                        - stage.getWidth();

        /*
         * Простая защита от ухода окна
         * за левую границу экрана.
         */
        stage.setX(
                Math.max(0, newX)
        );
    }

    private Stage getStage() {

        Scene scene =
                visualArea.getScene();

        if (scene == null) {
            return null;
        }

        Window window =
                scene.getWindow();

        if (window instanceof Stage stage) {
            return stage;
        }

        return null;
    }


    /*
     * ═════════════════════════════════════════════════════════════════
     * HOTKEYS
     * ═════════════════════════════════════════════════════════════════
     */

    public void handleHotkey(KeyEvent e) {

        switch (e.getCode()) {

            case UP -> {

                state.setDirection(
                        DerivTradingService.Direction.UP
                );

                logView.log(
                        "Direction: UP (key)"
                );

                e.consume();
            }

            case DOWN -> {

                state.setDirection(
                        DerivTradingService.Direction.DOWN
                );

                logView.log(
                        "Direction: DOWN (key)"
                );

                e.consume();
            }

            case SPACE -> {

                boolean next =
                        !state.isAutoTradeEnabled();

                state.setAutoTradeEnabled(next);

                logView.log(
                        "Auto-trade toggled by key: "
                                + (
                                next
                                        ? "ON"
                                        : "OFF"
                        )
                );

                e.consume();
            }

            default -> {
            }
        }
    }


    /*
     * ═════════════════════════════════════════════════════════════════
     * CONTRACT
     * ═════════════════════════════════════════════════════════════════
     */

    private Contract buildContractOrLog() {

        BigDecimal stake =
                parseStakeOrLog();

        if (stake == null) {
            return null;
        }

        ActiveSymbol asset =
                state.getSelectedAsset();

        if (asset == null) {

            logView.log(
                    "Current asset is not selected"
            );

            return null;
        }

        return new Contract(
                asset.symbol(),
                stake,
                state.getDuration(),
                state.getDuration() > 10
                        ? DURATION_UNIT_S
                        : DURATION_UNIT_T,
                state.getBasis(),
                state.isAllowEquals()
        );
    }


    private Contract buildSmartContractOrLog() {

        BigDecimal stake =
                parseStakeOrLog();

        if (stake == null) {
            return null;
        }

        ActiveSymbol asset =
                state.getSelectedAsset();

        if (asset == null) {

            logView.log(
                    "Current asset is not selected"
            );

            return null;
        }

        int sec =
                java.time.LocalDateTime
                        .now()
                        .getSecond();

        int remaining =
                59 - sec;

        int duration =
                (remaining & 1) == 1
                        ? remaining
                        : Math.max(
                        1,
                        remaining - 1
                );

        return new Contract(
                asset.symbol(),
                stake,
                duration,
                DURATION_UNIT_S,
                state.getBasis(),
                state.isAllowEquals()
        );
    }


    private BigDecimal parseStakeOrLog() {

        String raw =
                state.getStake();

        if (
                raw == null
                        || raw.isBlank()
        ) {
            logView.log(
                    "Stake is empty"
            );

            return null;
        }

        try {

            BigDecimal stake =
                    new BigDecimal(
                            raw.trim()
                    );

            if (stake.signum() <= 0) {

                logView.log(
                        "Stake must be > 0"
                );

                return null;
            }

            return stake;

        } catch (NumberFormatException ex) {

            logView.log(
                    "Invalid stake: " + raw
            );

            return null;
        }
    }


    /*
     * ═════════════════════════════════════════════════════════════════
     * STYLES
     * ═════════════════════════════════════════════════════════════════
     */

    private void styleChartToggle(Button button) {

        button.setFocusTraversable(false);

        button.setStyle("""
                -fx-background-color: rgba(255,255,255,0.10);
                -fx-text-fill: white;
                -fx-font-size: 12px;
                -fx-font-weight: 700;
                -fx-background-radius: 999;
                -fx-padding: 4 12 4 12;
                -fx-cursor: hand;
                """);
    }


    private static void applyCheckBoxStyle(
            CheckBox checkBox,
            boolean selected
    ) {

        checkBox.setStyle(
                selected
                        ? """
                        -fx-text-fill: rgba(255,255,255,0.85);
                        -fx-font-size: 12px;
                        -fx-mark-color: black;
                        -fx-background-color: white, white, #22c55e;
                        """
                        : """
                        -fx-text-fill: rgba(255,255,255,0.85);
                        -fx-font-size: 12px;
                        -fx-mark-color: black;
                        -fx-background-color: rgba(255,255,255,0.15), rgba(255,255,255,0.15), #1e293b;
                        """
        );
    }


    private static void applyRadioStyle(
            RadioButton radioButton
    ) {

        radioButton.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.85);
                -fx-font-size: 12px;
                -fx-mark-color: black;
                """);
    }


    private static void applyDirectionButtonStyle(
            Button button,
            DerivTradingService.Direction direction
    ) {

        boolean up =
                direction ==
                        DerivTradingService.Direction.UP;

        button.setText(
                up
                        ? "▲ UP"
                        : "▼ DOWN"
        );

        button.setStyle("""
                -fx-background-color: %s;
                -fx-text-fill: white;
                -fx-font-weight: 800;
                -fx-background-radius: 12;
                -fx-padding: 8 14 8 14;
                -fx-cursor: hand;
                """.formatted(
                up
                        ? "#22c55e"
                        : "#ef4444"
        ));
    }


    private void styleButtons() {

        stylePrimary(
                buySellSmartButton,
                "#7c3aaa"
        );

        stylePrimary(
                buySellButton,
                "#7c3aed"
        );

        stylePrimary(
                buyButton,
                "#22c55e"
        );

        stylePrimary(
                sellButton,
                "#ef4444"
        );

        buySellSmartButton.setPrefHeight(36);
        buySellButton.setPrefHeight(36);
        buyButton.setPrefHeight(36);
        sellButton.setPrefHeight(36);

        buySellSmartButton.setMinWidth(120);
        buySellButton.setMinWidth(120);
        buyButton.setMinWidth(90);
        sellButton.setMinWidth(90);
    }


    private void stylePrimary(
            Button button,
            String color
    ) {

        button.setStyle("""
                -fx-background-color: %s;
                -fx-text-fill: white;
                -fx-font-weight: 800;
                -fx-background-radius: 12;
                -fx-padding: 8 14 8 14;
                -fx-cursor: hand;
                """.formatted(color));

        button.setOnMouseEntered(
                e -> button.setOpacity(0.92)
        );

        button.setOnMouseExited(
                e -> button.setOpacity(1.0)
        );
    }


    private void styleComboBox(
            ComboBox<?> comboBox
    ) {

        comboBox.setStyle("""
                -fx-background-color: rgba(255,255,255,0.08);
                -fx-background-radius: 10;
                -fx-border-radius: 10;
                -fx-border-color: rgba(255,255,255,0.12);
                -fx-padding: 2 8 2 8;
                """);
    }


    private static <T> void applyDarkComboBoxCells(
            ComboBox<T> comboBox
    ) {

        comboBox.setCellFactory(
                listView ->
                        new ListCell<>() {

                            @Override
                            protected void updateItem(
                                    T item,
                                    boolean empty
                            ) {
                                super.updateItem(
                                        item,
                                        empty
                                );

                                setText(
                                        empty || item == null
                                                ? ""
                                                : item.toString()
                                );

                                setStyle("""
                                        -fx-text-fill: white;
                                        -fx-background-color: #111827;
                                        """);
                            }
                        }
        );

        comboBox.setButtonCell(
                new ListCell<>() {

                    @Override
                    protected void updateItem(
                            T item,
                            boolean empty
                    ) {
                        super.updateItem(
                                item,
                                empty
                        );

                        setText(
                                empty || item == null
                                        ? ""
                                        : item.toString()
                        );

                        setStyle("""
                                -fx-text-fill: white;
                                -fx-background-color: transparent;
                                """);
                    }
                }
        );
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


    private Label fieldLabel(
            String text
    ) {

        Label label =
                new Label(text + ":");

        label.setStyle("""
                -fx-text-fill: rgba(255,255,255,0.78);
                -fx-font-size: 12px;
                """);

        return label;
    }


    private String safe(String value) {

        return (
                value == null
                        || value.isBlank()
        )
                ? "N/A"
                : value;
    }
}
