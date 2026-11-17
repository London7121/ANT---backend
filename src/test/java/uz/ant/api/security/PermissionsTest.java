package uz.ant.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java ruxsatlar matritsasi frontenddagi `ant-frontend/shared/roles.ts` bilan bir xil natija berishini tekshiradi.
 * `permissions-golden.json` shu fayldan generatsiya qilingan (roles.ts o'zgarsa — qayta generatsiya qiling, README'ga qarang).
 */
class PermissionsTest {
    private static JsonNode golden() throws Exception {
        try (InputStream in = PermissionsTest.class.getResourceAsStream("/permissions-golden.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void allPermissionsMatchFrontend() throws Exception {
        assertThat(Permissions.ALL.stream().sorted().toList()).isEqualTo(strings(golden().get("all")));
    }

    @TestFactory
    Stream<DynamicTest> effectivePermissionsMatchFrontend() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : golden().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asString(), () -> {
                var actual = Permissions.effective(Role.valueOf(c.get("role").asString()), strings(c.get("grant")), strings(c.get("revoke")));
                assertThat(actual.stream().sorted().toList()).isEqualTo(strings(c.get("expected")));
            }));
        }
        return tests.stream();
    }

    @Test
    void superAdminAlwaysHasEverything() {
        assertThat(Permissions.effective(Role.SUPER_ADMIN, List.of(), Permissions.ALL)).containsAll(Permissions.ALL);
    }
}
