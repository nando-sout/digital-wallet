package com.picpay.digitalwallet.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.picpay.digitalwallet.TestcontainersConfiguration;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TransferRepositoryTest {

	@Autowired
	private TransferRepository transferRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private TestEntityManager entityManager;

	private Wallet payer;
	private Wallet payee;

	@BeforeEach
	void setUp() {
		payer = persistWallet("12345678909", "ana@mail.com", UserType.COMMON);
		payee = persistWallet("12345678000195", "loja@mail.com", UserType.MERCHANT);
	}

	private Wallet persistWallet(String document, String email, UserType type) {
		User user = userRepository.saveAndFlush(new User("Nome", document, email, "$2a$10$hashedPasswordValue", type));
		return walletRepository.saveAndFlush(new Wallet(user));
	}

	@Test
	void shouldSaveAndFindCompletedTransfer() {
		Transfer saved = transferRepository.saveAndFlush(Transfer.completed(payer, payee, new BigDecimal("42.50")));
		entityManager.clear();

		assertThat(saved.getId()).isNotNull();
		assertThat(transferRepository.findById(saved.getId()))
			.hasValueSatisfying(found -> {
				assertThat(found.getStatus()).isEqualTo(TransferStatus.COMPLETED);
				assertThat(found.getAmount()).isEqualTo(new BigDecimal("42.50"));
				assertThat(found.getPayer().getId()).isEqualTo(payer.getId());
				assertThat(found.getPayee().getId()).isEqualTo(payee.getId());
				assertThat(found.getRejectionReason()).isNull();
				assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
			});
	}

	@Test
	void shouldSaveAndFindRejectedTransferWithReason() {
		Transfer saved = transferRepository
			.saveAndFlush(Transfer.rejected(payer, payee, new BigDecimal("10.00"), "INSUFFICIENT_BALANCE"));
		entityManager.clear();

		assertThat(transferRepository.findById(saved.getId()))
			.hasValueSatisfying(found -> {
				assertThat(found.getStatus()).isEqualTo(TransferStatus.REJECTED);
				assertThat(found.getRejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");
			});
	}

	@Test
	void shouldReturnEmptyForUnknownId() {
		assertThat(transferRepository.findById(Long.MAX_VALUE)).isEmpty();
	}

	@Test
	void shouldCountPersistedTransfers() {
		transferRepository.saveAndFlush(Transfer.completed(payer, payee, BigDecimal.ONE));
		transferRepository.saveAndFlush(Transfer.rejected(payer, payee, BigDecimal.TEN, null));

		assertThat(transferRepository.count()).isEqualTo(2);
	}

}
