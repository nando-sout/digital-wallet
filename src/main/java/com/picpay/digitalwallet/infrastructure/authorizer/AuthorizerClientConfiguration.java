package com.picpay.digitalwallet.infrastructure.authorizer;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Monta o RestClient do autorizador com URL e timeouts vindos de {@link AuthorizerProperties}.
 * Fica separado de {@link HttpTransferAuthorizer} para que os testes possam injetar um RestClient
 * ligado ao MockRestServiceServer sem que a fabrica de requisicoes real o substitua.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthorizerProperties.class)
public class AuthorizerClientConfiguration {

	@Bean
	HttpTransferAuthorizer httpTransferAuthorizer(AuthorizerProperties properties) {
		return new HttpTransferAuthorizer(authorizerRestClient(properties));
	}

	static RestClient authorizerRestClient(AuthorizerProperties properties) {
		// Redirect.NEVER: uma resposta 3xx e devolvida como esta (e tratada como falha tecnica);
		// o cliente nunca busca a autorizacao em outro destino
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
