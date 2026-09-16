package backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("production")
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "SOOMGIL_PUBLIC_ORIGIN=https://jiri.hknu.ac.kr", "soomgil.admin.username=launchoperator",
    "soomgil.demo-users=false", "soomgil.backup.enabled=false", "soomgil.tour-sync-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:production-audit;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "soomgil.naver.client-id=", "soomgil.naver.client-secret=", "soomgil.google.client-id="
})
class ProductionDeploymentTests {
    @Autowired MockMvc mvc;

    @Test void productionContextServesApiButRejectsUntrustedOriginsAndPrivateFiles() throws Exception {
        mvc.perform(get("/api/health").secure(true).header("Origin", "https://jiri.hknu.ac.kr"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/health").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/.env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/operations")).andExpect(status().isUnauthorized());
    }

    @Test void restoredDemoSessionsAndStaleAdminRolesCannotReadPrivateData() throws Exception {
        mvc.perform(get("/api/users/test1/settings").with(user("test1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/operations").with(user("previousoperator").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/operations/backup").with(user("previousoperator").roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/operations").with(user("launchoperator").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/another-user/settings").with(user("ordinary-user")))
                .andExpect(status().isForbidden());
    }
}
