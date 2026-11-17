package uz.ant.api.security;

import java.util.List;

/** Parolsiz ko'rinish — frontendga shu yuboriladi. */
public record StaffUser(long id, String username, String name, Role role, List<String> grant, List<String> revoke,
                        boolean active, String createdAt) {}
