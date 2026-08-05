package com.university;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class TestRoleSelection extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/role_selection.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        setUserAgentStylesheet(STYLESHEET_MODENA);
        stage.setScene(scene);
        stage.show();

        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> {
                try {
                    System.out.println("stage: " + stage.getWidth() + "x" + stage.getHeight());
                    System.out.println("scene: " + scene.getWidth() + "x" + scene.getHeight());
                    WritableImage img = scene.snapshot(null);
                    int w = (int) img.getWidth(), h = (int) img.getHeight();
                    PixelReader reader = img.getPixelReader();
                    BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            buffered.setRGB(x, y, reader.getArgb(x, y));
                        }
                    }
                    File out = new File(System.getProperty("snapshot.out", "snapshot.png"));
                    ImageIO.write(buffered, "png", out);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    Platform.exit();
                }
            });
        }).start();
    }
}
