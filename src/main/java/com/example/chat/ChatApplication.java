package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================
 * APPLICATION ENTRY POINT
 * ============================================================
 *
 * @SpringBootApplication is shorthand for:
 *   @Configuration       — this class can define @Bean methods
 *   @EnableAutoConfiguration — Spring Boot scans classpath and
 *                              auto-configures found libs
 *   @ComponentScan       — scans this package for @Component,
 *                          @Controller, @Service, @Repository
 *
 * What Spring auto-configures from the classpath:
 *   - Embedded Tomcat on port 8080
 *   - WebSocket/STOMP infrastructure (from @EnableWebSocketMessageBroker)
 *   - Jackson ObjectMapper for JSON serialization
 *   - Spring Security filter chain (locked down by default)
 */
@SpringBootApplication
public class ChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatApplication.class, args);
		System.out.println("==============================================");
		System.out.println(" Chat Server running on ws://localhost:8080/ws");
		System.out.println(" SockJS fallback: http://localhost:8080/ws");
		System.out.println("==============================================");
	}
}