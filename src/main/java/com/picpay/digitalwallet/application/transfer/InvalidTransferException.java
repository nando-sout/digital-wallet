package com.picpay.digitalwallet.application.transfer;

/**
 * Dados de entrada invalidos para uma transferencia (valor ausente, nao positivo ou com mais de 2 casas;
 * pagador ou recebedor ausente; pagador igual ao recebedor). Lancada pelo {@link TransferService} antes de
 * qualquer acesso a dados. A mensagem descreve apenas o problema da entrada e pode ser exibida ao cliente.
 *
 * Intencionalmente NAO estende IllegalArgumentException: uma IllegalArgumentException no fluxo indica erro
 * interno, e nao pode ser confundida com uma entrada invalida.
 */
public class InvalidTransferException extends RuntimeException {

	public InvalidTransferException(String message) {
		super(message);
	}

	public InvalidTransferException(String message, Throwable cause) {
		super(message, cause);
	}
}
