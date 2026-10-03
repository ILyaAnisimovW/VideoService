package org.prod.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
        "org.prod.app",
        "org.prod.auth",
        "org.prod.user",
        "org.prod.shared",
        "org.prod.parking",
        "org.prod.spot",
        "org.prod.booking",
        "org.prod.notification"
})
@EnableJpaRepositories(basePackages = {
        "org.prod.user.repository",
        "org.prod.auth.repository",
        "org.prod.parking.repository",
        "org.prod.spot.repository",
        "org.prod.booking.repository"
})
@EntityScan(basePackages = {
        "org.prod.user.entity",
        "org.prod.auth.entity",
        "org.prod.parking.entity",
        "org.prod.spot.entity",
        "org.prod.booking.entity"
})
public class VideoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoApplication.class, args);
    }
}