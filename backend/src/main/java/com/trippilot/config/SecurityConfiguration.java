package com.trippilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/** Session identity; CSRF remains enabled, and unimplemented capabilities fail closed. */
@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/identity/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/identity/register", "/api/identity/login").permitAll()
                        .requestMatchers("/api/identity/me", "/api/planning/sessions", "/api/planning/sessions/*").authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/planning/sessions/*/revisions","/api/planning/sessions/*/jobs","/api/research/jobs/*/cancel").authenticated()
                        .requestMatchers(HttpMethod.GET,"/api/research/jobs/*","/api/research/jobs/*/tasks","/api/research/jobs/*/sources","/api/research/jobs/*/result","/api/research/jobs/*/events","/api/research/jobs/*/event-log","/api/research/jobs/*/inspector").authenticated()
                        .requestMatchers(HttpMethod.GET,"/api/planning/sessions/*/jobs","/api/planning/sessions/*/selected-trips","/api/planning/revisions/*","/api/planning/trips/*","/api/planning/trips/*/messages","/api/research/jobs/*/images").authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/planning/clarification","/api/planning/sessions/*/selected-trips","/api/planning/trips/*/research").authenticated()
                        .requestMatchers(HttpMethod.GET,"/api/planning/cards/*").authenticated()
                        .requestMatchers(HttpMethod.PUT,"/api/planning/cards/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE,"/api/planning/cards/*").authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/planning/cards/*/restore").authenticated()
                        .requestMatchers(HttpMethod.GET,"/api/booking/capability","/api/booking/trips/*/attempts","/api/booking/attempts/*","/api/booking/quotes/*").authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/booking/trips/*/inventory","/api/booking/trips/*/quotes","/api/booking/trips/*/authorizations","/api/booking/attempts/*/execute","/api/booking/attempts/*/status","/api/booking/attempts/*/revoke").authenticated()
                        .anyRequest().denyAll())
                .formLogin(login -> login.loginPage("/api/identity/login").loginProcessingUrl("/api/identity/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> error(response, 401, "INVALID_CREDENTIALS")))
                .logout(logout -> logout.logoutUrl("/api/identity/logout").invalidateHttpSession(true)
                        .deleteCookies("SESSION").logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> error(response, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, exception) -> error(response, 403, "FORBIDDEN")))
                .build();
    }

    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Access denied.\",\"traceId\":\"" + UUID.randomUUID() + "\"}");
    }
}
