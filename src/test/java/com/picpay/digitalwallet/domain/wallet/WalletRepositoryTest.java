package com.picpay.digitalwallet.domain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@Import(TestcontainersConfiguration.class)
class WalletRepositoryTest {

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestEntityManager entityManager;

	private User persistUser(String document, String email) {
		return userRepository.saveAndFlush(
				new User("Ana Souza", document, email, "$2a$10$hashedPasswordValue", UserType.COMMON));
	}

	@Test
	void shouldFindWalletById() {
		User user = persistUser("12345678909", "ana@mail.com");
		Wallet saved = walletRepository.saveAndFlush(new Wallet(user));
		entityManager.clear();

		assertThat(walletRepository.findById(saved.getId()))
			.hasValueSatisfying(found -> {
				assertThat(found.getUser().getId()).isEqualTo(user.getId());
				assertThat(found.getBalance()).isEqualTo(new BigDecimal("0.00"));
			});
	}

	@Test
	void shouldFindWalletByUserId() {
		User user = persistUser("12345678909", "ana@mail.com");
		Wallet saved = walletRepository.saveAndFlush(new Wallet(user));
		entityManager.clear();

		assertThat(walletRepository.findByUserId(user.getId()))
			.hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
	}

	@Test
	void shouldReturnEmptyWhenUserHasNoWallet() {
		User userWithWallet = persistUser("12345678909", "ana@mail.com");
		walletRepository.saveAndFlush(new Wallet(userWithWallet));
		User userWithoutWallet = persistUser("98765432100", "bia@mail.com");

		assertThat(walletRepository.findByUserId(userWithoutWallet.getId())).isEmpty();
	}

	@Test
	void shouldCheckWalletExistenceByUserId() {
		User userWithWallet = persistUser("12345678909", "ana@mail.com");
		walletRepository.saveAndFlush(new Wallet(userWithWallet));
		User userWithoutWallet = persistUser("98765432100", "bia@mail.com");

		assertThat(walletRepository.existsByUserId(userWithWallet.getId())).isTrue();
		assertThat(walletRepository.existsByUserId(userWithoutWallet.getId())).isFalse();
	}

	@Test
	void shouldRejectSecondWalletForSameUser() {
		User user = persistUser("12345678909", "ana@mail.com");
		walletRepository.saveAndFlush(new Wallet(user));

		assertThatThrownBy(() -> walletRepository.saveAndFlush(new Wallet(user)))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause()
			.hasMessageContaining("uk_wallets_user_id");
	}

	@Test
	void shouldRejectWalletForNonExistentUser() {
		// Referencia (proxy) sem consulta ao banco: o INSERT chega ao PostgreSQL e a FK e avaliada la
		User nonExistentUser = entityManager.getEntityManager().getReference(User.class, Long.MAX_VALUE);

		assertThatThrownBy(() -> walletRepository.saveAndFlush(new Wallet(nonExistentUser)))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause()
			.hasMessageContaining("fk_wallets_user");
	}

}
