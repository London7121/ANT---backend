package uz.ant.api.bootstrap;

import java.util.Optional;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import uz.ant.api.security.PasswordHasher;
import uz.ant.api.security.Role;
import uz.ant.api.security.Staff;
import uz.ant.api.staff.StaffRepository;

/**
 * Admin parolini tiklash (parol esdan chiqqanda):
 * <pre>java -jar ant-api.jar --reset-admin=YANGI_PAROL [--login=admin]</pre>
 * Login berilmasa — birinchi SUPER_ADMIN. Hisob faollashtiriladi, dastur esa darhol yopiladi (server ochilmaydi).
 */
@Component
public class AdminResetRunner implements ApplicationRunner {
    private final StaffRepository staff;
    private final PasswordHasher hasher;
    private final ConfigurableApplicationContext ctx;

    public AdminResetRunner(StaffRepository staff, PasswordHasher hasher, ConfigurableApplicationContext ctx) {
        this.staff = staff;
        this.hasher = hasher;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("reset-admin")) return;
        String password = args.getOptionValues("reset-admin").stream().findFirst().orElse("");
        if (password.length() < 6) {
            System.err.println("Foydalanish: --reset-admin=<parol (kamida 6 belgi)> [--login=<login>]");
            System.exit(SpringApplication.exit(ctx, () -> 1));
        }
        Optional<String> login = Optional.ofNullable(args.getOptionValues("login")).flatMap(v -> v.stream().findFirst());
        Optional<Staff> target = login.isPresent() ? staff.findByUsername(login.get()) : staff.findAll().stream().filter(s -> s.role() == Role.SUPER_ADMIN).findFirst();
        if (target.isEmpty()) {
            System.err.println(login.map(l -> "\"" + l + "\" nomli xodim topilmadi.").orElse("SUPER_ADMIN topilmadi."));
            System.exit(SpringApplication.exit(ctx, () -> 1));
        }
        Staff s = target.get();
        staff.update(new Staff(s.id(), s.username(), s.name(), s.role(), s.grant(), s.revoke(), true, hasher.hash(password), s.createdAt()));
        System.out.println("✅ \"" + s.username() + "\" (" + s.role() + ") — parol yangilandi. Endi serverni ishga tushiring.");
        System.exit(SpringApplication.exit(ctx, () -> 0));
    }
}
