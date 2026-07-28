package com.rakesh.chat.client;

import com.rakesh.chat.client.ui.ChatWindow;

import javafx.application.Application;

/**
 * Starts the GUI client.
 *
 * <p><b>Why this class exists at all.</b> If the class named on the command line
 * {@code extends Application}, the JavaFX launcher insists that JavaFX was loaded as a
 * proper module, and you get the famous
 * "Error: JavaFX runtime components are missing, and are required to run this application".
 * Launching from a class that does <i>not</i> extend {@code Application} skips that check,
 * so the app also runs from a plain classpath, for example straight out of an IDE.
 *
 * <p>Preferred way to run it: {@code mvn javafx:run}.
 */
public final class Main {

    public static void main(String[] args) {
        Application.launch(ChatWindow.class, args);
    }
}
