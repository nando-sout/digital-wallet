package com.picpay.digitalwallet.infrastructure.authorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import com.picpay.digitalwallet.application.transfer.AuthorizationResult;

/**
 * Mapeamento de respostas com MockRestServiceServer: nenhuma requisicao sai do processo.
 * O host "authorizer.test" usa o dominio reservado .test (RFC 2606), que nao existe na internet.
 */
class HttpTransferAuthorizerTest {

	private static final String AUTHORIZER_URL = "http://authorizer.test/api/v2/authorize";

	private MockRestServiceServer server;
	private HttpTransferAuthorizer authorizer;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(AUTHORIZER_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		authorizer = new HttpTransferAuthorizer(builder.build());
	}

	@AfterEach
	void verifyExactlyTheExpectedRequest() {
		server.verify();
	}

	private AuthorizationResult authorize() {
		return authorizer.authorize(4L, 15L, new BigDecimal("100.00"));
	}

	private void respondWith(ResponseCreator response) {
		server.expect(requestTo(AUTHORIZER_URL)).andExpect(method(HttpMethod.GET)).andRespond(response);
	}

	private static DefaultResponseCreator json(HttpStatus status, String body) {
		return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}

	// --- Decisoes explicitas ---

	@Test
	void shouldAuthorizeOn200WithAuthorizationTrue() {
		respondWith(withSuccess("{\"status\":\"success\",\"data\":{\"authorization\":true}}", MediaType.APPLICATION_JSON));

		assertThat(authorize()).isEqualTo(AuthorizationResult.AUTHORIZED);
	}

	@Test
	void shouldDenyOn200WithAuthorizationFalse() {
		respondWith(json(HttpStatus.OK, "{\"status\":\"success\",\"data\":{\"authorization\":false}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.DENIED);
	}

	@Test
	void shouldDenyOn403WithAuthorizationFalse() {
		respondWith(json(HttpStatus.FORBIDDEN, "{\"status\":\"fail\",\"data\":{\"authorization\":false}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.DENIED);
	}

	@Test
	void shouldIgnoreUnknownFieldsInValidResponse() {
		respondWith(json(HttpStatus.OK, "{\"status\":\"success\",\"extra\":1,\"data\":{\"authorization\":true,\"x\":\"y\"}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.AUTHORIZED);
	}

	// --- Falhas tecnicas ---

	@Test
	void shouldReturnTechnicalFailureOn500() {
		respondWith(withServerError());

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOn500EvenWithAuthorizationTrueBody() {
		respondWith(json(HttpStatus.INTERNAL_SERVER_ERROR, "{\"data\":{\"authorization\":true}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOnTimeout() {
		respondWith(withException(new SocketTimeoutException("Read timed out")));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOnConnectionError() {
		respondWith(withException(new ConnectException("Connection refused")));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOnInvalidJson() {
		respondWith(json(HttpStatus.OK, "{\"status\":\"success\",\"data\":{\"authorization\":tr"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"status\":\"success\",\"data\":{}}", "{\"status\":\"success\"}",
			"{\"status\":\"success\",\"data\":null}", "{\"status\":\"success\",\"data\":{\"authorization\":null}}" })
	void shouldReturnTechnicalFailureWhenAuthorizationFieldIsMissing(String body) {
		respondWith(json(HttpStatus.OK, body));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOn403WithoutAuthorizationField() {
		respondWith(json(HttpStatus.FORBIDDEN, "{\"status\":\"fail\"}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	// --- Respostas inesperadas que nao podem ser interpretadas com seguranca ---

	@ParameterizedTest
	@ValueSource(strings = { "\"true\"", "1", "\"yes\"", "[true]", "{\"value\":true}" })
	void shouldReturnTechnicalFailureWhenAuthorizationIsNotAJsonBoolean(String authorizationValue) {
		respondWith(json(HttpStatus.OK, "{\"status\":\"success\",\"data\":{\"authorization\":" + authorizationValue + "}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOnContradictory403WithAuthorizationTrue() {
		respondWith(json(HttpStatus.FORBIDDEN, "{\"status\":\"fail\",\"data\":{\"authorization\":true}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOnNonJsonBody() {
		respondWith(withStatus(HttpStatus.OK).contentType(MediaType.TEXT_HTML).body("<html>authorized</html>"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldReturnTechnicalFailureOnEmptyBody() {
		respondWith(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@ParameterizedTest
	@ValueSource(ints = { 201, 204, 302, 400, 401, 404, 429, 502, 503, 504 })
	void shouldReturnTechnicalFailureOnAnyOtherStatus(int status) {
		respondWith(json(HttpStatus.valueOf(status), "{\"status\":\"success\",\"data\":{\"authorization\":true}}"));

		assertThat(authorize()).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

}
