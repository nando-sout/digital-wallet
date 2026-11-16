package com.picpay.digitalwallet.application.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.picpay.digitalwallet.TestcontainersConfiguration;
import com.picpay.digitalwallet.domain.transfer.Transfer;
import com.picpay.digitalwallet.domain.transfer.TransferRejectionReason;
import com.picpay.digitalwallet.domain.transfer.TransferRepository;
import com.picpay.digitalwallet.domain.transfer.TransferStatus;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;

/**
 * Executor contra o PostgreSQL do Testcontainers, com transacoes REAIS: a transacao do teste e desativada
 * (NOT_SUPPORTED), entao cada chamada ao executor abre e confirma (ou desfaz) a sua propria transacao via
 * proxy @Transactional. Os saldos sao sempre conferidos relendo o banco em uma nova transacao.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, TransactionalTransferExecutorIntegrationTest.FaultInjection.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionalTransferExecutorIntegrationTest {

	private static final long TIMEOUT_SECONDS = 20;
	// Maior valor de NUMERIC(19,2)
	private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");

	@Autowired
	private TransferExecutor executor;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private WalletLockFaults faults;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransferRepository transferRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transaction;
	private Long payerWalletId;
	private Long payeeWalletId;

	@BeforeEach
	void setUp() {
		transaction = new TransactionTemplate(transactionManager);
		payerWalletId = createWallet("Ana Souza", "12345678909", "ana@mail.com", UserType.COMMON, "100.00");
		payeeWalletId = createWallet("Loja Ltda", "12345678000195", "loja@mail.com", UserType.MERCHANT, "50.00");
	}

	@AfterEach
	void tearDown() {
		faults.clear();
		// Apenas o banco descartavel do Testcontainers
		transaction.executeWithoutResult(status -> {
			transferRepository.deleteAll();
			walletRepository.deleteAll();
			userRepository.deleteAll();
		});
	}

	private Long createWallet(String name, String document, String email, UserType type, String balance) {
		return transaction.execute(status -> {
			User user = userRepository.save(new User(name, document, email, "$2a$10$hashedPasswordValue", type));
			Wallet wallet = new Wallet(user);
			wallet.credit(new BigDecimal(balance));
			return walletRepository.save(wallet).getId();
		});
	}

	private void setBalance(Long walletId, BigDecimal target) {
		transaction.executeWithoutResult(status -> {
			Wallet wallet = walletRepository.findById(walletId).orElseThrow();
			wallet.credit(target.subtract(wallet.getBalance()));
		});
	}

	private BigDecimal committedBalance(Long walletId) {
		return transaction.execute(status -> walletRepository.findById(walletId).orElseThrow().getBalance());
	}

	private void assertCommittedBalances(String payerBalance, String payeeBalance) {
		assertThat(committedBalance(payerWalletId)).isEqualTo(new BigDecimal(payerBalance));
		assertThat(committedBalance(payeeWalletId)).isEqualTo(new BigDecimal(payeeBalance));
	}

	/**
	 * Injecao de falhas: o executor REAL (com proxy @Transactional) recebe um WalletRepository que delega ao
	 * repositorio real; apenas a carteira configurada em {@link WalletLockFaults} e devolvida, ja travada,
	 * como um spy que falha no debito ou no credito. Sem falha configurada, o comportamento e o real.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class FaultInjection {

		@Bean
		WalletLockFaults walletLockFaults() {
			return new WalletLockFaults();
		}

		@Bean
		TransactionalTransferExecutor transactionalTransferExecutor(WalletRepository walletRepository,
				TransferRepository transferRepository, WalletLockFaults faults) {
			WalletRepository faulty = mock(WalletRepository.class, delegatesTo(walletRepository));
			doAnswer(invocation -> faults.apply(walletRepository.findByIdForUpdate(invocation.getArgument(0))))
				.when(faulty).findByIdForUpdate(anyLong());
			return new TransactionalTransferExecutor(faulty, transferRepository);
		}
	}

	static class WalletLockFaults {

		private volatile Long walletId;
		private volatile boolean failDebit;

		void failOn(Long walletId, boolean failDebit) {
			this.failDebit = failDebit;
			this.walletId = walletId;
		}

		void clear() {
			this.walletId = null;
		}

		Optional<Wallet> apply(Optional<Wallet> locked) {
			Long target = walletId;
			if (target == null || locked.isEmpty() || !target.equals(locked.get().getId())) {
				return locked;
			}
			Wallet failing = spy(locked.get());
			if (failDebit) {
				doThrow(new IllegalStateException("debit failure")).when(failing).debit(any());
			} else {
				doThrow(new IllegalStateException("credit failure")).when(failing).credit(any());
			}
			return Optional.of(failing);
		}
	}

	@Test
	void shouldBeTransactionalProxy() {
		assertThat(AopUtils.isAopProxy(executor)).isTrue();
	}

	// --- Sucesso ---

	@Test
	void shouldCommitDebitCreditAndCompletedTransfer() {
		Transfer result = executor.execute(payerWalletId, payeeWalletId, new BigDecimal("30.00"));

		assertCommittedBalances("70.00", "80.00");
		assertThat(result.getId()).isNotNull();
		Transfer stored = transaction.execute(status -> {
			Transfer transfer = transferRepository.findById(result.getId()).orElseThrow();
			assertThat(transfer.getPayer().getId()).isEqualTo(payerWalletId);
			assertThat(transfer.getPayee().getId()).isEqualTo(payeeWalletId);
			return transfer;
		});
		assertThat(stored.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(stored.getAmount()).isEqualTo(new BigDecimal("30.00"));
		assertThat(stored.getRejectionReason()).isNull();
	}

	@Test
	void shouldKeepBalancesConsistentAcrossSequentialTransfersInBothDirections() {
		executor.execute(payerWalletId, payeeWalletId, new BigDecimal("100.00"));
		executor.execute(payeeWalletId, payerWalletId, new BigDecimal("25.50"));
		executor.execute(payerWalletId, payeeWalletId, new BigDecimal("0.01"));

		assertCommittedBalances("25.49", "124.51");
		assertThat(committedBalance(payerWalletId).add(committedBalance(payeeWalletId)))
			.isEqualTo(new BigDecimal("150.00"));
		assertThat(transferRepository.count()).isEqualTo(3);
	}

	@Test
	void shouldRecordRejectionWithoutMovingBalancesWhenBalanceIsInsufficient() {
		Transfer result = executor.execute(payerWalletId, payeeWalletId, new BigDecimal("100.01"));

		assertThat(result.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(result.getRejectionReason()).isEqualTo(TransferRejectionReason.INSUFFICIENT_BALANCE.name());
		assertCommittedBalances("100.00", "50.00");
		assertThat(transferRepository.count()).isEqualTo(1);
	}

	// --- Falha no debito ---

	@Test
	void shouldRollBackEverythingWhenDebitFails() {
		faults.failOn(payerWalletId, true);

		assertThatThrownBy(() -> executor.execute(payerWalletId, payeeWalletId, new BigDecimal("30.00")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("debit failure");

		assertCommittedBalances("100.00", "50.00");
		assertThat(transferRepository.count()).isZero();
	}

	@Test
	void shouldRejectInvalidAmountInDebitWithoutPersistingAnything() {
		assertThatThrownBy(() -> executor.execute(payerWalletId, payeeWalletId, new BigDecimal("10.001")))
			.isInstanceOf(IllegalArgumentException.class);

		assertCommittedBalances("100.00", "50.00");
		assertThat(transferRepository.count()).isZero();
	}

	// --- Falha no credito: o debito ja aplicado deve ser desfeito ---

	@Test
	void shouldRollBackDebitWhenCreditFails() {
		// A carteira pagadora e a entidade gerenciada real: o debito fica pendente na transacao
		faults.failOn(payeeWalletId, false);

		assertThatThrownBy(() -> executor.execute(payerWalletId, payeeWalletId, new BigDecimal("30.00")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("credit failure");

		assertCommittedBalances("100.00", "50.00");
		assertThat(transferRepository.count()).isZero();
	}

	@Test
	void shouldRollBackDebitAndTransferWhenDatabaseRejectsCredit() {
		// Credito valido em memoria, mas que estoura NUMERIC(19,2) no PostgreSQL durante o flush
		setBalance(payeeWalletId, MAX_BALANCE);

		assertThatThrownBy(() -> executor.execute(payerWalletId, payeeWalletId, new BigDecimal("30.00")))
			.isInstanceOf(DataAccessException.class);

		assertThat(committedBalance(payerWalletId)).isEqualTo(new BigDecimal("100.00"));
		assertThat(committedBalance(payeeWalletId)).isEqualTo(MAX_BALANCE);
		assertThat(transferRepository.count()).isZero();
	}

	// --- Concorrencia ---

	@Test
	void shouldNeverOverdraftUnderConcurrentTransfersFromSamePayer() throws Exception {
		int attempts = 10;
		List<Callable<Transfer>> tasks = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			tasks.add(() -> executor.execute(payerWalletId, payeeWalletId, new BigDecimal("20.00")));
		}

		List<Transfer> results = runConcurrently(tasks);

		long completed = results.stream().filter(t -> t.getStatus() == TransferStatus.COMPLETED).count();
		long rejected = results.stream().filter(t -> t.getStatus() == TransferStatus.REJECTED).count();
		assertThat(completed).isEqualTo(5);
		assertThat(rejected).isEqualTo(5);
		assertCommittedBalances("0.00", "150.00");
		assertThat(transferRepository.count()).isEqualTo(attempts);
	}

	@Test
	void shouldNotDeadlockOnConcurrentOppositeTransfers() throws Exception {
		setBalance(payeeWalletId, new BigDecimal("100.00"));
		List<Callable<Transfer>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			boolean forward = i % 2 == 0;
			tasks.add(() -> forward
					? executor.execute(payerWalletId, payeeWalletId, new BigDecimal("1.00"))
					: executor.execute(payeeWalletId, payerWalletId, new BigDecimal("1.00")));
		}

		List<Transfer> results = runConcurrently(tasks);

		assertThat(results).allMatch(t -> t.getStatus() == TransferStatus.COMPLETED);
		assertCommittedBalances("100.00", "100.00");
	}

	private static List<Transfer> runConcurrently(List<Callable<Transfer>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Transfer>> futures = new ArrayList<>();
			for (Callable<Transfer> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<Transfer> results = new ArrayList<>();
			for (Future<Transfer> future : futures) {
				results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
			assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
		}
	}
}
