package com.picpay.digitalwallet.domain.transfer;

// O nome e gravado em transfers.rejection_reason (VARCHAR(255))
public enum TransferRejectionReason {
	MERCHANT_CANNOT_TRANSFER,
	INSUFFICIENT_BALANCE,
	NOT_AUTHORIZED
}
