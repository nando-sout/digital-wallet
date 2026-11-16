package com.picpay.digitalwallet.domain.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransferStatusTest {

	@Test
	void shouldExposeOnlyCompletedAndRejected() {
		assertThat(TransferStatus.values()).containsExactly(TransferStatus.COMPLETED, TransferStatus.REJECTED);
	}

	@Test
	void shouldHaveNamesMatchingDatabaseCheckConstraint() {
		// Persistido como STRING: os nomes precisam coincidir com ck_transfers_status e caber em VARCHAR(20)
		assertThat(TransferStatus.valueOf("COMPLETED")).isEqualTo(TransferStatus.COMPLETED);
		assertThat(TransferStatus.valueOf("REJECTED")).isEqualTo(TransferStatus.REJECTED);
		for (TransferStatus status : TransferStatus.values()) {
			assertThat(status.name()).hasSizeLessThanOrEqualTo(20);
		}
	}

}
