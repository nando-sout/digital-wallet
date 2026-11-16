package com.picpay.digitalwallet.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.picpay.digitalwallet.domain.user.PasswordHasher;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserAlreadyExistsException;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.WalletAlreadyExistsException;
import com.picpay.digitalwallet.domain.wallet.WalletService;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	private static final String FULL_NAME = "Ana Souza";
	private static final String RAW_PASSWORD = "S3nh@Original!";
	private static final String BCRYPT_PATTERN = "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$";

	private static final Long PERSISTED_USER_ID = 1L;

	@Mock
	private UserRepository userRepository;

	@Mock
	private WalletService walletService;

	private PasswordHasher passwordHasher;

	private UserService userService;

	@BeforeEach
	void setUp() {
		// Hasher real (BCrypt de verdade), envolvido em spy apenas para verificar chamadas
		passwordHasher = spy(new PasswordHasher());
		userService = new UserService(userRepository, passwordHasher, walletService);
	}

	private void stubSuccessfulSave() {
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	// Simula o banco atribuindo o ID gerado (a entidade nao tem setter de ID)
	private void stubSaveAssigningId() {
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", PERSISTED_USER_ID);
			return user;
		});
	}

	private User captureSavedUser() {
		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(captor.capture());
		return captor.getValue();
	}

	@Test
	void shouldRegisterCommonUser() {
		stubSuccessfulSave();

		User registered = userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		assertThat(registered.getUserType()).isEqualTo(UserType.COMMON);
		assertThat(registered).isSameAs(captureSavedUser());
	}

	@Test
	void shouldRegisterMerchantUser() {
		stubSuccessfulSave();

		User registered = userService.register("Loja Ltda", "12.345.678/0001-95", "loja@mail.com", RAW_PASSWORD,
				UserType.MERCHANT);

		assertThat(registered.getUserType()).isEqualTo(UserType.MERCHANT);
		assertThat(registered.getDocument()).isEqualTo("12345678000195");
	}

	@Test
	void shouldPersistNormalizedEmailAndUseItForLookup() {
		stubSuccessfulSave();

		userService.register(FULL_NAME, "12345678909", "  Ana.Souza@Mail.COM ", RAW_PASSWORD, UserType.COMMON);

		assertThat(captureSavedUser().getEmail()).isEqualTo("ana.souza@mail.com");
		verify(userRepository).existsByEmail("ana.souza@mail.com");
	}

	@Test
	void shouldPersistNormalizedDocumentAndUseItForLookup() {
		stubSuccessfulSave();

		userService.register(FULL_NAME, "123.456.789-09", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		assertThat(captureSavedUser().getDocument()).isEqualTo("12345678909");
		verify(userRepository).existsByDocument("12345678909");
	}

	@Test
	void shouldPersistOnlyBcryptHash() {
		stubSuccessfulSave();

		userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		String storedHash = captureSavedUser().getPasswordHash();
		assertThat(storedHash).matches(BCRYPT_PATTERN).doesNotContain(RAW_PASSWORD);
		assertThat(passwordHasher.matches(RAW_PASSWORD, storedHash)).isTrue();
	}

	@Test
	void shouldRejectDuplicateDocument() {
		when(userRepository.existsByDocument("12345678909")).thenReturn(true);

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "123.456.789-09", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.DOCUMENT_ALREADY_EXISTS);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void shouldRejectDuplicateEmail() {
		when(userRepository.existsByEmail("ana@mail.com")).thenReturn(true);

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ANA@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.EMAIL_ALREADY_EXISTS);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void shouldRejectPasswordAbove72Utf8BytesBeforeHashing() {
		String asciiPassword73Bytes = "a".repeat(73);
		String accentedPassword74Bytes = "é".repeat(37); // 37 caracteres, 74 bytes em UTF-8

		assertThatThrownBy(() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", asciiPassword73Bytes,
				UserType.COMMON))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining(asciiPassword73Bytes);
		assertThatThrownBy(() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com",
				accentedPassword74Bytes, UserType.COMMON))
			.isInstanceOf(IllegalArgumentException.class);

		verify(passwordHasher, never()).hash(anyString());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void shouldAcceptPasswordWithExactly72Utf8Bytes() {
		stubSuccessfulSave();
		String accentedPassword72Bytes = "é".repeat(36);

		userService.register(FULL_NAME, "12345678909", "ana@mail.com", accentedPassword72Bytes, UserType.COMMON);

		assertThat(passwordHasher.matches(accentedPassword72Bytes, captureSavedUser().getPasswordHash())).isTrue();
	}

	@Test
	void shouldSendEntityWithCorrectDataToRepository() {
		stubSuccessfulSave();

		userService.register(" Ana Souza", "123.456.789-09", " Ana@Mail.com", RAW_PASSWORD, UserType.MERCHANT);

		User saved = captureSavedUser();
		assertThat(saved.getId()).isNull();
		assertThat(saved.getFullName()).isEqualTo(" Ana Souza");
		assertThat(saved.getDocument()).isEqualTo("12345678909");
		assertThat(saved.getEmail()).isEqualTo("ana@mail.com");
		assertThat(saved.getUserType()).isEqualTo(UserType.MERCHANT);
		assertThat(passwordHasher.matches(RAW_PASSWORD, saved.getPasswordHash())).isTrue();
	}

	@Test
	void shouldNeverSendRawPasswordToRepository() {
		stubSuccessfulSave();

		userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		for (Invocation invocation : mockingDetails(userRepository).getInvocations()) {
			for (Object argument : invocation.getArguments()) {
				if (argument instanceof User user) {
					assertThat(user.getPasswordHash()).doesNotContain(RAW_PASSWORD);
					assertThat(user.getFullName()).doesNotContain(RAW_PASSWORD);
					assertThat(user.getEmail()).doesNotContain(RAW_PASSWORD);
					assertThat(user.getDocument()).doesNotContain(RAW_PASSWORD);
				} else {
					assertThat(String.valueOf(argument)).doesNotContain(RAW_PASSWORD);
				}
			}
		}
	}

	@Test
	void shouldTranslateConcurrentDocumentViolationFromDatabase() {
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(uniqueViolation("uk_users_document"));

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.DOCUMENT_ALREADY_EXISTS)
			.hasCauseInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void shouldTranslateConcurrentEmailViolationFromDatabase() {
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(uniqueViolation("uk_users_email_lower"));

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class)
			.hasMessage(UserService.EMAIL_ALREADY_EXISTS);
	}

	@Test
	void shouldPropagateUnrelatedIntegrityViolation() {
		DataIntegrityViolationException unrelated = uniqueViolation("some_other_constraint");
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(unrelated);

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isSameAs(unrelated);
	}

	// --- Integracao com WalletService ---

	@Test
	void shouldCreateWalletOnceForCommonUser() {
		stubSaveAssigningId();

		User registered = userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		verify(walletService, times(1)).createFor(registered);
	}

	@Test
	void shouldCreateWalletOnceForMerchantUser() {
		stubSaveAssigningId();

		User registered = userService.register("Loja Ltda", "12345678000195", "loja@mail.com", RAW_PASSWORD,
				UserType.MERCHANT);

		verify(walletService, times(1)).createFor(registered);
	}

	@Test
	void shouldCreateWalletForExactlyThePersistedUserWithId() {
		stubSaveAssigningId();

		User registered = userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON);

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		InOrder inOrder = inOrder(userRepository, walletService);
		inOrder.verify(userRepository).saveAndFlush(any(User.class));
		inOrder.verify(walletService).createFor(captor.capture());
		assertThat(captor.getValue()).isSameAs(registered).isSameAs(captureSavedUser());
		assertThat(captor.getValue().getId()).isEqualTo(PERSISTED_USER_ID);
	}

	@Test
	void shouldNotCreateWalletWhenDocumentIsDuplicate() {
		when(userRepository.existsByDocument("12345678909")).thenReturn(true);

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class);
		verifyNoInteractions(walletService);
	}

	@Test
	void shouldNotCreateWalletWhenEmailIsDuplicate() {
		when(userRepository.existsByEmail("ana@mail.com")).thenReturn(true);

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class);
		verifyNoInteractions(walletService);
	}

	@Test
	void shouldNotCreateWalletWhenPasswordIsTooLong() {
		assertThatThrownBy(() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", "a".repeat(73),
				UserType.COMMON))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(walletService);
	}

	@Test
	void shouldNotCreateWalletWhenUserInsertFailsOnDatabaseConstraint() {
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(uniqueViolation("uk_users_document"));

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isInstanceOf(UserAlreadyExistsException.class);
		verifyNoInteractions(walletService);
	}

	@Test
	void shouldPropagateWalletCreationFailureUnchanged() {
		stubSaveAssigningId();
		WalletAlreadyExistsException walletFailure = new WalletAlreadyExistsException("User already has a wallet");
		when(walletService.createFor(any(User.class))).thenThrow(walletFailure);

		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isSameAs(walletFailure);
	}

	@Test
	void shouldNotTranslateWalletIntegrityViolationAsUserDuplicate() {
		stubSaveAssigningId();
		DataIntegrityViolationException walletViolation = uniqueViolation("uk_users_document");
		when(walletService.createFor(any(User.class))).thenThrow(walletViolation);

		// Mesmo com nome de constraint de usuario, a falha vinda da carteira nao e convertida
		assertThatThrownBy(
				() -> userService.register(FULL_NAME, "12345678909", "ana@mail.com", RAW_PASSWORD, UserType.COMMON))
			.isSameAs(walletViolation);
	}

	private static DataIntegrityViolationException uniqueViolation(String constraintName) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("duplicate key", new SQLException("duplicate key", "23505"),
						constraintName));
	}

}
