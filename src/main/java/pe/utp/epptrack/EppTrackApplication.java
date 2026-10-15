package pe.utp.epptrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EppTrackApplication {

    public static void main(String[] args) {
        SpringApplication.run(EppTrackApplication.class, args);
    }
}
