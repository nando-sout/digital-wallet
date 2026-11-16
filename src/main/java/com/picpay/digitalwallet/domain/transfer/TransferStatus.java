package com.picpay.digitalwallet.domain.transfer;

// Os nomes sao persistidos como texto e devem coincidir com o CHECK ck_transfers_status (migration V3)
public enum TransferStatus {
	COMPLETED,
	REJECTED
}
