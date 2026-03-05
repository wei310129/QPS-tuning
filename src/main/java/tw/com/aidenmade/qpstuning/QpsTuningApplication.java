package tw.com.aidenmade.qpstuning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class QpsTuningApplication {

    public static void main(String[] args) {
        SpringApplication.run(QpsTuningApplication.class, args);
    }

}
