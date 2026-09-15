package backend;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

final class GoogleIdentity {
    private static volatile NimbusJwtDecoder decoder;

    static Jwt verify(String token, String audience) {
        try {
            if (decoder == null) {
                synchronized (GoogleIdentity.class) {
                    if (decoder == null) {
                        var created = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
                        created.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), jwt -> {
                            boolean valid = jwt.getAudience().contains(audience)
                                    && jwt.getExpiresAt() != null
                                    && jwt.getSubject() != null
                                    && ("https://accounts.google.com".equals(String.valueOf(jwt.getIssuer())) || "accounts.google.com".equals(String.valueOf(jwt.getIssuer())))
                                    && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                                    && jwt.getClaimAsString("email") != null;
                            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                        }));
                        decoder = created;
                    }
                }
            }
            return decoder.decode(token);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google 인증을 확인할 수 없습니다. 다시 로그인해주세요.");
        }
    }
}
