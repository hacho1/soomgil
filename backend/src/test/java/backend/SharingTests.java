package backend;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("sharing")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "soomgil.public-origin=https://share.example.test",
    "spring.datasource.url=jdbc:h2:mem:sharing-test;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class SharingTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionAuth auth;
    @MockitoBean EmailDelivery mail;

    class Client {
        HttpClient http=HttpClient.newHttpClient(); String cookie="",csrf="";
        HttpResponse<String> call(String method,String path,Object body,String origin) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
            if (origin!=null) builder.header("Origin",origin);
            if (!cookie.isEmpty()) builder.header("Cookie",cookie);
            if (!csrf.isEmpty()) builder.header("X-CSRF-TOKEN",csrf);
            var response=http.send(builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            for (String value:response.headers().allValues("Set-Cookie")) if (value.startsWith("SOOMGIL_SHARE_SESSION=")) cookie=value.split(";",2)[0];
            return response;
        }
        HttpResponse<String> get(String path) throws Exception { return call("GET",path,null,"https://share.example.test"); }
        HttpResponse<String> post(String path,Object body) throws Exception { return call("POST",path,body,"https://share.example.test"); }
        JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
        void csrf() throws Exception {
            var response=get("/api/auth/csrf"); csrf=json(response).path("token").asText();
            assertThat(response.headers().allValues("Set-Cookie").toString()).contains("Secure","HttpOnly","SameSite=Lax");
        }
    }
    @Test void servesBuiltAppAndRefreshRoutesWithoutExposingSourceOrAdministrator() throws Exception {
        var client=new Client();
        for (String page:new String[]{"/","/loginform","/signupform","/diary/new","/places/1","/mypage/badges"}) {
            var response=client.get(page);
            assertThat(response.statusCode()).as(page+": "+response.body()).isEqualTo(200);
            assertThat(response.body()).contains("<div id=\"root\"").doesNotContain("/@vite/client");
        }
        assertThat(client.get("/assets/badges/first-view.png").statusCode()).isEqualTo(200);
        for (String path:new String[]{"/.env","/src/App.jsx","/backend/data/soomgil.mv.db","/admin.html","/api/admin/inquiries"})
            assertThat(client.get(path).statusCode()).isIn(401,403,404);
        assertThat(client.get("/api/places/search?query=cafe").statusCode()).isEqualTo(200);
        assertThat(client.call("GET","/api/places/search",null,"https://untrusted.example.test").statusCode()).isEqualTo(403);
        var config=client.json(client.get("/api/auth/config"));
        assertThat(config.path("googleClientId").asText()).isEmpty();
        assertThat(config.path("naverAvailable").asBoolean()).isFalse();
    }
    @Test void verifiedSignupLoginAndDiaryWorkWithSecureCookiesAndCsrf() throws Exception {
        var codes=new HashMap<String,String>(); when(mail.available()).thenReturn(true);
        doAnswer(call -> { codes.put(call.getArgument(0),call.getArgument(1)); return null; }).when(mail).verification(anyString(),anyString(),anyString(),any());
        var client=new Client(); client.csrf();
        String user="share"+UUID.randomUUID().toString().replace("-","").substring(0,10),email=user+"@example.test";
        var requested=client.post("/api/auth/email/request",Map.of("email",email,"purpose","signup"));
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
        var confirmed=client.post("/api/auth/email/confirm",Map.of("challengeId",client.json(requested).path("challengeId").asText(),"code",codes.get(email)));
        var signup=client.post("/api/auth/signup",Map.of("username",user,"email",email,"fullName","공유테스트","password","Secure1!pass","passwordConfirm","Secure1!pass","privacyConsent",true,"emailProof",client.json(confirmed).path("proof").asText()));
        assertThat(signup.statusCode()).as(signup.body()).isEqualTo(200);
        String diaries="/api/users/"+user+"/diaries";
        var saved=client.post(diaries,Map.of("title","공유 테스트 기록","body","DB 저장 확인"));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(client.json(client.get(diaries)).get(0).path("title").asText()).isEqualTo("공유 테스트 기록");
        assertThat(client.get("/api/admin/notices").statusCode()).isEqualTo(403);
        var noCsrf=new Client(); noCsrf.cookie=client.cookie;
        assertThat(noCsrf.post(diaries,Map.of("title","blocked")).statusCode()).isEqualTo(403);
        var second=new Client(); second.csrf();
        assertThat(second.post("/api/auth/login",Map.of("identifier",email,"password","Secure1!pass","autoLogin",true)).statusCode()).isEqualTo(200);
        assertThat(second.json(second.get(diaries)).size()).isEqualTo(1);
        for (String reserved:new String[]{"test1","test01","demo"}) {
            assertThat(auth.isReservedUsername(reserved)).isTrue();
            assertThatThrownBy(() -> auth.login(reserved,false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
}
