package com.picpay.digitalwallet.application.transfer;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picpay.digitalwallet.domain.transfer.Transfer;
import com.picpay.digitalwallet.domain.transfer.TransferRejectionReason;
import com.picpay.digitalwallet.domain.transfer.TransferRepository;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;

/**
 * Implementacao do {@link TransferExecutor}: travamento, revalidacao de saldo, debito, credito e registro
 * da transferencia ocorrem em uma UNICA transacao. Qualquer RuntimeException (no debito, no credito ou na
 * gravacao) desfaz tudo: nenhum saldo e alterado e nenhuma transferencia e registrada.
 */
@Service
public class TransactionalTransferExecutor implements TransferExecutor {

	private final WalletRepository walletRepository;
	private final TransferRepository transferRepository;

	public TransactionalTransferExecutor(WalletRepository walletRepository, TransferRepository transferRepository) {
		this.walletRepository = walletRepository;
		this.transferRepository = transferRepository;
	}

	@Override
	@Transactional
	public Transfer execute(Long payerWalletId, Long payeeWalletId, BigDecimal amount) {
		if (payerWalletId == null || payeeWalletId == null) {
			throw new IllegalArgumentException("payer and payee wallets must not be null");
		}
		if (payerWalletId.equals(payeeWalletId)) {
			throw new IllegalArgumentException("payer and payee must be different wallets");
		}
		if (amount == null) {
			throw new IllegalArgumentException("amount must not be null");
		}
		// Demais regras do valor (positivo, ate 2 casas) sao aplicadas por Wallet.debit/credit e Transfer

		// Ordem crescente de ID: transferencias opostas simultaneas (A->B e B->A) nao entram em deadlock
		Wallet first = lock(Math.min(payerWalletId, payeeWalletId));
		Wallet second = lock(Math.max(payerWalletId, payeeWalletId));
		Wallet payer = payerWalletId.equals(first.getId()) ? first : second;
		Wallet payee = payer == first ? second : first;

		// Checagem definitiva, com as carteiras travadas: o saldo pode ter mudado desde a pre-checagem do servico
		if (payer.getBalance().compareTo(amount) < 0) {
			return transferRepository.saveAndFlush(Transfer.rejected(payer, payee, amount,
					TransferRejectionReason.INSUFFICIENT_BALANCE.name()));
		}

		payer.debit(amount);
		payee.credit(amount);
		// flush imediato: falhas do banco (ex.: constraints, overflow de saldo) surgem aqui, dentro da transacao
		return transferRepository.saveAndFlush(Transfer.completed(payer, payee, amount));
	}

	private Wallet lock(Long walletId) {
		// A carteira foi lida pelo TransferService instantes antes; ausencia aqui e um estado inconsistente
		return walletRepository.findByIdForUpdate(walletId)
			.orElseThrow(() -> new IllegalStateException("Wallet not found: " + walletId));
	}
}
