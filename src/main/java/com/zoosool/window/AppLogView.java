package com.zoosool.window;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public class AppLogView {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea area = new TextArea();

    public AppLogView() {
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(8);
    }

    public Parent getNode() {
        return new VBox(area);
    }

    public void log(String msg) {
        String line = "[" + LocalTime.now().format(TS) + "] " + msg + "\n";
        if (Platform.isFxApplicationThread()) {
            area.appendText(line);
        } else {
            Platform.runLater(() -> area.appendText(line));
        }
    }

    public Consumer<String> logger() {
        return this::log;
    }

    public void clear() {
        if (Platform.isFxApplicationThread()) {
            area.clear();
        } else {
            Platform.runLater(area::clear);
        }
    }
}

