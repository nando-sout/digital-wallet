package com.picpay.digitalwallet.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.picpay.digitalwallet.TestcontainersConfiguration;
import com.picpay.digitalwallet.domain.user.PasswordHasher;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserAlreadyExistsException;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletAlreadyExistsException;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;
import com.picpay.digitalwallet.domain.wallet.WalletService;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, UserService.class, PasswordHasher.class, WalletService.class })
class UserServiceIntegrationTest {

	private static final String RAW_PASSWORD = "S3nh@Original!";

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private PasswordHasher passwordHasher;

	@Autowired
	private TestEntityManager entityManager;

	// Spy sobre o bean real: por padrao executa o WalletService verdadeiro (com proxy transacional);
	// apenas o teste de rollback o faz falhar. O Spring reseta o spy apos cada teste.
	@MockitoSpyBean
	private WalletService walletService;

	// --- Fluxo de sucesso: servicos reais + PostgreSQL do Testcontainers ---

	@Test
	void shouldPersistRegisteredUserWithNormalizedDataAndHashedPassword() {
		User registered = userService.register("Ana Souza", "123.456.789-09", " Ana@Mail.COM ", RAW_PASSWORD,
				UserType.COMMON);
		entityManager.clear();

		User stored = userRepository.findById(registered.getId()).orElseThrow();
		assertThat(stored.getDocument()).isEqualTo("12345678909");
		assertThat(stored.getEmail()).isEqualTo("ana@mail.com");
		assertThat(stored.getUserType()).isEqualTo(UserType.COMMON);
		assertThat(stored.getPasswordHash()).isNotEqualTo(RAW_PASSWORD).startsWith("$2");
		assertThat(passwordHasher.matches(RAW_PASSWORD, stored.getPasswordHash())).isTrue();
	}

	@Test
	void shouldCreateWalletWithZeroBalanceForCommonUser() {
		User registered = userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD,
				UserType.COMMON);
		entityManager.clear();

		Wallet wallet = walletRepository.findByUserId(registered.getId()).orElseThrow();
		assertThat(wallet.getId()).isNotNull();
		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(wallet.getUser().getId()).isEqualTo(registered.getId());
		assertThat(wallet.getUser().getUserType()).isEqualTo(UserType.COMMON);
	}

	@Test
	void shouldCreateWalletWithZeroBalanceForMerchantUser() {
		User registered = userService.register("Loja Ltda", "12.345.678/0001-95", "loja@mail.com", RAW_PASSWORD,
				UserType.MERCHANT);
		entityManager.clear();

		assertThat(userRepository.findByDocument("12345678000195"))
			.hasValueSatisfying(stored -> assertThat(stored.getUserType()).isEqualTo(UserType.MERCHANT));
		Wallet wallet = walletRepository.findByUserId(registered.getId()).orElseThrow();
		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(wallet.getUser().getId()).isEqualTo(registered.getId());
	}

	@Test
	void shouldNotAllowSecondWalletForRegisteredUser() {
		User registered = userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD,
				UserType.COMMON);

		assertThatThrownBy(() -> walletService.createFor(registered))
			.isInstanceOf(WalletAlreadyExistsException.class);
		assertThat(walletRepository.findByUserId(registered.getId())).isPresent();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void shouldCommitUserAndWalletTogether() {
		try {
			// Sem transacao do teste: o register abre e confirma a sua propria transacao
			User registered = userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD,
					UserType.COMMON);

			assertThat(userRepository.existsByDocument("12345678909")).isTrue();
			assertThat(walletRepository.existsByUserId(registered.getId())).isTrue();
		} finally {
			deleteCommittedTestData();
		}
	}

	// --- Atomicidade: falha na carteira desfaz o usuario ---

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void shouldRollbackUserWhenWalletCreationFails() {
		IllegalStateException walletFailure = new IllegalStateException("simulated wallet failure");
		doThrow(walletFailure).when(walletService).createFor(any(User.class));

		try {
			assertThatThrownBy(() -> userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD,
					UserType.COMMON))
				.isSameAs(walletFailure);

			// O INSERT do usuario chegou ao banco (recebeu ID) antes da falha da carteira...
			verify(walletService).createFor(argThat(user -> user.getId() != null));
			// ...e foi desfeito pelo rollback: nada permanece no PostgreSQL apos a transacao
			assertThat(userRepository.existsByDocument("12345678909")).isFalse();
			assertThat(userRepository.existsByEmail("ana@mail.com")).isFalse();
			assertThat(userRepository.count()).isZero();
			assertThat(walletRepository.count()).isZero();
		} finally {
			deleteCommittedTestData();
		}
	}

	// --- Duplicidade (comportamento existente) ---

	@Test
	void shouldRejectDuplicateDocumentWithDifferentFormatting() {
		userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		assertThatThrownBy(() -> userService.register("Bia Lima", "123.456.789-09", "bia@mail.com", RAW_PASSWORD,
				UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.DOCUMENT_ALREADY_EXISTS);
	}

	@Test
	void shouldRejectDuplicateEmailWithDifferentCase() {
		userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		assertThatThrownBy(() -> userService.register("Bia Lima", "98765432100", "ANA@Mail.com", RAW_PASSWORD,
				UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.EMAIL_ALREADY_EXISTS);
	}

	// Simula a corrida entre cadastros simultaneos: o existsBy... nao enxerga o outro cadastro,
	// e a unicidade precisa ser garantida pela constraint do PostgreSQL.

	@Test
	void shouldTranslateRealDocumentConstraintViolationWhenExistenceCheckMissesConcurrentInsert() {
		userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);
		UserService serviceWithStaleCheck = serviceWhoseExistenceChecksAlwaysReturnFalse();

		assertThatThrownBy(() -> serviceWithStaleCheck.register("Bia Lima", "12345678909", "bia@mail.com",
				RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.DOCUMENT_ALREADY_EXISTS)
			.hasCauseInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldTranslateRealEmailConstraintViolationWhenExistenceCheckMissesConcurrentInsert() {
		userService.register("Ana Souza", "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);
		UserService serviceWithStaleCheck = serviceWhoseExistenceChecksAlwaysReturnFalse();

		assertThatThrownBy(() -> serviceWithStaleCheck.register("Bia Lima", "98765432100", "Ana@MAIL.com",
				RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.EMAIL_ALREADY_EXISTS)
			.hasCauseInstanceOf(DataIntegrityViolationException.class);
	}

	private UserService serviceWhoseExistenceChecksAlwaysReturnFalse() {
		// Todas as chamadas vao ao repositorio real (e ao banco), exceto as verificacoes de existencia
		UserRepository staleRepository = mock(UserRepository.class, delegatesTo(userRepository));
		doReturn(false).when(staleRepository).existsByDocument(anyString());
		doReturn(false).when(staleRepository).existsByEmail(anyString());
		return new UserService(staleRepository, passwordHasher, walletService);
	}

	// Limpa apenas o banco descartavel do Testcontainers, nos testes que confirmam transacoes reais
	private void deleteCommittedTestData() {
		walletRepository.deleteAll();
		userRepository.deleteAll();
	}

}
