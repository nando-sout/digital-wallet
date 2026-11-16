package com.picpay.digitalwallet.domain.wallet;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {

	public InsufficientBalanceException(BigDecimal balance, BigDecimal amount) {
		super("Insufficient balance: available " + balance.toPlainString() + ", requested " + amount.toPlainString());
	}
}
