package com.zoosool.safari;

import com.zoosool.state.TradeWindowState;

public class SafariBridge {

    private static final String CHART_BASE_URL =
            "https://app.deriv.com/dtrader?action=redirect&redirect_to=accumulator&account=demo&lang=ru&trade_type=multiplier&chart_type=area&interval=1t&symbol=";

    private final TradeWindowState state;

    public SafariBridge(TradeWindowState state) {
        this.state = state;
    }

    public void redirectIfEnabled() {
        if (!state.isRedirectEnabled()) return;
        if (state.getSelectedAsset() == null) return;
        openInSafari(state.getSelectedAsset().symbol());
    }

    public void openInSafari(String symbol) {
        String script = "tell application \"Safari\" to set URL of current tab of front window to \"%s\""
                .formatted(CHART_BASE_URL + symbol);

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
                pb.redirectErrorStream(true);
                pb.start().waitFor();
            } catch (Exception ignored) {}
        }).start();
    }
}