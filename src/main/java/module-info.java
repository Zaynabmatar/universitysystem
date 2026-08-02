module com.university {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires com.microsoft.sqlserver.jdbc;

    opens com.university to javafx.fxml;
    exports com.university;
    exports com.university.database;
}
