package com.picpay.digitalwallet.application.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.picpay.digitalwallet.domain.transfer.Transfer;
import com.picpay.digitalwallet.domain.transfer.TransferRejectionReason;
import com.picpay.digitalwallet.domain.transfer.TransferRepository;
import com.picpay.digitalwallet.domain.transfer.TransferStatus;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;

@ExtendWith(MockitoExtension.class)
class TransactionalTransferExecutorTest {

	private static final Long LOW_WALLET_ID = 10L;
	private static final Long HIGH_WALLET_ID = 20L;
	private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.00");
	private static final BigDecimal AMOUNT = new BigDecimal("40.00");

	@Mock
	private WalletRepository walletRepository;

	@Mock
	private TransferRepository transferRepository;

	private TransactionalTransferExecutor executor;

	private Wallet lowWallet;
	private Wallet highWallet;

	@BeforeEach
	void setUp() {
		executor = new TransactionalTransferExecutor(walletRepository, transferRepository);
		lowWallet = wallet(LOW_WALLET_ID, INITIAL_BALANCE);
		highWallet = wallet(HIGH_WALLET_ID, INITIAL_BALANCE);
	}

	private static Wallet wallet(Long id, BigDecimal balance) {
		User owner = new User("Nome " + id, "1234567890" + id, "u" + id + "@mail.com", "$2a$10$hash", UserType.COMMON);
		Wallet wallet = new Wallet(owner);
		ReflectionTestUtils.setField(wallet, "id", id);
		wallet.credit(balance);
		return wallet;
	}

	private void givenLocked(Wallet... wallets) {
		for (Wallet wallet : wallets) {
			when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
		}
	}

	private void stubSave() {
		when(transferRepository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Transfer savedTransfer() {
		ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
		verify(transferRepository).saveAndFlush(captor.capture());
		return captor.getValue();
	}

	// --- Sucesso ---

	@Test
	void shouldDebitPayerCreditPayeeAndRecordCompletedTransfer() {
		givenLocked(lowWallet, highWallet);
		stubSave();

		Transfer result = executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, AMOUNT);

		assertThat(lowWallet.getBalance()).isEqualTo(new BigDecimal("60.00"));
		assertThat(highWallet.getBalance()).isEqualTo(new BigDecimal("140.00"));
		assertThat(result).isSameAs(savedTransfer());
		assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(result.getPayer()).isSameAs(lowWallet);
		assertThat(result.getPayee()).isSameAs(highWallet);
		assertThat(result.getAmount()).isEqualTo(AMOUNT);
		assertThat(result.getRejectionReason()).isNull();
	}

	@Test
	void shouldPreserveTotalBalance() {
		givenLocked(lowWallet, highWallet);
		stubSave();
		BigDecimal totalBefore = lowWallet.getBalance().add(highWallet.getBalance());

		executor.execute(HIGH_WALLET_ID, LOW_WALLET_ID, AMOUNT);

		assertThat(lowWallet.getBalance().add(highWallet.getBalance())).isEqualTo(totalBefore);
	}

	@Test
	void shouldAllowTransferOfEntireBalance() {
		givenLocked(lowWallet, highWallet);
		stubSave();

		Transfer result = executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, INITIAL_BALANCE);

		assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(lowWallet.getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(highWallet.getBalance()).isEqualTo(new BigDecimal("200.00"));
	}

	// --- Ordem dos locks ---

	@Test
	void shouldLockWalletsInAscendingIdOrderWhenPayerHasLowerId() {
		givenLocked(lowWallet, highWallet);
		stubSave();

		executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, AMOUNT);

		InOrder order = inOrder(walletRepository, transferRepository);
		order.verify(walletRepository).findByIdForUpdate(LOW_WALLET_ID);
		order.verify(walletRepository).findByIdForUpdate(HIGH_WALLET_ID);
		order.verify(transferRepository).saveAndFlush(any(Transfer.class));
	}

	@Test
	void shouldLockWalletsInAscendingIdOrderWhenPayerHasHigherId() {
		givenLocked(lowWallet, highWallet);
		stubSave();

		Transfer result = executor.execute(HIGH_WALLET_ID, LOW_WALLET_ID, AMOUNT);

		InOrder order = inOrder(walletRepository);
		order.verify(walletRepository).findByIdForUpdate(LOW_WALLET_ID);
		order.verify(walletRepository).findByIdForUpdate(HIGH_WALLET_ID);
		// Os papeis seguem os parametros, nao a ordem de travamento
		assertThat(result.getPayer()).isSameAs(highWallet);
		assertThat(result.getPayee()).isSameAs(lowWallet);
		assertThat(highWallet.getBalance()).isEqualTo(new BigDecimal("60.00"));
		assertThat(lowWallet.getBalance()).isEqualTo(new BigDecimal("140.00"));
	}

