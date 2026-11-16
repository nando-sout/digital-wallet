package com.picpay.digitalwallet.domain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserType;

class WalletTest {

	private final User user = new User("Ana Souza", "12345678909", "ana@mail.com", "$2a$10$hashedPasswordValue",
			UserType.COMMON);

	@Test
	void shouldCreateWalletWithZeroBalanceAndTwoDecimalPlaces() {
		Wallet wallet = new Wallet(user);

		assertThat(wallet.getBalance()).isEqualByComparingTo("0");
		assertThat(wallet.getBalance().scale()).isEqualTo(2);
		assertThat(wallet.getId()).isNull();
	}

	@Test
	void shouldBelongToGivenUser() {
		Wallet wallet = new Wallet(user);

		assertThat(wallet.getUser()).isSameAs(user);
	}

	@Test
	void shouldRejectNullUser() {
		assertThatThrownBy(() -> new Wallet(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void shouldCreditPositiveAmount() {
		Wallet wallet = new Wallet(user);

		wallet.credit(new BigDecimal("150.25"));
		wallet.credit(new BigDecimal("9.75"));

		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("160.00"));
	}

	@Test
	void shouldDebitPositiveAmount() {
		Wallet wallet = new Wallet(user);
		wallet.credit(new BigDecimal("100.00"));

		wallet.debit(new BigDecimal("30.50"));

		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("69.50"));
	}

	@Test
	void shouldAllowDebitOfEntireBalance() {
		Wallet wallet = new Wallet(user);
		wallet.credit(new BigDecimal("42.10"));

		wallet.debit(new BigDecimal("42.10"));

		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("0.00"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-0.01", "-100" })
	void shouldRejectCreditOfZeroOrNegativeAmount(String amount) {
		Wallet wallet = new Wallet(user);

		assertThatThrownBy(() -> wallet.credit(new BigDecimal(amount))).isInstanceOf(IllegalArgumentException.class);
		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("0.00"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-0.01", "-100" })
	void shouldRejectDebitOfZeroOrNegativeAmount(String amount) {
		Wallet wallet = new Wallet(user);
		wallet.credit(new BigDecimal("50.00"));

		assertThatThrownBy(() -> wallet.debit(new BigDecimal(amount))).isInstanceOf(IllegalArgumentException.class);
		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("50.00"));
	}

	@Test
	void shouldRejectNullAmount() {
		Wallet wallet = new Wallet(user);

		assertThatThrownBy(() -> wallet.credit(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> wallet.debit(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldRejectDebitGreaterThanAvailableBalance() {
		Wallet wallet = new Wallet(user);
		wallet.credit(new BigDecimal("10.00"));

		assertThatThrownBy(() -> wallet.debit(new BigDecimal("10.01")))
			.isInstanceOf(InsufficientBalanceException.class);
		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("10.00"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.001", "0.005", "1.999" })
	void shouldRejectAmountsThatWouldRequireRounding(String amount) {
		Wallet wallet = new Wallet(user);
		wallet.credit(new BigDecimal("100.00"));

		assertThatThrownBy(() -> wallet.credit(new BigDecimal(amount))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> wallet.debit(new BigDecimal(amount))).isInstanceOf(IllegalArgumentException.class);
		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("100.00"));
	}

	@Test
	void shouldAcceptAmountsWithTrailingZerosBeyondTwoDecimalPlaces() {
		Wallet wallet = new Wallet(user);

		wallet.credit(new BigDecimal("10.500"));

		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("10.50"));
	}

	@Test
	void shouldNeverLeaveBalanceNegative() {
		Wallet wallet = new Wallet(user);

		assertThatThrownBy(() -> wallet.debit(new BigDecimal("0.01")))
			.isInstanceOf(InsufficientBalanceException.class);
		assertThat(wallet.getBalance().signum()).isGreaterThanOrEqualTo(0);

		wallet.credit(new BigDecimal("5.00"));
		wallet.debit(new BigDecimal("3.00"));
		assertThatThrownBy(() -> wallet.debit(new BigDecimal("2.01")))
			.isInstanceOf(InsufficientBalanceException.class);
		wallet.debit(new BigDecimal("2.00"));
		assertThatThrownBy(() -> wallet.debit(new BigDecimal("0.01")))
			.isInstanceOf(InsufficientBalanceException.class);

		assertThat(wallet.getBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(wallet.getBalance().signum()).isGreaterThanOrEqualTo(0);
	}

}
