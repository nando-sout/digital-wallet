package com.picpay.digitalwallet.infrastructure.authorizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.picpay.digitalwallet.application.transfer.AuthorizationResult;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercita o RestClient REAL montado pela configuracao (fabrica de requisicoes e timeouts)
 * contra um servidor HTTP criado no proprio teste em 127.0.0.1. Nenhuma chamada sai da maquina.
 */
class AuthorizerClientConfigurationTest {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(300);

	private static final String AUTHORIZED_BODY = "{\"status\":\"success\",\"data\":{\"authorization\":true}}";

	private final List<HttpServer> servers = new ArrayList<>();
	private final List<ExecutorService> serverExecutors = new ArrayList<>();

	@AfterEach
	void stopServers() throws InterruptedException {
		servers.forEach(server -> server.stop(0));
		for (ExecutorService executor : serverExecutors) {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private URI startServer(HttpHandler handler) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		server.setExecutor(executor);
		server.createContext("/api/v2/authorize", handler);
		server.start();
		servers.add(server);
		serverExecutors.add(executor);
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/authorize");
	}

	private URI startLocalServer(int status, String body, Duration delay, AtomicInteger requestCount) throws IOException {
		return startServer(exchange -> {
			requestCount.incrementAndGet();
			try {
				Thread.sleep(delay.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				exchange.close();
				return;
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
	}

	private static HttpTransferAuthorizer realAuthorizer(URI url, Duration readTimeout) {
		AuthorizerProperties properties = new AuthorizerProperties(url, CONNECT_TIMEOUT, readTimeout);
		return new HttpTransferAuthorizer(AuthorizerClientConfiguration.authorizerRestClient(properties));
	}

	@Test
	void shouldAuthorizeThroughRealHttpClientAndJsonConverter() throws IOException {
		AtomicInteger requests = new AtomicInteger();
		URI url = startLocalServer(200, "{\"status\":\"success\",\"data\":{\"authorization\":true}}", Duration.ZERO,
				requests);

		AuthorizationResult result = realAuthorizer(url, Duration.ofSeconds(3)).authorize(1L, 2L, BigDecimal.TEN);

		assertThat(result).isEqualTo(AuthorizationResult.AUTHORIZED);
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void shouldDenyThroughRealHttpClientOn403() throws IOException {
		AtomicInteger requests = new AtomicInteger();
		URI url = startLocalServer(403, "{\"status\":\"fail\",\"data\":{\"authorization\":false}}", Duration.ZERO,
				requests);

		assertThat(realAuthorizer(url, Duration.ofSeconds(3)).authorize(1L, 2L, BigDecimal.TEN))
			.isEqualTo(AuthorizationResult.DENIED);
	}

	@Test
	void shouldApplyConfiguredReadTimeout() throws IOException, InterruptedException {
		AtomicInteger requests = new AtomicInteger();
		URI url = startLocalServer(200, "{\"status\":\"success\",\"data\":{\"authorization\":true}}",
				Duration.ofSeconds(3), requests);

		long start = System.nanoTime();
		AuthorizationResult result = realAuthorizer(url, SHORT_READ_TIMEOUT).authorize(1L, 2L, BigDecimal.TEN);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		// O servidor so responderia apos 3s (com autorizacao!), mas o read timeout de 300 ms interrompe antes
		assertThat(result).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
		assertThat(elapsed).isGreaterThanOrEqualTo(SHORT_READ_TIMEOUT).isLessThan(Duration.ofSeconds(2));
		// A requisicao chegou ao servidor (que nao respondeu a tempo). O timeout do HttpClient do JDK cobre a
		// requisicao inteira, entao sob carga o cliente pode desistir antes de o servidor registrar a chegada:
		// aguarda-se o registro em vez de exigir que ja tenha ocorrido
		assertThat(awaitCount(requests, 1, Duration.ofSeconds(5))).isEqualTo(1);
	}

	private static int awaitCount(AtomicInteger counter, int expected, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (counter.get() < expected && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		return counter.get();
	}

	@ParameterizedTest
	@ValueSource(ints = { 301, 302, 303, 307, 308 })
	void shouldNotFollowRedirectToAnotherServer(int redirectStatus) throws IOException {
		// Destino do redirecionamento: AUTORIZARIA se fosse consultado
		AtomicInteger redirectTargetRequests = new AtomicInteger();
		URI redirectTarget = startLocalServer(200, AUTHORIZED_BODY, Duration.ZERO, redirectTargetRequests);

		// Endpoint configurado: responde apenas com o redirecionamento para o outro servidor
		AtomicInteger configuredEndpointRequests = new AtomicInteger();
		URI configuredEndpoint = startServer(exchange -> {
			configuredEndpointRequests.incrementAndGet();
			exchange.getResponseHeaders().add("Location", redirectTarget.toString());
			exchange.sendResponseHeaders(redirectStatus, -1);
			exchange.close();
		});

		AuthorizationResult result = realAuthorizer(configuredEndpoint, Duration.ofSeconds(3))
			.authorize(1L, 2L, BigDecimal.TEN);

		assertThat(result).isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
		assertThat(configuredEndpointRequests.get()).isEqualTo(1);
		assertThat(redirectTargetRequests.get()).as("redirect target must never be contacted").isZero();
	}

	@Test
	void shouldReturnTechnicalFailureWhenConnectionIsRefused() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		URI url = URI.create("http://127.0.0.1:" + closedPort + "/api/v2/authorize");

		assertThat(realAuthorizer(url, Duration.ofSeconds(3)).authorize(1L, 2L, BigDecimal.TEN))
			.isEqualTo(AuthorizationResult.TECHNICAL_FAILURE);
	}

	@Test
	void shouldBindPropertiesFromApplicationProperties() {
		new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(AuthorizerClientConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(HttpTransferAuthorizer.class);
				AuthorizerProperties properties = context.getBean(AuthorizerProperties.class);
				assertThat(properties.url()).hasHost("util.devi.tools").hasPath("/api/v2/authorize");
				assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
				assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
			});
		// Apenas leitura de configuracao: nenhuma chamada ao autorizador e feita neste teste
	}

	@Test
	void shouldFailStartupWhenUrlIsMissing() {
		new ApplicationContextRunner().withUserConfiguration(AuthorizerClientConfiguration.class)
			.withPropertyValues("app.authorizer.connect-timeout=2s", "app.authorizer.read-timeout=3s")
			.run(context -> assertThat(context).hasFailed());
	}

}
