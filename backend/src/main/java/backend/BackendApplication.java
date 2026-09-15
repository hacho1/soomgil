package backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && "--verify-backup".equals(args[0])) {
			BackupArchive.main(java.util.Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		SpringApplication.run(BackendApplication.class, args);
	}

}
