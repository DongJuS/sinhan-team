package com.sinhan.benefit;

import com.sinhan.web.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({SecurityConfiguration.class, ErrorHandler.class, TraceFilter.class, ServiceHttp.class})
public class Application {
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
