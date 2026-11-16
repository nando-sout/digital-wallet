package com.picpay.digitalwallet.domain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.picpay.digitalwallet.domain.user.User;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

	private static final Long USER_ID = 10L;
	private static final Long WALLET_ID = 99L;

	@Mock
	private WalletRepository walletRepository;

	private WalletService walletService;

	@BeforeEach
	void setUp() {
		walletService = new WalletService(walletRepository);
	}

	// Simula um usuario ja persistido: o ID e gerado pelo banco e a entidade nao tem setter
	private static User persistedUser() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(USER_ID);
		return user;
	}

	private void stubSaveAssigningId() {
		when(walletRepository.saveAndFlush(any(Wallet.class))).thenAnswer(invocation -> {
			Wallet wallet = invocation.getArgument(0);
			ReflectionTestUtils.setField(wallet, "id", WALLET_ID);
			return wallet;
		});
	}

	@Test
	void shouldCreateWalletForPersistedUser() {
		User user = persistedUser();
		stubSaveAssigningId();

		walletService.createFor(user);

		ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
		verify(walletRepository).existsByUserId(USER_ID);
		verify(walletRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getUser()).isSameAs(user);
		assertThat(captor.getValue().getBalance()).isEqualTo(new BigDecimal("0.00"));
	}

	@Test
	void shouldReturnPersistedWalletWithId() {
		User user = persistedUser();
		stubSaveAssigningId();

		Wallet wallet = walletService.createFor(user);

		assertThat(wallet.getId()).isEqualTo(WALLET_ID);
		assertThat(wallet.getUser()).isSameAs(user);
	}

	@Test
	void shouldRejectNullUser() {
		assertThatThrownBy(() -> walletService.createFor(null)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(walletRepository);
	}

	@Test
	void shouldRejectUserWithoutId() {
		User transientUser = mock(User.class);
		when(transientUser.getId()).thenReturn(null);

		assertThatThrownBy(() -> walletService.createFor(transientUser))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("persisted");
		verifyNoInteractions(walletRepository);
	}

	@Test
	void shouldRejectUserThatAlreadyHasWalletWithoutSaving() {
		User user = persistedUser();
		when(walletRepository.existsByUserId(USER_ID)).thenReturn(true);

		assertThatThrownBy(() -> walletService.createFor(user))
			.isInstanceOf(WalletAlreadyExistsException.class)
			.hasMessage(WalletService.WALLET_ALREADY_EXISTS);
		verify(walletRepository, never()).saveAndFlush(any());
	}

	@Test
	void shouldTranslateConcurrentUserWalletConstraintViolation() {
		User user = persistedUser();
		DataIntegrityViolationException violation = integrityViolation("uk_wallets_user_id");
		when(walletRepository.saveAndFlush(any(Wallet.class))).thenThrow(violation);

		assertThatThrownBy(() -> walletService.createFor(user))
			.isInstanceOf(WalletAlreadyExistsException.class)
			.hasMessage(WalletService.WALLET_ALREADY_EXISTS)
			.hasCause(violation);
	}

	@Test
	void shouldPropagateOtherIntegrityViolationsUntranslated() {
		User user = persistedUser();
		DataIntegrityViolationException foreignKeyViolation = integrityViolation("fk_wallets_user");
		when(walletRepository.saveAndFlush(any(Wallet.class))).thenThrow(foreignKeyViolation);

		assertThatThrownBy(() -> walletService.createFor(user)).isSameAs(foreignKeyViolation);
	}

	@Test
	void shouldPropagateIntegrityViolationWithoutConstraintName() {
		User user = persistedUser();
		DataIntegrityViolationException unknown = new DataIntegrityViolationException("unknown");
		when(walletRepository.saveAndFlush(any(Wallet.class))).thenThrow(unknown);

		assertThatThrownBy(() -> walletService.createFor(user)).isSameAs(unknown);
	}

	private static DataIntegrityViolationException integrityViolation(String constraintName) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("violation", new SQLException("violation", "23505"), constraintName));
	}

}
