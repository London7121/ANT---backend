package uz.ant.api.security;

import java.time.Instant;
import java.util.List;

/** Admin panel xodimi (bazadagi qator, parol xeshi bilan). Frontendga faqat {@link StaffUser} yuboriladi. */
public record Staff(long id, String username, String name, Role role, List<String> grant, List<String> revoke,
                    boolean active, String hash, Instant createdAt) {

    public String displayName() { return name == null || name.isEmpty() ? username : name; }

    public StaffUser toUser() {
        return new StaffUser(id, username, name, role, grant, revoke, active, uz.ant.api.common.Time.iso(createdAt));
    }
}
