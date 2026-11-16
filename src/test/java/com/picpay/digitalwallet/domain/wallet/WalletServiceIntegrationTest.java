package com.picpay.digitalwallet.domain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.picpay.digitalwallet.TestcontainersConfiguration;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, WalletService.class })
class WalletServiceIntegrationTest {

	@Autowired
	private WalletService walletService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestEntityManager entityManager;

	private User persistUser() {
		return userRepository.saveAndFlush(
				new User("Ana Souza", "12345678909", "ana@mail.com", "$2a$10$hashedPasswordValue", UserType.COMMON));
	}

	@Test
	void shouldCreateAndReadPersistedWallet() {
		User user = persistUser();

		Wallet created = walletService.createFor(user);
		entityManager.clear();

		assertThat(created.getId()).isNotNull();
		assertThat(walletRepository.findById(created.getId())).isPresent();
	}

	@Test
	void shouldStartWithZeroBalanceAndTwoDecimalPlaces() {
		User user = persistUser();

		Wallet created = walletService.createFor(user);
		entityManager.clear();

		Wallet stored = walletRepository.findById(created.getId()).orElseThrow();
		assertThat(stored.getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(stored.getBalance().scale()).isEqualTo(2);
	}

	@Test
	void shouldLinkWalletToUser() {
		User user = persistUser();

		Wallet created = walletService.createFor(user);
		entityManager.clear();

		assertThat(walletRepository.findByUserId(user.getId()))
			.hasValueSatisfying(stored -> {
				assertThat(stored.getId()).isEqualTo(created.getId());
				assertThat(stored.getUser().getId()).isEqualTo(user.getId());
			});
	}

	@Test
	void shouldRejectSecondWalletForSameUser() {
		User user = persistUser();
		walletService.createFor(user);

		assertThatThrownBy(() -> walletService.createFor(user))
			.isInstanceOf(WalletAlreadyExistsException.class)
			.hasMessage(WalletService.WALLET_ALREADY_EXISTS);
	}

	@Test
	void shouldTranslateRealUniqueConstraintViolationWhenExistenceCheckMissesConcurrentInsert() {
		User user = persistUser();
		walletService.createFor(user);

		// Simula a corrida: existsByUserId nao enxerga a carteira ja criada, e o INSERT chega ao PostgreSQL
		WalletRepository staleRepository = mock(WalletRepository.class, delegatesTo(walletRepository));
		doReturn(false).when(staleRepository).existsByUserId(anyLong());
		WalletService serviceWithStaleCheck = new WalletService(staleRepository);

		assertThatThrownBy(() -> serviceWithStaleCheck.createFor(user))
			.isInstanceOf(WalletAlreadyExistsException.class)
			.hasMessage(WalletService.WALLET_ALREADY_EXISTS)
			.hasCauseInstanceOf(DataIntegrityViolationException.class);
	}

}
