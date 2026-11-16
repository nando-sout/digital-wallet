package com.picpay.digitalwallet.application.transfer;

import java.math.BigDecimal;

/**
 * Consulta um servico externo para autorizar uma transferencia antes de sua execucao.
 * Implementacoes nao lancam excecoes por falhas de comunicacao: retornam
 * {@link AuthorizationResult#TECHNICAL_FAILURE}. Nunca retornam AUTHORIZED sem uma confirmacao explicita.
 */
public interface TransferAuthorizer {

	AuthorizationResult authorize(Long payerId, Long payeeId, BigDecimal amount);
}
