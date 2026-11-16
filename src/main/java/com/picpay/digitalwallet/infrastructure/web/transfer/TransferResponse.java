package com.picpay.digitalwallet.infrastructure.web.transfer;

import java.math.BigDecimal;
import java.time.Instant;

import com.picpay.digitalwallet.domain.transfer.Transfer;

public record TransferResponse(
		Long id,
		BigDecimal value,
		Long payer,
		Long payee,
		String status,
		String rejectionReason,
		Instant createdAt) {

	/**
	 * payer e payee vem da requisicao (IDs de usuario). Os relacionamentos da entidade (carteiras e, por
	 * elas, usuarios) sao lazy e a sessao do Hibernate ja esta fechada quando o servico retorna: nao sao lidos.
	 */
	static TransferResponse of(Transfer transfer, TransferRequest request) {
		return new TransferResponse(
				transfer.getId(),
				transfer.getAmount(),
				request.payer(),
				request.payee(),
				transfer.getStatus().name(),
				transfer.getRejectionReason(),
				transfer.getCreatedAt());
	}
}
