package com.picpay.digitalwallet.application.transfer;

import java.math.BigDecimal;

import com.picpay.digitalwallet.domain.transfer.Transfer;

/**
 * Executa a parte transacional de uma transferência ja validada e autorizada.
 * Contrato (implementado por {@link TransactionalTransferExecutor}), em uma unica transacao:
 * <ol>
 * <li>travar as duas carteiras com findByIdForUpdate, em ordem crescente de ID;</li>
 * <li>revalidar o saldo da carteira pagadora;</li>
 * <li>com saldo: debitar, creditar e registrar a transferência COMPLETED;</li>
 * <li>sem saldo: registrar REJECTED com motivo INSUFFICIENT_BALANCE, sem movimentar saldos.</li>
 * </ol>
 */
public interface TransferExecutor {

	Transfer execute(Long payerWalletId, Long payeeWalletId, BigDecimal amount);
}
