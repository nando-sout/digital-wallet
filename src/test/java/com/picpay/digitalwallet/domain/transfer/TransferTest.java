package com.picpay.digitalwallet.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;

class TransferTest {

	private final Wallet payer = new Wallet(
			new User("Ana Souza", "12345678909", "ana@mail.com", "$2a$10$hashedPasswordValue", UserType.COMMON));
	private final Wallet payee = new Wallet(
			new User("Loja Ltda", "12345678000195", "loja@mail.com", "$2a$10$hashedPasswordValue", UserType.MERCHANT));

	@Test
	void shouldCreateCompletedTransfer() {
		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

		Transfer transfer = Transfer.completed(payer, payee, new BigDecimal("100.50"));

		assertThat(transfer.getId()).isNull();
		assertThat(transfer.getPayer()).isSameAs(payer);
		assertThat(transfer.getPayee()).isSameAs(payee);
		assertThat(transfer.getAmount()).isEqualTo(new BigDecimal("100.50"));
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transfer.getRejectionReason()).isNull();
		assertThat(transfer.getCreatedAt()).isBetween(before, Instant.now());
	}

	@Test
	void shouldCreateRejectedTransferWithReason() {
		Transfer transfer = Transfer.rejected(payer, payee, new BigDecimal("10.00"), "  Insufficient balance ");

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(transfer.getRejectionReason()).isEqualTo("Insufficient balance");
		assertThat(transfer.getAmount()).isEqualTo(new BigDecimal("10.00"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void shouldCreateRejectedTransferWithoutReason(String reason) {
		Transfer transfer = Transfer.rejected(payer, payee, new BigDecimal("10.00"), reason);

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
		assertThat(transfer.getRejectionReason()).isNull();
	}

	@Test
	void shouldRejectReasonLongerThan255Characters() {
		assertThat(Transfer.rejected(payer, payee, BigDecimal.TEN, "x".repeat(255)).getRejectionReason()).hasSize(255);
		assertThatThrownBy(() -> Transfer.rejected(payer, payee, BigDecimal.TEN, "x".repeat(256)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldNormalizeAmountToTwoDecimalPlaces() {
		assertThat(Transfer.completed(payer, payee, new BigDecimal("7")).getAmount()).isEqualTo(new BigDecimal("7.00"));
		assertThat(Transfer.completed(payer, payee, new BigDecimal("7.500")).getAmount())
			.isEqualTo(new BigDecimal("7.50"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-0.01", "-100" })
	void shouldRejectZeroOrNegativeAmount(String amount) {
		assertThatThrownBy(() -> Transfer.completed(payer, payee, new BigDecimal(amount)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Transfer.rejected(payer, payee, new BigDecimal(amount), "reason"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.001", "0.005" })
	void shouldRejectAmountThatWouldRequireRounding(String amount) {
		assertThatThrownBy(() -> Transfer.completed(payer, payee, new BigDecimal(amount)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldRejectNullArguments() {
		assertThatThrownBy(() -> Transfer.completed(null, payee, BigDecimal.TEN)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Transfer.completed(payer, null, BigDecimal.TEN)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Transfer.completed(payer, payee, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldRejectSameWalletInstance() {
		assertThatThrownBy(() -> Transfer.completed(payer, payer, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldRejectDifferentInstancesOfSamePersistedWallet() {
		Wallet sameWalletLoadedTwice = new Wallet(
				new User("Ana Souza", "12345678909", "ana@mail.com", "$2a$10$hashedPasswordValue", UserType.COMMON));
		ReflectionTestUtils.setField(payer, "id", 7L);
		ReflectionTestUtils.setField(sameWalletLoadedTwice, "id", 7L);

		assertThatThrownBy(() -> Transfer.completed(payer, sameWalletLoadedTwice, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldNotChangeWalletBalances() {
		Transfer.completed(payer, payee, new BigDecimal("50.00"));

		assertThat(payer.getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(payee.getBalance()).isEqualTo(new BigDecimal("0.00"));
	}

}
