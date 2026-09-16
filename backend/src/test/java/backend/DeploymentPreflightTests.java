package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DeploymentPreflightTests {
    @TempDir Path root;
    String configuration="SOOMGIL_PUBLIC_ORIGIN=https://jiri.hknu.ac.kr\nSOOMGIL_ADMIN_USERNAME=privateowner\nSOOMGIL_DEMO_USERS=false\nSOOMGIL_SMTP_USERNAME=sender@example.test\nSOOMGIL_SMTP_PASSWORD=private-mail-value\nSOOMGIL_TOUR_API_KEY=private-tour-value\n";
    @BeforeEach void fixture() throws Exception {
        for (String path : List.of("runtime/java/bin/java.exe","runtime/node/node.exe","backend/build/libs/backend-0.0.1-SNAPSHOT.jar",
                "frontend/dist/index.html","deploy/Caddyfile","deploy/windows/caddy.exe","deploy/windows/soomgil-backend.exe","deploy/windows/soomgil-web.exe",
                "deploy/windows/soomgil-monitor.exe","deploy/windows/soomgil-backend.xml","deploy/windows/soomgil-monitor.xml","backend/data/soomgil.mv.db")) {
            Files.createDirectories(root.resolve(path).getParent()); Files.writeString(root.resolve(path),"fixture only");
        }
        Files.writeString(root.resolve("deploy/windows/soomgil-web.xml"),"<service><env name=\"SOOMGIL_DOMAIN\" value=\"jiri.hknu.ac.kr\"/><env name=\"SOOMGIL_WEB_ROOT\" value=\"%BASE%\\..\\..\\frontend\\dist\"/></service>");
        Files.writeString(root.resolve(".env"),configuration);
    }
    DeploymentPreflight.Report report() { return DeploymentPreflight.inspect(root,Map.of()); }
    void fails(String id) { assertThat(report().checks()).anyMatch(check->check.id().equals(id) && check.status().equals("FAIL")); }
    @Test void validOfflineConfigurationNeverClaimsDeploymentApprovalOrPrintsSecrets() throws Exception {
        var result=report(); assertThat(result.configurationPassed()).isTrue(); assertThat(result.launchApproved()).isFalse();
        String json=new ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("private-mail-value","private-tour-value","sender@example.test","privateowner");
        assertThat(Files.readString(root.resolve("backend/data/soomgil.mv.db"))).isEqualTo("fixture only");
    }
    @Test void missingDatabaseIsAStopInsteadOfSilentlyCreatingANewOne() throws Exception {
        Files.delete(root.resolve("backend/data/soomgil.mv.db")); fails("restored-database");
        assertThat(Files.exists(root.resolve("backend/data/soomgil.mv.db"))).isFalse();
    }
    @Test void missingConfigurationAndArtifactsFailWithoutLeakingValues() throws Exception {
        Files.delete(root.resolve(".env")); Files.delete(root.resolve("frontend/dist/index.html"));
        fails("private-config"); fails("production-settings"); fails("file:frontend/dist/index.html");
    }
    @Test void wrongNaverCallbackOrDemoAdministratorStopsDeployment() throws Exception {
        Files.writeString(root.resolve(".env"),configuration+"NAVER_CLIENT_ID=fixture-id\nNAVER_CLIENT_SECRET=private-naver-value\nNAVER_REDIRECT_URI=http://localhost/callback\n");
        fails("production-settings");
        assertThat(new ObjectMapper().writeValueAsString(report())).doesNotContain("private-naver-value");
        Files.writeString(root.resolve(".env"),configuration+"SOOMGIL_ADMIN_USERNAME=test1\n"); fails("production-settings");
    }
    @Test void databaseInsideWebRootOrDisabledBackupsStopsDeployment() throws Exception {
        Files.writeString(root.resolve("frontend/dist/exposed.mv.db"),"fixture");
        Files.writeString(root.resolve(".env"),configuration+"SOOMGIL_DB_URL=jdbc:h2:file:../frontend/dist/exposed\nSOOMGIL_BACKUP_ENABLED=false\n");
        fails("private-database"); fails("automatic-backup");
    }
    @Test void inheritedEnvironmentOverridesAreCheckedAndNeverPrinted() {
        var result=DeploymentPreflight.inspect(root,Map.of("SOOMGIL_ADMIN_USERNAME","test01","SPRING_APPLICATION_JSON","secret override"));
        assertThat(result.configurationPassed()).isFalse();
        assertThat(result.checks()).anyMatch(check->check.id().equals("configuration-overrides") && check.status().equals("FAIL"));
        assertThat(result.toString()).doesNotContain("secret override");
    }
    @Test void publicBackupOrIdenticalReplicaAndWrongDriverFail() throws Exception {
        Files.writeString(root.resolve(".env"),configuration+"SOOMGIL_BACKUP_DIRECTORY=../frontend/dist/backups\nSOOMGIL_DB_DRIVER=other.Driver\n");
        fails("private-backup"); fails("database-driver");
        Files.createDirectories(root.resolve("backup/database"));
        Files.writeString(root.resolve(".env"),configuration+"SOOMGIL_BACKUP_REPLICA=../backup/database\n"); fails("backup-replica");
    }
    @Test void dangerousXmlAndMismatchedWebOriginFail() throws Exception {
        Files.writeString(root.resolve("deploy/windows/soomgil-web.xml"),"<!DOCTYPE service [<!ENTITY x SYSTEM 'file:///private'>]><service>&x;</service>");
        fails("web-service");
        Files.writeString(root.resolve("deploy/windows/soomgil-web.xml"),"<service><env name=\"SOOMGIL_DOMAIN\" value=\"different.example\"/><env name=\"SOOMGIL_WEB_ROOT\" value=\"%BASE%/../../\"/></service>");
        fails("web-origin"); fails("public-root");
    }
}
