package backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EmailDeliveryTests {
    @SuppressWarnings("unchecked")
    @Test void blankFromUsesSenderAndMessagesExcludePassword() {
        var sender = mock(JavaMailSender.class);
        var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        var delivery = new EmailDelivery(provider, "sender@example.test", "test-only-app-password", "");
        try {
            assertThat(delivery.available()).isTrue();
            delivery.verification("recipient@example.test", "123456", "signup", () -> fail("send failed"));
            var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender, timeout(3000)).send(message.capture());
            assertThat(message.getValue().getFrom()).isEqualTo("sender@example.test");
            assertThat(message.getValue().getText()).contains("123456").doesNotContain("test-only-app-password");
        } finally { delivery.close(); }
    }
    @SuppressWarnings("unchecked")
    @Test void deliveryFailureCallsInvalidationWithoutLoggingSecrets() {
        var sender = mock(JavaMailSender.class);
        doThrow(new org.springframework.mail.MailAuthenticationException("sensitive provider response")).when(sender).send(any(SimpleMailMessage.class));
        var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        var delivery = new EmailDelivery(provider, "sender@example.test", "test-only", "");
        var failure = mock(Runnable.class);
        try {
            delivery.verification("recipient@example.test", "123456", "signup", failure);
            verify(failure, timeout(3000)).run();
            assertThat(new EmailDelivery(provider, "sender@example.test", "", "").available()).isFalse();
        } finally { delivery.close(); }
    }
}
