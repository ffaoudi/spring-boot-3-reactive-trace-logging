package me.geales.spring.trace.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class TraceLoggingTest {
    private final Logger logger = LoggerFactory.getLogger(TraceLoggingTest.class);

    public static void main(String[] args) {
        SpringApplication.run(TraceLoggingTest.class, args);
    }

    @GetMapping("/")
    public String getter() {
        var msg = "hello, world!";
        logger.info(msg);
        return msg;
    }
}
