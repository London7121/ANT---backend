package uz.ant.api.account;

import java.time.Instant;

/** Landing foydalanuvchisi (ro'yxatdan o'tgan mijoz). */
public record SiteUser(long id, String username, String hash, boolean blocked, Instant createdAt) {}
