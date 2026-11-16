package com.picpay.digitalwallet.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.picpay.digitalwallet.TestcontainersConfiguration;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;

import jakarta.persistence.PersistenceException;

/**
 * Persistencia via TestEntityManager (nesta etapa nao ha TransferRepository).
 * As violacoes de constraint usam SQL nativo para contornar as validacoes da entidade
 * e comprovar que o proprio PostgreSQL rejeita os dados.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TransferPersistenceTest {

	private static final String INSERT_TRANSFER = "INSERT INTO transfers "
			+ "(amount, payer_wallet_id, payee_wallet_id, status, created_at) VALUES (?1, ?2, ?3, ?4, now())";

	@Autowired
	private TestEntityManager entityManager;

	private Wallet payer;
	private Wallet payee;

	@BeforeEach
	void setUp() {
		payer = persistWallet("Ana Souza", "12345678909", "ana@mail.com", UserType.COMMON);
		payee = persistWallet("Loja Ltda", "12345678000195", "loja@mail.com", UserType.MERCHANT);
	}

	private Wallet persistWallet(String name, String document, String email, UserType type) {
		User user = entityManager.persist(new User(name, document, email, "$2a$10$hashedPasswordValue", type));
		return entityManager.persistAndFlush(new Wallet(user));
	}

	private Transfer reload(Transfer transfer) {
		entityManager.clear();
		return entityManager.find(Transfer.class, transfer.getId());
	}

	// --- Persistencia ---

	@Test
	void shouldPersistCompletedTransfer() {
		Transfer saved = entityManager.persistAndFlush(Transfer.completed(payer, payee, new BigDecimal("150.75")));

		Transfer stored = reload(saved);

		assertThat(stored.getId()).isNotNull();
		assertThat(stored.getAmount()).isEqualTo(new BigDecimal("150.75"));
		assertThat(stored.getPayer().getId()).isEqualTo(payer.getId());
		assertThat(stored.getPayee().getId()).isEqualTo(payee.getId());
		assertThat(stored.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(stored.getRejectionReason()).isNull();
		assertThat(stored.getCreatedAt()).isEqualTo(saved.getCreatedAt());
	}

	@Test
	void shouldPersistRejectedTransferWithReason() {
		Transfer saved = entityManager
			.persistAndFlush(Transfer.rejected(payer, payee, new BigDecimal("20.00"), "Insufficient balance"));

		Transfer stored = reload(saved);

		assertThat(stored.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(stored.getRejectionReason()).isEqualTo("Insufficient balance");
		assertThat(stored.getAmount()).isEqualTo(new BigDecimal("20.00"));
	}

	@Test
	void shouldPersistRejectedTransferWithoutReason() {
		Transfer saved = entityManager.persistAndFlush(Transfer.rejected(payer, payee, new BigDecimal("20.00"), null));

		Transfer stored = reload(saved);

		assertThat(stored.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(stored.getRejectionReason()).isNull();
	}

	@Test
	void shouldKeepWalletsLazyWhenLoadingTransfer() {
		Transfer saved = entityManager.persistAndFlush(Transfer.completed(payer, payee, BigDecimal.TEN));

		Transfer stored = reload(saved);

		var persistenceUnitUtil = entityManager.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil();
		assertThat(persistenceUnitUtil.isLoaded(stored, "payer")).isFalse();
		assertThat(persistenceUnitUtil.isLoaded(stored, "payee")).isFalse();
	}

	@Test
	void shouldNotChangeWalletBalancesWhenPersistingTransfer() {
		entityManager.persistAndFlush(Transfer.completed(payer, payee, new BigDecimal("50.00")));
		entityManager.clear();

		assertThat(entityManager.find(Wallet.class, payer.getId()).getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(entityManager.find(Wallet.class, payee.getId()).getBalance()).isEqualTo(new BigDecimal("0.00"));
	}

	// --- Constraints do banco ---

	@ParameterizedTest
	@ValueSource(strings = { "0.00", "-0.01", "-100.00" })
	void shouldRejectZeroOrNegativeAmountAtDatabaseLevel(String amount) {
		assertThatThrownBy(() -> insertNative(new BigDecimal(amount), payer.getId(), payee.getId(), "COMPLETED"))
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("ck_transfers_amount_positive");
	}

	@Test
	void shouldRejectSameWalletAtDatabaseLevel() {
		assertThatThrownBy(() -> insertNative(BigDecimal.TEN, payer.getId(), payer.getId(), "COMPLETED"))
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("ck_transfers_distinct_wallets");
	}

	@Test
	void shouldRejectUnknownStatusAtDatabaseLevel() {
		assertThatThrownBy(() -> insertNative(BigDecimal.TEN, payer.getId(), payee.getId(), "PENDING"))
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("ck_transfers_status");
	}

	@Test
	void shouldRejectNonExistentPayerWallet() {
		assertThatThrownBy(() -> insertNative(BigDecimal.TEN, Long.MAX_VALUE, payee.getId(), "COMPLETED"))
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("fk_transfers_payer_wallet");
	}

	@Test
	void shouldRejectNonExistentPayeeWallet() {
		assertThatThrownBy(() -> insertNative(BigDecimal.TEN, payer.getId(), Long.MAX_VALUE, "COMPLETED"))
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("fk_transfers_payee_wallet");
	}

	@Test
	void shouldPreventDeletingWalletReferencedByTransfer() {
		entityManager.persistAndFlush(Transfer.completed(payer, payee, BigDecimal.TEN));

		assertThatThrownBy(() -> entityManager.getEntityManager()
			.createNativeQuery("DELETE FROM wallets WHERE id = ?1")
			.setParameter(1, payee.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("fk_transfers_payee_wallet");
	}

	@Test
	void shouldCreateCompositeIndexesForWalletAndDateQueries() {
		@SuppressWarnings("unchecked")
		List<String> indexDefinitions = entityManager.getEntityManager()
			.createNativeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'transfers' ORDER BY indexname")
			.getResultList();

		assertThat(indexDefinitions).hasSize(3)
			.anySatisfy(def -> assertThat(def).contains("ix_transfers_payer_wallet_created_at")
				.contains("(payer_wallet_id, created_at)"))
			.anySatisfy(def -> assertThat(def).contains("ix_transfers_payee_wallet_created_at")
				.contains("(payee_wallet_id, created_at)"))
			.anySatisfy(def -> assertThat(def).contains("transfers_pkey"));
	}

	private void insertNative(BigDecimal amount, Long payerWalletId, Long payeeWalletId, String status) {
		entityManager.getEntityManager()
			.createNativeQuery(INSERT_TRANSFER)
			.setParameter(1, amount)
			.setParameter(2, payerWalletId)
			.setParameter(3, payeeWalletId)
			.setParameter(4, status)
			.executeUpdate();
	}

}
