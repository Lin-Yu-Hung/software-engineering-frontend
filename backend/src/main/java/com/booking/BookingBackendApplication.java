package com.booking;

import com.booking.neon.NeonDatabaseInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BookingBackendApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(BookingBackendApplication.class);
		application.addInitializers(new NeonDatabaseInitializer());
		application.run(args);
	}

}
