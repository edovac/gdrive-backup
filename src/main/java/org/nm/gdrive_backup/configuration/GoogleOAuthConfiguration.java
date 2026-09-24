package org.nm.gdrive_backup.configuration;

import com.google.api.client.http.javanet.NetHttpTransport;
import org.nm.gdrive_backup.adapter.out.google.GoogleOAuthClientAdapter;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;
import org.nm.gdrive_backup.domain.port.out.GoogleOAuthPort;
import org.nm.gdrive_backup.domain.service.GoogleLoginService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleOAuthConfiguration {

	@Bean
	GoogleOAuthPort googleOAuthPort(CredentialStoragePort credentialStoragePort) {
		return new GoogleOAuthClientAdapter(new NetHttpTransport(), credentialStoragePort);
	}

	@Bean
	GoogleLoginUseCase googleLoginUseCase(GoogleOAuthPort googleOAuthPort) {
		return new GoogleLoginService(googleOAuthPort);
	}
}
