package com.picpay.digitalwallet.application.transfer;

/**
 * Notifica o recebedor de uma transferencia ja CONCLUIDA e confirmada no banco.
 * Contrato de melhor esforco:
 * <ul>
 * <li>nunca propaga excecoes: falhas externas (status nao 2xx, timeout, erro de conexao) sao apenas
 * registradas em log, sem dados pessoais;</li>
 * <li>nao altera o resultado da transferencia: chamado somente depois do commit, nao ha nada a desfazer;</li>
 * <li>sem retentativas nem fila: uma notificacao que falha e perdida (limitacao conhecida).</li>
 * </ul>
 */
public interface TransferNotifier {

	/**
	 * @param transferId ID da transferencia concluida, usado apenas para correlacao em log
	 */
	void notifyTransferCompleted(Long transferId);
}
