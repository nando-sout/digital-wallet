package com.picpay.digitalwallet.infrastructure.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * Comportamento do notificador com MockRestServiceServer: nenhuma requisicao sai do processo.
 * O host "notifier.test" usa o dominio reservado .test (RFC 2606), que nao existe na internet.
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpTransferNotifierTest {

	private static final String NOTIFIER_URL = "http://notifier.test/api/v1/notify";
	private static final Long TRANSFER_ID = 42L;
	private static final String FAILURE_LOG = "Payee notification failed for transfer " + TRANSFER_ID;

	private MockRestServiceServer server;
	private HttpTransferNotifier notifier;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(NOTIFIER_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		notifier = new HttpTransferNotifier(builder.build());
	}

	@AfterEach
	void verifyExactlyOneRequest() {
		// Exatamente uma requisicao por notificacao: sem retentativas
		server.verify();
	}

	private void respondWith(ResponseCreator response) {
		server.expect(requestTo(NOTIFIER_URL)).andExpect(method(HttpMethod.POST)).andRespond(response);
	}

	private void notifyCompleted() {
		assertThatNoException().isThrownBy(() -> notifier.notifyTransferCompleted(TRANSFER_ID));
	}

	// --- Requisicao ---

	@Test
	void shouldSendPostWithoutBody() {
		server.expect(requestTo(NOTIFIER_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().bytes(new byte[0]))
			.andExpect(headerDoesNotExist(HttpHeaders.CONTENT_TYPE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		notifyCompleted();
	}

	// --- Sucesso ---

	@ParameterizedTest
	@ValueSource(ints = { 200, 201, 202, 204 })
	void shouldTreatAny2xxAsSuccess(int status, CapturedOutput output) {
		respondWith(withStatus(HttpStatus.valueOf(status)));

		notifyCompleted();

		assertThat(output).doesNotContain(FAILURE_LOG);
	}

	@Test
	void shouldIgnoreResponseBodyOnSuccess(CapturedOutput output) {
		respondWith(withStatus(HttpStatus.OK).contentType(MediaType.TEXT_HTML).body("<html>not json</html>"));

		notifyCompleted();

		assertThat(output).doesNotContain(FAILURE_LOG);
	}

	// --- Falhas: registradas, nunca propagadas ---

	@ParameterizedTest
	@ValueSource(ints = { 400, 401, 403, 404, 429 })
	void shouldLogAndNotPropagateOn4xx(int status, CapturedOutput output) {
		respondWith(withStatus(HttpStatus.valueOf(status)));

		notifyCompleted();

		assertThat(output).contains(FAILURE_LOG + ": unexpected HTTP status " + status);
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 504 })
	void shouldLogAndNotPropagateOn5xx(int status, CapturedOutput output) {
		respondWith(withStatus(HttpStatus.valueOf(status))
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"status\":\"error\",\"message\":\"The service is not available, try again later\"}"));

		notifyCompleted();

		assertThat(output).contains(FAILURE_LOG + ": unexpected HTTP status " + status);
	}

	@ParameterizedTest
	@ValueSource(ints = { 301, 302, 304 })
	void shouldTreatRedirectAsFailure(int status, CapturedOutput output) {
		respondWith(withStatus(HttpStatus.valueOf(status)));

		notifyCompleted();

		assertThat(output).contains(FAILURE_LOG + ": unexpected HTTP status " + status);
	}

	@Test
	void shouldLogAndNotPropagateOnTimeout(CapturedOutput output) {
		respondWith(withException(new SocketTimeoutException("Read timed out")));

		notifyCompleted();

		assertThat(output).contains(FAILURE_LOG + ": ResourceAccessException");
	}

	@Test
	void shouldLogAndNotPropagateOnConnectionError(CapturedOutput output) {
		respondWith(withException(new ConnectException("Connection refused")));

		notifyCompleted();

		assertThat(output).contains(FAILURE_LOG + ": ResourceAccessException");
	}

	@Test
	void shouldNotPropagateUnexpectedRuntimeException(CapturedOutput output) {
		respondWith(request -> {
			throw new IllegalStateException("unexpected client failure");
		});

		notifyCompleted();

		assertThat(output).contains(FAILURE_LOG + ": IllegalStateException")
			// Somente o tipo da falha e registrado, nunca a mensagem da excecao
			.doesNotContain("unexpected client failure");
	}
}
