package com.picpay.digitalwallet.infrastructure.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.picpay.digitalwallet.application.transfer.TransferNotifier;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercita o RestClient REAL montado pela configuracao (fabrica de requisicoes e timeouts)
 * contra um servidor HTTP criado no proprio teste em 127.0.0.1. Nenhuma chamada sai da maquina.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotifierClientConfigurationTest {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(300);
	private static final String PATH = "/api/v1/notify";
	private static final String FAILURE_LOG = "Payee notification failed for transfer 7";

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
		server.createContext(PATH, handler);
		server.start();
		servers.add(server);
		serverExecutors.add(executor);
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
	}

	private URI startStatusServer(int status, Duration delay, AtomicInteger requestCount) throws IOException {
		return startServer(exchange -> {
			requestCount.incrementAndGet();
			try {
				Thread.sleep(delay.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
	}

	private static TransferNotifier realNotifier(URI url, Duration readTimeout) {
		NotifierProperties properties = new NotifierProperties(url, CONNECT_TIMEOUT, readTimeout);
		return new HttpTransferNotifier(NotifierClientConfiguration.notifierRestClient(properties));
	}

	private static void notifyCompleted(TransferNotifier notifier) {
		assertThatNoException().isThrownBy(() -> notifier.notifyTransferCompleted(7L));
	}

	@Test
	void shouldSendBodilessPostThroughRealHttpClient(CapturedOutput output) throws IOException {
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<byte[]> body = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		URI url = startServer(exchange -> {
			method.set(exchange.getRequestMethod());
			body.set(exchange.getRequestBody().readAllBytes());
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});

		notifyCompleted(realNotifier(url, READ_TIMEOUT));

		assertThat(method.get()).isEqualTo("POST");
		assertThat(body.get()).isEmpty();
		assertThat(contentType.get()).isNull();
		assertThat(output).doesNotContain(FAILURE_LOG);
	}

	@Test
	void shouldNotPropagateServerErrorThroughRealHttpClient(CapturedOutput output) throws IOException {
		AtomicInteger requests = new AtomicInteger();
		URI url = startStatusServer(504, Duration.ZERO, requests);

		notifyCompleted(realNotifier(url, READ_TIMEOUT));

		assertThat(requests.get()).isEqualTo(1);
		assertThat(output).contains(FAILURE_LOG + ": unexpected HTTP status 504");
	}

	@Test
	void shouldApplyConfiguredReadTimeout(CapturedOutput output) throws IOException {
		AtomicInteger requests = new AtomicInteger();
		URI url = startStatusServer(204, Duration.ofSeconds(3), requests);

		long start = System.nanoTime();
		notifyCompleted(realNotifier(url, SHORT_READ_TIMEOUT));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		// O servidor so responderia apos 3s; o read timeout de 300 ms interrompe antes, sem excecao
		assertThat(elapsed).isGreaterThanOrEqualTo(SHORT_READ_TIMEOUT).isLessThan(Duration.ofSeconds(2));
		assertThat(output).contains(FAILURE_LOG + ":");
	}

	@ParameterizedTest
	@ValueSource(ints = { 301, 302, 307, 308 })
	void shouldNotFollowRedirectToAnotherServer(int redirectStatus, CapturedOutput output) throws IOException {
		AtomicInteger redirectTargetRequests = new AtomicInteger();
		URI redirectTarget = startStatusServer(204, Duration.ZERO, redirectTargetRequests);
		URI configuredEndpoint = startServer(exchange -> {
			exchange.getResponseHeaders().add("Location", redirectTarget.toString());
			exchange.sendResponseHeaders(redirectStatus, -1);
			exchange.close();
		});

		notifyCompleted(realNotifier(configuredEndpoint, READ_TIMEOUT));

		assertThat(redirectTargetRequests.get()).as("redirect target must never be contacted").isZero();
		assertThat(output).contains(FAILURE_LOG + ": unexpected HTTP status " + redirectStatus);
	}

	@Test
	void shouldNotPropagateWhenConnectionIsRefused(CapturedOutput output) throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		URI url = URI.create("http://127.0.0.1:" + closedPort + PATH);

		notifyCompleted(realNotifier(url, READ_TIMEOUT));

		assertThat(output).contains(FAILURE_LOG + ": ResourceAccessException");
	}

	@Test
	void shouldBindPropertiesFromApplicationProperties() {
		new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(NotifierClientConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(HttpTransferNotifier.class);
				assertThat(context).getBean(TransferNotifier.class).isInstanceOf(HttpTransferNotifier.class);
				NotifierProperties properties = context.getBean(NotifierProperties.class);
				assertThat(properties.url()).hasScheme("https").hasHost("util.devi.tools").hasPath(PATH);
				assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
				assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
			});
		// Apenas leitura de configuracao: nenhuma notificacao e enviada neste teste
	}

	@Test
	void shouldAllowOverridingUrlWithNotifierUrlVariable() {
		new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(NotifierClientConfiguration.class)
			.withSystemProperties("NOTIFIER_URL=http://notifier.test/custom")
			.run(context -> assertThat(context.getBean(NotifierProperties.class).url())
				.isEqualTo(URI.create("http://notifier.test/custom")));
	}

	@Test
	void shouldFailStartupWhenUrlIsMissing() {
		new ApplicationContextRunner().withUserConfiguration(NotifierClientConfiguration.class)
			.withPropertyValues("app.notifier.connect-timeout=2s", "app.notifier.read-timeout=3s")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void shouldFailStartupWhenTimeoutsAreMissing() {
		new ApplicationContextRunner().withUserConfiguration(NotifierClientConfiguration.class)
			.withPropertyValues("app.notifier.url=http://notifier.test/api/v1/notify")
			.run(context -> assertThat(context).hasFailed());
	}
}
