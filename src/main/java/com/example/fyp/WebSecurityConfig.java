package com.example.fyp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http, FirebaseUserDetailsService firebaseUserDetailsService) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder = http.getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder.userDetailsService(firebaseUserDetailsService).passwordEncoder(passwordEncoder());
        return authenticationManagerBuilder.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return rawPassword.toString();
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return rawPassword.toString().equals(encodedPassword);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/signup", "/login", "/").permitAll()
                .requestMatchers("/createJobPosting").hasRole("HR_STAFF")
                .requestMatchers("/welcome_employee", "/probationReview", 
                               "/selectNewJoinerForReview", "/leaveReview", 
                               "/submitProbationReview", "/reviewHistory").hasRole("EMPLOYEE")
                .requestMatchers("/welcome_new_joiner").hasRole("NEW_JOINER")
                .requestMatchers("/viewJobs", "/profile", "/updateProfile").authenticated()
                .anyRequest().authenticated())
            .formLogin(login -> login
                .loginPage("/login")
                .usernameParameter("email")
                .defaultSuccessUrl("/welcome")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login")
                .permitAll());
        return http.build();
    }
}