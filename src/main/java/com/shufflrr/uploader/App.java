package com.shufflrr.uploader;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/app.fxml"));
        Parent parent = loader.load();

        ((Controller) loader.getController()).setStage(stage);

        stage.setTitle("Shufflrr Uploader");
        stage.setScene(new Scene(parent, 470, 300));
        stage.show();
    }
}
