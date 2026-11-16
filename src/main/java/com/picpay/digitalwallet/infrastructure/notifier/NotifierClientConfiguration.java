package com.picpay.digitalwallet.infrastructure.notifier;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Monta o RestClient do notificador com URL e timeouts vindos de {@link NotifierProperties}.
 * Mesmo padrao de AuthorizerClientConfiguration: separado de {@link HttpTransferNotifier} para que os
 * testes possam injetar um RestClient ligado ao MockRestServiceServer.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotifierProperties.class)
public class NotifierClientConfiguration {

	@Bean
	HttpTransferNotifier httpTransferNotifier(NotifierProperties properties) {
		return new HttpTransferNotifier(notifierRestClient(properties));
	}

	static RestClient notifierRestClient(NotifierProperties properties) {
		// Redirect.NEVER: uma resposta 3xx nao e 2xx e e tratada como falha; nenhum outro destino e contatado
		HttpClient httpClient = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return RestClient.builder()
			.baseUrl(properties.url().toString())
			.requestFactory(requestFactory)
			.build();
	}
}
