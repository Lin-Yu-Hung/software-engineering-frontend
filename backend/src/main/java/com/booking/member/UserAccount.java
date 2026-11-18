package com.booking.member;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false, length = 254) private String email;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 20) private String phone;
    @Column(nullable = false) private boolean enabled;
    @Version private long version;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Set<Role> roles = new HashSet<>();

    protected UserAccount() { }

    public UserAccount(String email, String passwordHash, String name, String phone,
            Role role, Instant now) {
        id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phone = phone;
        enabled = true;
        createdAt = now;
        updatedAt = now;
        roles.add(role);
    }

    public void updateProfile(String name, String phone, Instant now) {
        if (name != null) this.name = name;
        if (phone != null) this.phone = phone;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public boolean isEnabled() { return enabled; }
    public Set<Role> getRoles() { return Set.copyOf(roles); }
}
