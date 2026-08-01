package com.university;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;


/**
 * JavaFX App
 */
public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        Parent root = FXMLLoader.load(App.class.getResource("Login.fxml"));

        stage.setTitle("University System - Login");
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }

}