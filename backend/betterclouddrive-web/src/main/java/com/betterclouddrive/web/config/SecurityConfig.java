package com.betterclouddrive.web.config;

import com.betterclouddrive.dal.repository.UserTokenRepository;
import com.betterclouddrive.web.security.BasicAuthFilter;
import com.betterclouddrive.web.security.ClientIpResolver;
import com.betterclouddrive.web.security.JwtAuthenticationFilter;
import com.betterclouddrive.web.security.JwtTokenProvider;
import com.betterclouddrive.web.security.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain webDavFilterChain(HttpSecurity http, BasicAuthFilter basicAuthFilter) throws Exception {
        http
            .securityMatcher("/webdav/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(basicAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                                            StringRedisTemplate redisTemplate,
                                                            UserTokenRepository userTokenRepository) {
        return new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate, userTokenRepository);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(StringRedisTemplate redisTemplate,
                                            RateLimitProperties properties,
                                            ClientIpResolver clientIpResolver,
                                            MeterRegistry meterRegistry,
                                            ObjectMapper objectMapper) {
        return new RateLimitFilter(redisTemplate, properties, clientIpResolver, meterRegistry, objectMapper);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtFilter,
                                            RateLimitFilter rateLimitFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/register-code/send", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/team-invites/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/download/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/download/folders/*/zip").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/teams/*/download/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/teams/*/download/folders/*/zip").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/office/files/*/content").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/teams/*/office/files/*/content").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/teams/*/office/files/*/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/shares/access/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/shares/access/*/files").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/shares/access/*/download/*/zip").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/shares/access/*/download/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/grafana/auth").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/notifications/stream").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(List.of(
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE",
                "PROPFIND", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"
        ));
        return (web) -> web.httpFirewall(firewall);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
