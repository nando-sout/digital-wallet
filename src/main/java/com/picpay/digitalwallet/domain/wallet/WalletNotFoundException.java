package com.picpay.digitalwallet.domain.wallet;

public class WalletNotFoundException extends RuntimeException {

	public WalletNotFoundException(Long userId) {
		super("Wallet not found for user: " + userId);
	}
}
