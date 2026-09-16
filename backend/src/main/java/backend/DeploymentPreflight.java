package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Offline validation only: never connects to a database, sends mail or installs services. */
public final class DeploymentPreflight {
    public record Check(String id, String status, String detail) { }
    public record Report(boolean configurationPassed, boolean launchApproved, List<Check> checks, List<String> manualChecks) { }

    static Report inspect(Path project, Map<String,String> environment) {
        Path root=project.toAbsolutePath().normalize();
        var checks=new ArrayList<Check>();
        var config=new Properties();
        try (var reader=Files.newBufferedReader(root.resolve(".env"),StandardCharsets.UTF_8)) {
            config.load(reader);
            add(checks,"private-config",true,"Private configuration is readable. Values are never included in this report.");
        } catch (Exception failure) {
            add(checks,"private-config",false,"Place the reviewed private configuration in the project-root .env file.");
        }
        environment.forEach((key,value)-> { if (key.startsWith("SOOMGIL_") || key.startsWith("NAVER_") || key.equals("GOOGLE_CLIENT_ID") || key.equals("PORT")) config.setProperty(key,value); });
        boolean overrides=environment.keySet().stream().anyMatch(key->key.startsWith("SPRING_") || key.startsWith("SERVER_") || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS"))
                || config.stringPropertyNames().stream().anyMatch(key->key.startsWith("spring.") || key.startsWith("server.") || key.startsWith("soomgil."))
                || Boolean.parseBoolean(config.getProperty("SOOMGIL_SHARING_ENABLED","false"));
        add(checks,"configuration-overrides",!overrides,"Remove or separately review Spring/server/JVM overrides before trusting this bundled production check.");
        var values=new HashMap<String,Object>();
        String origin=config.getProperty("SOOMGIL_PUBLIC_ORIGIN","");
        values.put("soomgil.public-origin",origin); values.put("soomgil.allowed-origins",origin);
        values.put("server.servlet.session.cookie.secure",true); values.put("server.address","127.0.0.1");
        values.put("server.port",config.getProperty("PORT","8080"));
        values.put("soomgil.demo-users",config.getProperty("SOOMGIL_DEMO_USERS","false"));
        values.put("soomgil.admin.username",config.getProperty("SOOMGIL_ADMIN_USERNAME",""));
        values.put("soomgil.naver.client-id",config.getProperty("NAVER_CLIENT_ID",""));
        values.put("soomgil.naver.client-secret",config.getProperty("NAVER_CLIENT_SECRET",""));
        values.put("soomgil.naver.redirect-uri",config.getProperty("NAVER_REDIRECT_URI",""));
        var effective=new StandardEnvironment();
        effective.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        effective.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        effective.getPropertySources().addFirst(new MapPropertySource("offline-production",values));
        try {
            ProductionConfiguration.validate(effective);
            add(checks,"production-settings",true,"HTTPS origin, administrator name, demo policy, port and Naver callback satisfy production validation.");
        } catch (Exception failure) {
            add(checks,"production-settings",false,"Review SOOMGIL_PUBLIC_ORIGIN, SOOMGIL_ADMIN_USERNAME, SOOMGIL_DEMO_USERS=false, PORT=8080 and the Naver credentials/callback pair.");
        }
        for (String file : List.of("runtime/java/bin/java.exe","runtime/node/node.exe","backend/build/libs/backend-0.0.1-SNAPSHOT.jar",
                "frontend/dist/index.html","deploy/Caddyfile","deploy/windows/caddy.exe","deploy/windows/soomgil-backend.exe",
                "deploy/windows/soomgil-web.exe","deploy/windows/soomgil-monitor.exe","deploy/windows/soomgil-backend.xml","deploy/windows/soomgil-monitor.xml")) {
            add(checks,"file:"+file,nonempty(root.resolve(file)),"Required bundled file: "+file);
        }
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document=builder.parse(root.resolve("deploy/windows/soomgil-web.xml").toFile());
            String domain="",webRoot="";
            var entries=document.getElementsByTagName("env");
            for (int i=0;i<entries.getLength();i++) {
                var entry=(org.w3c.dom.Element)entries.item(i);
                if (entry.getAttribute("name").equals("SOOMGIL_DOMAIN")) domain=entry.getAttribute("value");
                if (entry.getAttribute("name").equals("SOOMGIL_WEB_ROOT")) webRoot=entry.getAttribute("value").replace('\\','/');
            }
            add(checks,"web-origin",!domain.isBlank() && origin.equals("https://"+domain),"The web service domain must match SOOMGIL_PUBLIC_ORIGIN.");
            add(checks,"public-root",webRoot.equals("%BASE%/../../frontend/dist"),"Serve frontend/dist only; never expose the project, private settings or database directory.");
        } catch (Exception failure) { add(checks,"web-service",false,"The web service XML is missing or invalid."); }
        String db=config.getProperty("SOOMGIL_DB_URL","jdbc:h2:file:./data/soomgil;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try {
            if (!db.startsWith("jdbc:h2:file:")) throw new IllegalArgumentException();
            String location=db.substring("jdbc:h2:file:".length()).split(";",2)[0];
            if (location.isBlank() || location.startsWith("~")) throw new IllegalArgumentException();
            Path database=root.resolve("backend").resolve(location+".mv.db").normalize();
            add(checks,"restored-database",nonempty(database),"A restored H2 database must already exist at the configured path. This check never creates or opens it.");
            boolean outside=!database.startsWith(root.resolve("frontend/dist"));
            if (Files.exists(database) && Files.exists(root.resolve("frontend/dist"))) outside &= !database.toRealPath().startsWith(root.resolve("frontend/dist").toRealPath());
            add(checks,"private-database",outside,"Keep the database outside the public web directory.");
        } catch (Exception failure) { add(checks,"database-path",false,"Review the H2 file path. Other database engines require a separate migration and backup validation."); }
        add(checks,"database-driver",config.getProperty("SOOMGIL_DB_DRIVER","org.h2.Driver").equals("org.h2.Driver")
                && config.getProperty("SOOMGIL_DB_PLATFORM","h2").equals("h2"),"The bundled database and session schema must both use H2.");
        add(checks,"email-config",present(config,"SOOMGIL_SMTP_USERNAME") && present(config,"SOOMGIL_SMTP_PASSWORD"),"SMTP credentials must be configured; actual receipt still needs testing.");
        add(checks,"tour-api-config",present(config,"SOOMGIL_TOUR_API_KEY"),"Configure the tourism API key; validity and quota are checked during live testing.");
        add(checks,"automatic-backup",config.getProperty("SOOMGIL_BACKUP_ENABLED","true").equalsIgnoreCase("true"),"Keep the scheduled database backup enabled.");
        Path backup=null;
        try {
            backup=root.resolve("backend").resolve(config.getProperty("SOOMGIL_BACKUP_DIRECTORY","../backup/database")).normalize();
            boolean outside=!backup.startsWith(root.resolve("frontend/dist"));
            if (Files.exists(backup)) outside &= !backup.toRealPath().startsWith(root.resolve("frontend/dist").toRealPath());
            add(checks,"private-backup",outside,"Keep the backup directory outside the public web directory.");
        } catch (Exception failure) { add(checks,"private-backup",false,"Review the private backup directory."); }
        String replica=config.getProperty("SOOMGIL_BACKUP_REPLICA","");
        if (replica.isBlank()) checks.add(new Check("backup-replica","WARN","Configure an existing private backup directory on another disk before public operation."));
        else try {
            Path directory=root.resolve("backend").resolve(replica).normalize();
            boolean separate=backup!=null && !directory.equals(backup) && (!Files.exists(backup) || !Files.isSameFile(directory,backup));
            add(checks,"backup-replica",separate && Files.isDirectory(directory) && !directory.toRealPath().startsWith(root.resolve("frontend/dist").toRealPath()),"The replica directory must exist outside the public web directory and differ from the primary backup. Verify permissions and a separate disk manually.");
        } catch (Exception failure) { add(checks,"backup-replica",false,"Review the existing private replica directory."); }
        if (!present(config,"GOOGLE_CLIENT_ID")) checks.add(new Check("google-login","WARN","Google login is disabled. Enable it only after the production origin is registered."));
        if (!present(config,"NAVER_CLIENT_ID")) checks.add(new Check("naver-login","WARN","Naver login is disabled. Enable it only after production callback registration."));
        return new Report(checks.stream().noneMatch(check->check.status().equals("FAIL")),false,List.copyOf(checks),List.of(
                "Verify bundle hashes and replace previously exposed secrets.",
                "Confirm the selected private administrator account exists in the restored database.",
                "Run from an internal fixed disk; verify least-privilege service permissions and backup restore.",
                "Validate Caddy, DNS, HTTPS, allowed ports, actual mail delivery and OAuth sign-in on the server.",
                "Test signup, search, favorites, diary persistence and service recovery after reboot."));
    }
    private static void add(List<Check> checks,String id,boolean passed,String detail) { checks.add(new Check(id,passed ? "PASS" : "FAIL",detail)); }
    private static boolean present(Properties config,String key) { return !config.getProperty(key,"").isBlank(); }
    private static boolean nonempty(Path path) { try { return Files.isRegularFile(path) && Files.size(path)>0; } catch (Exception failure) { return false; } }
    public static void main(String[] args) throws Exception {
        if (args.length!=1) throw new IllegalArgumentException("Usage: --check-deployment <project-root>");
        var report=inspect(Path.of(args[0]),System.getenv());
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (!report.configurationPassed()) System.exit(2);
    }
}
