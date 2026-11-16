package com.picpay.digitalwallet.domain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.picpay.digitalwallet.TestcontainersConfiguration;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;

/**
 * Testes de lock pessimista com transacoes REAIS e independentes.
 * A classe desativa a transacao do teste (NOT_SUPPORTED): cada thread abre a sua propria
 * transacao via TransactionTemplate, em uma conexao propria do pool. Os dados sao confirmados
 * no PostgreSQL do Testcontainers e removidos ao final de cada teste.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletRepositoryLockTest {

	private static final long TIMEOUT_SECONDS = 10;
	private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.00");

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private DataSource dataSource;

	private TransactionTemplate transaction;
	private ExecutorService executor;
	private Long walletId;

	@BeforeEach
	void setUp() {
		transaction = new TransactionTemplate(transactionManager);
		executor = Executors.newFixedThreadPool(2);
		walletId = transaction.execute(status -> {
			User user = userRepository
				.save(new User("Ana Souza", "12345678909", "ana@mail.com", "$2a$10$hashedPasswordValue", UserType.COMMON));
			Wallet wallet = new Wallet(user);
			wallet.credit(INITIAL_BALANCE);
			return walletRepository.save(wallet).getId();
		});
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		executor.shutdownNow();
		assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
		// Apenas o banco descartavel do Testcontainers
		transaction.executeWithoutResult(status -> {
			walletRepository.deleteAll();
			userRepository.deleteAll();
		});
	}

	@Test
	void shouldRequireActiveTransaction() {
		// Sem transacao, o lock seria liberado imediatamente: o metodo deve falhar em vez disso
		assertThatThrownBy(() -> walletRepository.findByIdForUpdate(walletId))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void shouldReturnEmptyForUnknownWalletInsideTransaction() {
		Optional<Wallet> result = transaction.execute(status -> walletRepository.findByIdForUpdate(Long.MAX_VALUE));

		assertThat(result).isEmpty();
	}

	@Test
	void shouldBlockSecondTransactionWhileFirstHoldsLock() throws Exception {
		CountDownLatch firstLocked = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondLocked = new CountDownLatch(1);

		Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
			walletRepository.findByIdForUpdate(walletId).orElseThrow();
			firstLocked.countDown();
			await(releaseFirst);
		}));
		assertThat(firstLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

		Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
			walletRepository.findByIdForUpdate(walletId).orElseThrow();
			secondLocked.countDown();
		}));

		try {
			// O proprio PostgreSQL confirma: a sessao da segunda transacao esta parada esperando um lock,
			// executando o SELECT ... FOR UPDATE, e bloqueada por outra sessao (a primeira transacao)
			WaitingSession waiting = awaitSessionWaitingForLock();
			// Hibernate 7 traduz PESSIMISTIC_WRITE no PostgreSQL para FOR NO KEY UPDATE: lock exclusivo de
			// linha entre escritores (o mesmo que o PostgreSQL usa em UPDATE de colunas que nao sao chave)
			assertThat(waiting.query()).containsIgnoringCase("from wallets").containsIgnoringCase("for no key update");
			assertThat(waiting.blockingSessions()).isEqualTo(1);

			// E a segunda transacao continua sem o lock enquanto a primeira nao termina
			assertThat(secondLocked.await(1, TimeUnit.SECONDS)).isFalse();
		} finally {
			releaseFirst.countDown();
		}

		first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(secondLocked.getCount()).isZero();
	}

	@Test
	void shouldAcquireLockAfterFirstCommitAndSeeUpdatedBalance() throws Exception {
		CountDownLatch firstLocked = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicLong firstCommitStartedAt = new AtomicLong();
		AtomicLong secondAcquiredAt = new AtomicLong();

		Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
			Wallet wallet = walletRepository.findByIdForUpdate(walletId).orElseThrow();
			firstLocked.countDown();
			await(releaseFirst);
			wallet.debit(new BigDecimal("30.00"));
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					firstCommitStartedAt.set(System.nanoTime());
				}
			});
		}));
		assertThat(firstLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

		Future<BigDecimal> second = executor.submit(() -> transaction.execute(status -> {
			Wallet wallet = walletRepository.findByIdForUpdate(walletId).orElseThrow();
			secondAcquiredAt.set(System.nanoTime());
			return wallet.getBalance();
		}));

		try {
			awaitSessionWaitingForLock();
		} finally {
			releaseFirst.countDown();
		}

		first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		BigDecimal balanceSeenBySecond = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		// A segunda so obteve o lock depois que a primeira iniciou o commit...
		assertThat(secondAcquiredAt.get()).isGreaterThan(firstCommitStartedAt.get());
		// ...e leu o saldo ja confirmado pela primeira (100.00 - 30.00), nao o valor anterior
		assertThat(balanceSeenBySecond).isEqualTo(new BigDecimal("70.00"));
		BigDecimal committedBalance = transaction
			.execute(status -> walletRepository.findById(walletId).orElseThrow().getBalance());
		assertThat(committedBalance).isEqualTo(new BigDecimal("70.00"));
	}

	@Test
	void shouldAcquireLockAfterFirstRollbackAndSeeOriginalBalance() throws Exception {
		CountDownLatch firstLocked = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);

		Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
			Wallet wallet = walletRepository.findByIdForUpdate(walletId).orElseThrow();
			firstLocked.countDown();
			await(releaseFirst);
			wallet.debit(new BigDecimal("30.00"));
			status.setRollbackOnly();
		}));
		assertThat(firstLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

		Future<BigDecimal> second = executor.submit(
				() -> transaction.execute(status -> walletRepository.findByIdForUpdate(walletId).orElseThrow().getBalance()));

		try {
			awaitSessionWaitingForLock();
		} finally {
			releaseFirst.countDown();
		}

		first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(INITIAL_BALANCE);
	}

	private record WaitingSession(String query, int blockingSessions) {
	}

	/** Consulta o pg_stat_activity ate encontrar uma sessao aguardando lock (falha apos o timeout). */
	private WaitingSession awaitSessionWaitingForLock() throws SQLException, InterruptedException {
		String sql = "SELECT query, cardinality(pg_blocking_pids(pid)) FROM pg_stat_activity "
				+ "WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()";
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			while (System.nanoTime() < deadline) {
				try (ResultSet result = statement.executeQuery()) {
					if (result.next()) {
						return new WaitingSession(result.getString(1), result.getInt(2));
					}
				}
				Thread.sleep(50);
			}
		}
		throw new AssertionError("No session waiting for a lock within " + TIMEOUT_SECONDS + "s");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for test coordination latch");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
