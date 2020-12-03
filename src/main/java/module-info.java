module com.shufflrr.uploader {
    requires java.net.http;
    requires javafx.controls;
    requires javafx.fxml;
    requires com.shufflrr.sdk;

    exports com.shufflrr.uploader to javafx.fxml, javafx.graphics, com.fasterxml.jackson.databind;
    opens com.shufflrr.uploader to javafx.fxml;
}