package com.booking.auth;

import com.booking.member.*;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.demo-users.enabled", havingValue = "true")
public class DemoUsersInitializer implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public DemoUsersInitializer(UserAccountRepository users, PasswordEncoder passwords, Clock clock, JdbcTemplate jdbc) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Serialize local bootstrapping across multiple application instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(74120819)");
        add("admin@booking.local", "AdminDemo123!", "開發管理員", Role.ADMIN);
        add("frontdesk@booking.local", "StaffDemo123!", "開發櫃台", Role.FRONT_DESK);
    }

    private void add(String email, String password, String name, Role role) {
        if (users.findByEmail(email).isEmpty()) {
            users.save(new UserAccount(email, passwords.encode(password), name, "0912345678", role, clock.instant()));
        }
    }
}
