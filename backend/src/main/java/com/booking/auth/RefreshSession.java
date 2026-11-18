package com.booking.auth;

import com.booking.member.UserAccount;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private UserAccount user;
    @Column(nullable = false, length = 64) private String tokenHash;
    @Column(nullable = false) private Instant expiresAt;
    private Instant revokedAt;
    @Version private long version;
    @Column(nullable = false) private Instant createdAt;

    protected RefreshSession() { }

    public RefreshSession(UserAccount user, String tokenHash, Instant expiresAt, Instant now) {
        id = UUID.randomUUID();
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        createdAt = now;
    }

    public boolean isActive(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public void rotate(String hash) { tokenHash = hash; }
    public void revoke(Instant now) { revokedAt = now; }
    public UUID getId() { return id; }
    public UserAccount getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
}
