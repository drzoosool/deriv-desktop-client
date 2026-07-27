package com.zoosool.state;

import com.zoosool.deriv.DerivTradingService;
import com.zoosool.enums.TradeMode;
import com.zoosool.model.ActiveSymbol;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

public class TradeWindowState {

    private final BooleanProperty autoTradeEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty redirectEnabled  = new SimpleBooleanProperty(false);
    private final BooleanProperty allowEquals = new SimpleBooleanProperty(false);
    private final ObjectProperty<ActiveSymbol> selectedAsset = new SimpleObjectProperty<>(null);
    private final StringProperty basis    = new SimpleStringProperty("payout");
    private final IntegerProperty duration = new SimpleIntegerProperty(2);
    private final StringProperty stake    = new SimpleStringProperty("");

    // NEW: тип стратегии (радиогруппа), дефолт SNAP
    private final ObjectProperty<TradeMode> tradeMode = new SimpleObjectProperty<>(TradeMode.SNAP);

    // NEW: направление для метронома, дефолт UP; переиспользуем сервисный enum
    private final ObjectProperty<DerivTradingService.Direction> direction =
            new SimpleObjectProperty<>(DerivTradingService.Direction.UP);

    private final ObservableList<ActiveSymbol> symbols = FXCollections.observableArrayList();

    // symbols
    public ObservableList<ActiveSymbol> getSymbols() { return symbols; }
    public void setSymbols(List<ActiveSymbol> list) { symbols.setAll(list); }

    // autoTradeEnabled
    public BooleanProperty autoTradeEnabledProperty() { return autoTradeEnabled; }
    public boolean isAutoTradeEnabled() { return autoTradeEnabled.get(); }
    public void setAutoTradeEnabled(boolean v) { autoTradeEnabled.set(v); }

    // redirectEnabled
    public BooleanProperty redirectEnabledProperty() { return redirectEnabled; }
    public boolean isRedirectEnabled() { return redirectEnabled.get(); }
    public void setRedirectEnabled(boolean v) { redirectEnabled.set(v); }

    // selectedAsset
    public ObjectProperty<ActiveSymbol> selectedAssetProperty() { return selectedAsset; }
    public ActiveSymbol getSelectedAsset() { return selectedAsset.get(); }
    public void setSelectedAsset(ActiveSymbol v) { selectedAsset.set(v); }

    // basis
    public StringProperty basisProperty() { return basis; }
    public String getBasis() { return basis.get(); }
    public void setBasis(String v) { basis.set(v); }

    // duration
    public IntegerProperty durationProperty() { return duration; }
    public int getDuration() { return duration.get(); }
    public void setDuration(int v) { duration.set(v); }

    // stake
    public StringProperty stakeProperty() { return stake; }
    public String getStake() { return stake.get(); }
    public void setStake(String v) { stake.set(v); }

    // call mode
    public BooleanProperty allowEqualsProperty() { return allowEquals; }
    public boolean isAllowEquals() { return allowEquals.get(); }
    public void setAllowEquals(boolean v) { allowEquals.set(v); }

    // NEW: tradeMode
    public ObjectProperty<TradeMode> tradeModeProperty() { return tradeMode; }
    public TradeMode getTradeMode() { return tradeMode.get(); }
    public void setTradeMode(TradeMode v) { tradeMode.set(v); }

    // NEW: direction
    public ObjectProperty<DerivTradingService.Direction> directionProperty() { return direction; }
    public DerivTradingService.Direction getDirection() { return direction.get(); }
    public void setDirection(DerivTradingService.Direction v) { direction.set(v); }
}