	// --- Saldo insuficiente (revalidado com lock) ---

	@Test
	void shouldRecordRejectionWithoutMovingBalancesWhenBalanceIsInsufficientUnderLock() {
		givenLocked(lowWallet, highWallet);
		stubSave();

		Transfer result = executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, new BigDecimal("100.01"));

		assertThat(result.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(result.getRejectionReason()).isEqualTo(TransferRejectionReason.INSUFFICIENT_BALANCE.name());
		assertThat(result.getPayer()).isSameAs(lowWallet);
		assertThat(result.getPayee()).isSameAs(highWallet);
		assertThat(lowWallet.getBalance()).isEqualTo(INITIAL_BALANCE);
		assertThat(highWallet.getBalance()).isEqualTo(INITIAL_BALANCE);
	}

	// --- Falhas ---

	@Test
	void shouldPropagateDebitFailureWithoutCreditingOrRecording() {
		Wallet failingPayer = spy(lowWallet);
		doThrow(new IllegalStateException("debit failure")).when(failingPayer).debit(any());
		givenLocked(failingPayer, highWallet);

		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, AMOUNT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("debit failure");

		assertThat(highWallet.getBalance()).isEqualTo(INITIAL_BALANCE);
		verify(transferRepository, never()).saveAndFlush(any());
		verify(transferRepository, never()).save(any());
	}

	@Test
	void shouldRejectInvalidAmountInDebitWithoutChangingBalances() {
		givenLocked(lowWallet, highWallet);

		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, new BigDecimal("10.001")))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(lowWallet.getBalance()).isEqualTo(INITIAL_BALANCE);
		assertThat(highWallet.getBalance()).isEqualTo(INITIAL_BALANCE);
		verifyNoInteractions(transferRepository);
	}

	@Test
	void shouldPropagateCreditFailureWithoutRecordingTransfer() {
		// Em memoria o debito ja ocorreu; quem o desfaz no banco e o rollback da transacao (teste de integracao)
		Wallet failingPayee = spy(highWallet);
		doThrow(new IllegalStateException("credit failure")).when(failingPayee).credit(any());
		givenLocked(lowWallet, failingPayee);

		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, AMOUNT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("credit failure");

		verify(transferRepository, never()).saveAndFlush(any());
		verify(transferRepository, never()).save(any());
	}

	@Test
	void shouldPropagatePersistenceFailure() {
		givenLocked(lowWallet, highWallet);
		when(transferRepository.saveAndFlush(any(Transfer.class))).thenThrow(new IllegalStateException("db failure"));

		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, AMOUNT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("db failure");
	}

	@Test
	void shouldFailWhenWalletDisappearsBeforeLock() {
		when(walletRepository.findByIdForUpdate(LOW_WALLET_ID)).thenReturn(Optional.of(lowWallet));
		when(walletRepository.findByIdForUpdate(HIGH_WALLET_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, AMOUNT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(HIGH_WALLET_ID.toString());

		assertThat(lowWallet.getBalance()).isEqualTo(INITIAL_BALANCE);
		verifyNoInteractions(transferRepository);
	}

	// --- Entradas invalidas ---

	@Test
	void shouldRejectNullWalletIdsBeforeAnyLock() {
		assertThatThrownBy(() -> executor.execute(null, HIGH_WALLET_ID, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, null, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(walletRepository, transferRepository);
	}

	@Test
	void shouldRejectSameWalletBeforeAnyLock() {
		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, LOW_WALLET_ID, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(walletRepository, transferRepository);
	}

	@Test
	void shouldRejectNullAmountBeforeAnyLock() {
		assertThatThrownBy(() -> executor.execute(LOW_WALLET_ID, HIGH_WALLET_ID, null))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(walletRepository, transferRepository);
	}

	// --- Transacao ---

	@Test
	void shouldRunExecuteInsideSpringTransaction() throws NoSuchMethodException {
		Transactional transactional = TransactionalTransferExecutor.class
			.getMethod("execute", Long.class, Long.class, BigDecimal.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		// Padrao REQUIRED + rollback em RuntimeException: debito, credito e registro sao atomicos
		assertThat(transactional.readOnly()).isFalse();
	}
}
