module com.example.socialnetwork {
    requires javafx.media;
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.desktop;
    requires org.apache.pdfbox;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.bootstrapfx.core;
    requires com.almasb.fxgl.all;

    opens com.example.socialnetwork to javafx.fxml;
    exports com.example.socialnetwork;

    exports socialnetwork.domain;
    opens socialnetwork.domain to javafx.fxml;
}