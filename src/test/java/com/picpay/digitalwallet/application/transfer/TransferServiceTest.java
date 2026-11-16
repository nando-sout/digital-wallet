package com.picpay.digitalwallet.application.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.picpay.digitalwallet.domain.transfer.Transfer;
import com.picpay.digitalwallet.domain.transfer.TransferRejectionReason;
import com.picpay.digitalwallet.domain.transfer.TransferRepository;
import com.picpay.digitalwallet.domain.transfer.TransferStatus;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserNotFoundException;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletNotFoundException;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

	private static final Long PAYER_ID = 4L;
	private static final Long PAYEE_ID = 15L;
	private static final Long PAYER_WALLET_ID = 40L;
	private static final Long PAYEE_WALLET_ID = 150L;
	private static final BigDecimal PAYER_BALANCE = new BigDecimal("500.00");
	private static final BigDecimal PAYEE_BALANCE = new BigDecimal("20.00");
	private static final BigDecimal AMOUNT = new BigDecimal("100.00");
	private static final Long TRANSFER_ID = 900L;

	@Mock
	private UserRepository userRepository;

	@Mock
	private WalletRepository walletRepository;

	@Mock
	private TransferRepository transferRepository;

	@Mock
	private TransferAuthorizer transferAuthorizer;

	@Mock
	private TransferExecutor transferExecutor;

	@Mock
	private TransferNotifier transferNotifier;

	private TransferService transferService;

	private Wallet payerWallet;
	private Wallet payeeWallet;

	@BeforeEach
	void setUp() {
		transferService = new TransferService(userRepository, walletRepository, transferRepository, transferAuthorizer,
				transferExecutor, transferNotifier);
	}

	// --- Cenario: carteiras reais (com saldo) e repositorios simulados ---

	private static User user(Long id, UserType type) {
		User user = new User("Nome " + id, "1234567890" + id, "u" + id + "@mail.com", "$2a$10$hash", type);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private static Wallet wallet(Long id, User owner, BigDecimal balance) {
		Wallet wallet = new Wallet(owner);
		ReflectionTestUtils.setField(wallet, "id", id);
		wallet.credit(balance);
		return wallet;
	}

	private void givenParticipants(UserType payerType, BigDecimal payerBalance) {
		User payer = user(PAYER_ID, payerType);
		User payee = user(PAYEE_ID, UserType.MERCHANT);
		payerWallet = wallet(PAYER_WALLET_ID, payer, payerBalance);
		payeeWallet = wallet(PAYEE_WALLET_ID, payee, PAYEE_BALANCE);
		when(userRepository.findById(PAYER_ID)).thenReturn(Optional.of(payer));
		when(userRepository.findById(PAYEE_ID)).thenReturn(Optional.of(payee));
		when(walletRepository.findByUserId(PAYER_ID)).thenReturn(Optional.of(payerWallet));
		when(walletRepository.findByUserId(PAYEE_ID)).thenReturn(Optional.of(payeeWallet));
	}

	private void givenValidParticipants() {
		givenParticipants(UserType.COMMON, PAYER_BALANCE);
	}

	private void stubRejectionSave() {
		when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Transfer capturedRejection() {
		ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
		verify(transferRepository).save(captor.capture());
		return captor.getValue();
	}

	/** O servico so le carteiras (findByUserId): nunca trava, grava ou altera saldos. */
	private void assertNoBalanceOperationByService() {
		verify(walletRepository).findByUserId(PAYER_ID);
		verify(walletRepository).findByUserId(PAYEE_ID);
		verifyNoMoreInteractions(walletRepository);
	}

	private void assertBalancesUnchanged(BigDecimal expectedPayerBalance) {
		assertThat(payerWallet.getBalance()).isEqualTo(expectedPayerBalance);
		assertThat(payeeWallet.getBalance()).isEqualTo(PAYEE_BALANCE);
	}

	/** Simula o retorno do executor (transacao ja confirmada) com uma transferencia que tem ID. */
	private Transfer givenExecutorReturns(Transfer transfer) {
		ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
		when(transferExecutor.execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT)).thenReturn(transfer);
		return transfer;
	}

	private Transfer givenExecutorCompletes() {
		return givenExecutorReturns(Transfer.completed(payerWallet, payeeWallet, AMOUNT));
	}

	// --- Autorizado ---

	@Test
	void shouldForwardAuthorizedTransferToExecutor() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.AUTHORIZED);
		Transfer completed = givenExecutorCompletes();

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		assertThat(result).isSameAs(completed);
		verify(transferExecutor).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT);
		verifyNoInteractions(transferRepository);
		assertNoBalanceOperationByService();
		assertBalancesUnchanged(PAYER_BALANCE);
	}

	@Test
	void shouldNormalizeAmountToTwoDecimalPlacesBeforeAuthorizingAndExecuting() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, new BigDecimal("100.00")))
			.thenReturn(AuthorizationResult.AUTHORIZED);
		givenExecutorCompletes();

		transferService.transfer(PAYER_ID, PAYEE_ID, new BigDecimal("100"));

		verify(transferExecutor).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, new BigDecimal("100.00"));
	}

	@Test
	void shouldConsultAuthorizerWhenBalanceIsExactlyTheAmount() {
		givenParticipants(UserType.COMMON, AMOUNT);
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.AUTHORIZED);
		givenExecutorCompletes();

		transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		verify(transferExecutor).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT);
	}

	// --- Notificacao do recebedor ---

	@Test
	void shouldNotifyPayeeOnceAfterExecutorReturnsCompletedTransfer() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.AUTHORIZED);
		Transfer completed = givenExecutorCompletes();

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		assertThat(result).isSameAs(completed);
		// Ordem: o executor (transacional) retorna, ou seja, ja confirmou; so depois o recebedor e notificado
		var inOrder = org.mockito.Mockito.inOrder(transferExecutor, transferNotifier);
		inOrder.verify(transferExecutor, times(1)).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT);
		inOrder.verify(transferNotifier, times(1)).notifyTransferCompleted(TRANSFER_ID);
		verifyNoMoreInteractions(transferExecutor, transferNotifier);
		// Nenhuma consulta adicional para montar a notificacao
		verify(userRepository).findById(PAYER_ID);
		verify(userRepository).findById(PAYEE_ID);
		verifyNoMoreInteractions(userRepository);
		assertNoBalanceOperationByService();
	}

	@Test
	void shouldNotNotifyWhenExecutorRejectsOnLockedBalanceRevalidation() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.AUTHORIZED);
		Transfer rejected = givenExecutorReturns(Transfer.rejected(payerWallet, payeeWallet, AMOUNT,
				TransferRejectionReason.INSUFFICIENT_BALANCE.name()));

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		assertThat(result).isSameAs(rejected);
		assertThat(result.getStatus()).isEqualTo(TransferStatus.REJECTED);
		verify(transferExecutor, times(1)).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT);
		verifyNoInteractions(transferNotifier);
	}

	@Test
	void shouldKeepCompletedResultWhenNotifierFailsUnexpectedly() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.AUTHORIZED);
		Transfer completed = givenExecutorCompletes();
		// Violacao do contrato "nunca lanca": o servico ainda assim nao pode transformar o sucesso em erro
		doThrow(new IllegalStateException("boom")).when(transferNotifier).notifyTransferCompleted(TRANSFER_ID);

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		assertThat(result).isSameAs(completed);
		assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		verify(transferExecutor, times(1)).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT);
		verify(transferNotifier, times(1)).notifyTransferCompleted(TRANSFER_ID);
		verifyNoInteractions(transferRepository);
	}

	// --- Negado e falha tecnica ---

	@Test
	void shouldRecordRejectedTransferWhenAuthorizerDenies() {
		givenValidParticipants();
		stubRejectionSave();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.DENIED);

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		Transfer rejected = capturedRejection();
		assertThat(result).isSameAs(rejected);
		assertThat(rejected.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(rejected.getRejectionReason()).isEqualTo("NOT_AUTHORIZED");
		assertThat(rejected.getPayer()).isSameAs(payerWallet);
		assertThat(rejected.getPayee()).isSameAs(payeeWallet);
		assertThat(rejected.getAmount()).isEqualTo(AMOUNT);
		verifyNoInteractions(transferExecutor, transferNotifier);
		assertNoBalanceOperationByService();
		assertBalancesUnchanged(PAYER_BALANCE);
	}

	@Test
	void shouldThrowWithoutRecordingWhenAuthorizerFailsTechnically() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT))
			.thenReturn(AuthorizationResult.TECHNICAL_FAILURE);

		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT))
			.isInstanceOf(AuthorizerUnavailableException.class);

		verifyNoInteractions(transferRepository, transferExecutor, transferNotifier);
		assertNoBalanceOperationByService();
		assertBalancesUnchanged(PAYER_BALANCE);
	}

	// --- Recusas antes do autorizador ---

	@Test
	void shouldRecordRejectedTransferWhenPayerIsMerchantWithoutConsultingAuthorizer() {
		givenParticipants(UserType.MERCHANT, PAYER_BALANCE);
		stubRejectionSave();

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		assertThat(result.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(capturedRejection().getRejectionReason()).isEqualTo("MERCHANT_CANNOT_TRANSFER");
		verifyNoInteractions(transferAuthorizer, transferExecutor, transferNotifier);
		assertNoBalanceOperationByService();
		assertBalancesUnchanged(PAYER_BALANCE);
	}

	@Test
	void shouldRecordRejectedTransferWhenBalanceIsInsufficientWithoutConsultingAuthorizer() {
		BigDecimal lowBalance = new BigDecimal("99.99");
		givenParticipants(UserType.COMMON, lowBalance);
		stubRejectionSave();

		Transfer result = transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		assertThat(result.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(capturedRejection().getRejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");
		verifyNoInteractions(transferAuthorizer, transferExecutor, transferNotifier);
		assertNoBalanceOperationByService();
		assertBalancesUnchanged(lowBalance);
	}

	@Test
	void shouldFailWhenPayerUserDoesNotExist() {
		when(userRepository.findById(PAYER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT))
			.isInstanceOf(UserNotFoundException.class)
			.hasMessageContaining(String.valueOf(PAYER_ID));
		verifyNoInteractions(walletRepository, transferRepository, transferAuthorizer, transferExecutor);
	}

	@Test
	void shouldFailWhenPayeeUserDoesNotExist() {
		when(userRepository.findById(PAYER_ID)).thenReturn(Optional.of(user(PAYER_ID, UserType.COMMON)));
		when(userRepository.findById(PAYEE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT))
			.isInstanceOf(UserNotFoundException.class)
			.hasMessageContaining(String.valueOf(PAYEE_ID));
		verifyNoInteractions(walletRepository, transferRepository, transferAuthorizer, transferExecutor);
	}

	@Test
	void shouldFailWhenPayerWalletDoesNotExist() {
		when(userRepository.findById(PAYER_ID)).thenReturn(Optional.of(user(PAYER_ID, UserType.COMMON)));
		when(userRepository.findById(PAYEE_ID)).thenReturn(Optional.of(user(PAYEE_ID, UserType.COMMON)));
		when(walletRepository.findByUserId(PAYER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT))
			.isInstanceOf(WalletNotFoundException.class)
			.hasMessageContaining(String.valueOf(PAYER_ID));
		verifyNoInteractions(transferRepository, transferAuthorizer, transferExecutor);
	}

	@Test
	void shouldFailWhenPayeeWalletDoesNotExist() {
		User payer = user(PAYER_ID, UserType.COMMON);
		when(userRepository.findById(PAYER_ID)).thenReturn(Optional.of(payer));
		when(userRepository.findById(PAYEE_ID)).thenReturn(Optional.of(user(PAYEE_ID, UserType.COMMON)));
		when(walletRepository.findByUserId(PAYER_ID)).thenReturn(Optional.of(wallet(PAYER_WALLET_ID, payer, PAYER_BALANCE)));
		when(walletRepository.findByUserId(PAYEE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT))
			.isInstanceOf(WalletNotFoundException.class)
			.hasMessageContaining(String.valueOf(PAYEE_ID));
		verifyNoInteractions(transferRepository, transferAuthorizer, transferExecutor);
	}

	// --- Validacoes de entrada (antes de qualquer acesso) ---

	@Test
	void shouldRejectTransferToSameUser() {
		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYER_ID, AMOUNT))
			.isInstanceOf(InvalidTransferException.class)
			.hasMessage("payer and payee must be different users");
		verifyNoInteractions(userRepository, walletRepository, transferRepository, transferAuthorizer, transferExecutor);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"0      | value must be positive: 0",
			"-0.01  | value must be positive: -0.01",
			"10.001 | value must have at most 2 decimal places: 10.001" })
	void shouldPreserveValidationMessages(String value, String expectedMessage) {
		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, new BigDecimal(value)))
			.isInstanceOf(InvalidTransferException.class)
			.hasMessage(expectedMessage);
	}

	@Test
	void shouldNotUseIllegalArgumentExceptionForInputValidation() {
		// Separacao exigida pelo tratamento HTTP: IllegalArgumentException no fluxo e erro interno (500)
		assertThatThrownBy(() -> transferService.transfer(null, PAYEE_ID, AMOUNT))
			.isNotInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, new BigDecimal("1.234")))
			.isNotInstanceOf(IllegalArgumentException.class)
			.hasCauseInstanceOf(ArithmeticException.class);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "0", "0.00", "-0.01", "-100", "10.001", "0.005" })
	void shouldRejectInvalidAmount(String value) {
		BigDecimal amount = value == null ? null : new BigDecimal(value);

		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, PAYEE_ID, amount))
			.isInstanceOf(InvalidTransferException.class);
		verifyNoInteractions(userRepository, walletRepository, transferRepository, transferAuthorizer, transferExecutor);
	}

	@Test
	void shouldRejectNullParticipants() {
		assertThatThrownBy(() -> transferService.transfer(null, PAYEE_ID, AMOUNT))
			.isInstanceOf(InvalidTransferException.class);
		assertThatThrownBy(() -> transferService.transfer(PAYER_ID, null, AMOUNT))
			.isInstanceOf(InvalidTransferException.class);
		verifyNoInteractions(userRepository, walletRepository, transferRepository, transferAuthorizer, transferExecutor);
	}

	// --- Arquitetura ---

	@Test
	void shouldNotBeTransactionalSoTheAuthorizerCallRunsOutsideAnyTransaction() {
		assertThat(TransferService.class.isAnnotationPresent(Transactional.class)).isFalse();
		assertThat(TransferService.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
		for (Method method : TransferService.class.getDeclaredMethods()) {
			assertThat(method.isAnnotationPresent(Transactional.class)).as(method.getName()).isFalse();
			assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class)).as(method.getName()).isFalse();
		}
	}

	@Test
	void shouldNeverCallExecutorOrSaveRejectionBeforeAuthorizerWhenEligible() {
		givenValidParticipants();
		when(transferAuthorizer.authorize(PAYER_ID, PAYEE_ID, AMOUNT)).thenReturn(AuthorizationResult.AUTHORIZED);
		givenExecutorCompletes();

		transferService.transfer(PAYER_ID, PAYEE_ID, AMOUNT);

		var inOrder = org.mockito.Mockito.inOrder(userRepository, walletRepository, transferAuthorizer, transferExecutor);
		inOrder.verify(userRepository).findById(PAYER_ID);
		inOrder.verify(userRepository).findById(PAYEE_ID);
		inOrder.verify(walletRepository).findByUserId(PAYER_ID);
		inOrder.verify(walletRepository).findByUserId(PAYEE_ID);
		inOrder.verify(transferAuthorizer).authorize(PAYER_ID, PAYEE_ID, AMOUNT);
		inOrder.verify(transferExecutor).execute(PAYER_WALLET_ID, PAYEE_WALLET_ID, AMOUNT);
		verify(transferRepository, never()).save(any());
	}

}
