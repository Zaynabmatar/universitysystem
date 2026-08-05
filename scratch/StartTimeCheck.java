import com.university.database.DBConnection;
import java.sql.*;

public class StartTimeCheck {
    public static void main(String[] args) throws Exception {
        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT sqlserver_start_time, GETDATE() AS now_time, DATEDIFF(SECOND, sqlserver_start_time, GETDATE()) AS uptime_sec FROM sys.dm_os_sys_info")) {
            while (rs.next()) {
                System.out.println("sqlserver_start_time=" + rs.getTimestamp(1));
                System.out.println("now_time=" + rs.getTimestamp(2));
                System.out.println("uptime_sec=" + rs.getInt(3));
            }
        }
    }
}
