package com.picpay.digitalwallet.domain.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.picpay.digitalwallet.domain.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallets")
public class Wallet {

	private static final int SCALE = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	// Nunca negativo: garantido por debit() e pelo CHECK ck_wallets_balance_non_negative (migration V2)
	@Column(name = "balance", nullable = false, precision = 19, scale = SCALE)
	private BigDecimal balance;

	protected Wallet() {
		// exigido pelo JPA
	}

	public Wallet(User user) {
		this.user = Objects.requireNonNull(user, "user must not be null");
		this.balance = BigDecimal.ZERO.setScale(SCALE);
	}

	public void credit(BigDecimal amount) {
		BigDecimal validAmount = validateAmount(amount);
		this.balance = this.balance.add(validAmount);
	}

	public void debit(BigDecimal amount) {
		BigDecimal validAmount = validateAmount(amount);
		if (this.balance.compareTo(validAmount) < 0) {
			throw new InsufficientBalanceException(this.balance, validAmount);
		}
		this.balance = this.balance.subtract(validAmount);
	}

	private static BigDecimal validateAmount(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalArgumentException("amount must not be null");
		}
		if (amount.compareTo(BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("amount must be positive: " + amount.toPlainString());
		}
		try {
			// UNNECESSARY lança ArithmeticException se a conversão para 2 casas exigir arredondamento
			return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException(
					"amount must have at most " + SCALE + " decimal places: " + amount.toPlainString(), e);
		}
	}

	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public BigDecimal getBalance() {
		return balance;
	}
}
