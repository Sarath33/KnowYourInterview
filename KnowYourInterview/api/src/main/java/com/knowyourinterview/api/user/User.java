package com.knowyourinterview.api.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    // Nullable: accounts created via Google Sign-In never get a password. Password-based
    // login (AuthService.login) rejects any user with a null hash before it ever reaches
    // passwordEncoder.matches (which would otherwise NPE on it).
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    // Google's stable per-account subject id (the ID token's "sub" claim). Null for
    // accounts that have never signed in with Google. Unique when present — see
    // V2__add_google_auth.sql.
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA
    }

    public User(UUID id, String email, String passwordHash, String displayName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.admin = false;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Google Sign-In signup: no password_hash — googleSub is the only credential. */
    public static User forGoogleSignup(UUID id, String email, String displayName, String googleSub) {
        User user = new User(id, email, null, displayName);
        user.googleSub = googleSub;
        return user;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isAdmin() {
        return admin;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    /** Links an existing (previously email/password-only) account to a Google account the
     * first time its owner uses "Sign in with Google" with a matching, verified email. */
    public void linkGoogleSub(String googleSub) {
        this.googleSub = googleSub;
        this.updatedAt = Instant.now();
    }

    /** See AuthService#bootstrapAdmin — the one path to ROLE_ADMIN that isn't a direct
     * database update, gated by the ADMIN_BOOTSTRAP_SECRET env var rather than by already
     * being an admin (there wouldn't be one yet the first time this is used). */
    public void promoteToAdmin() {
        this.admin = true;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
