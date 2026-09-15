package backend;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailDelivery {
    private final JavaMailSender sender;
    private final String from;
    private final boolean configured;
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { var thread = new Thread(runnable, "auth-mail"); thread.setDaemon(true); return thread; });
    public EmailDelivery(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password, @Value("${soomgil.mail.from:}") String from) {
        this.sender = sender.getIfAvailable(); this.from = from.isBlank() ? username : from;
        this.configured = this.sender != null && !username.isBlank() && !password.isBlank() && this.from.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }
    public boolean available() { return configured; }
    public void verification(String email, String code, String purpose, Runnable onFailure) {
        submit(email, "[숨길] 이메일 인증번호", "인증번호: " + code + "\n\n10분 안에 입력해주세요. 인증번호는 다른 사람에게 알려주지 마세요.\n본인이 요청하지 않았다면 이 메일을 무시해주세요.", onFailure);
    }
    public void passwordChanged(String email) {
        if (available()) submit(email, "[숨길] 비밀번호가 변경되었습니다", "숨길 계정의 비밀번호가 변경되어 모든 기기에서 로그아웃되었습니다.\n본인이 변경하지 않았다면 즉시 비밀번호를 재설정하고 이메일 계정의 보안도 확인해주세요.", () -> {});
    }
    private void submit(String email, String subject, String body, Runnable onFailure) {
        queue.execute(() -> {
            try {
                var message = new SimpleMailMessage(); message.setFrom(from); message.setTo(email);
                message.setSubject(subject); message.setText(body); sender.send(message);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Authentication email delivery failed: {}", failure.getClass().getSimpleName());
                onFailure.run();
            }
        });
    }
    @PreDestroy void close() { queue.shutdownNow(); }
}
