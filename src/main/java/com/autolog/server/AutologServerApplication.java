package com.autolog.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AutologServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AutologServerApplication.class, args);
	}

}
