package com.zoosool.state;

import com.zoosool.model.ActiveSymbol;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

public class TradeWindowState {

    private final BooleanProperty autoTradeEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty redirectEnabled  = new SimpleBooleanProperty(false);
    private final ObjectProperty<ActiveSymbol> selectedAsset = new SimpleObjectProperty<>(null);
    private final StringProperty basis    = new SimpleStringProperty("payout");
    private final IntegerProperty duration = new SimpleIntegerProperty(2);
    private final StringProperty stake    = new SimpleStringProperty("");

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
}