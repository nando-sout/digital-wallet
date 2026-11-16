package com.picpay.digitalwallet.domain.wallet;

public class WalletAlreadyExistsException extends RuntimeException {

	public WalletAlreadyExistsException(String message) {
		super(message);
	}

	public WalletAlreadyExistsException(String message, Throwable cause) {
		super(message, cause);
	}
}
