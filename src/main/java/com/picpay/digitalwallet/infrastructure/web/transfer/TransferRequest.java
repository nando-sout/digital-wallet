package com.picpay.digitalwallet.infrastructure.web.transfer;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Corpo de POST /transfer, no formato do desafio: {"value": 100.0, "payer": 4, "payee": 15}.
 * payer e payee sao IDs de USUARIO. Aqui so se valida o formato da entrada; regras de negocio
 * (pagador diferente do recebedor, lojista, saldo, autorizacao) permanecem no TransferService.
 * Mensagens fixas: a resposta nao depende do locale do servidor.
 */
public record TransferRequest(
		@NotNull(message = "must not be null")
		@Positive(message = "must be greater than 0")
		@Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 decimal places")
		BigDecimal value,

		@NotNull(message = "must not be null")
		Long payer,

		@NotNull(message = "must not be null")
		Long payee) {
}
