package pe.utp.epptrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EppTrackApplication {

    public static void main(String[] args) {
        // Las fechas de vigencia se calculan con la hora de Lima aunque el servidor esté en UTC.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Lima"));
        SpringApplication.run(EppTrackApplication.class, args);
    }
}
