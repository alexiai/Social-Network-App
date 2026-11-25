package community.campus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Campus {
    public static void main(String[] args) {
        SpringApplication.run(Campus.class, args);
    }
}
