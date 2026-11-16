package com.picpay.digitalwallet.application.transfer;

/**
 * Falha tecnica na consulta ao autorizador externo: nenhuma decisao foi obtida.
 * Nao representa uma recusa e nao gera registro de transferencia.
 */
public class AuthorizerUnavailableException extends RuntimeException {

	public AuthorizerUnavailableException() {
		super("Transfer authorizer is unavailable");
	}
}
