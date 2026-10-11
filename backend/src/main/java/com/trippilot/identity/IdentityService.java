package com.trippilot.identity;

import com.trippilot.presentation.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class IdentityService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public IdentityService(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    public record Account(UUID id, String email) {}

    public Account register(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password must contain 12-72 UTF-8 bytes.");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO app_users(id,email,password_hash) VALUES (?,?,?)", id.toString(), normalized, encoder.encode(password));
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "An account with this email already exists.");
        }
        return new Account(id, normalized);
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        var users = jdbc.query("SELECT id,password_hash FROM app_users WHERE email = ?", (rs, row) ->
                User.withUsername(rs.getString("id")).password(rs.getString("password_hash")).roles("USER").build(),
                email.strip().toLowerCase(Locale.ROOT));
        if (users.isEmpty()) { throw new UsernameNotFoundException("Invalid credentials"); }
        return users.getFirst();
    }

    public Account account(String id) {
        var accounts = jdbc.query("SELECT id,email FROM app_users WHERE id = ?", (rs, row) ->
                new Account(UUID.fromString(rs.getString("id")), rs.getString("email")), id);
        if (accounts.isEmpty()) { throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again."); }
        return accounts.getFirst();
    }
}
