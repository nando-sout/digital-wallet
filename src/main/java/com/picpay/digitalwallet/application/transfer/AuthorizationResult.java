package com.picpay.digitalwallet.application.transfer;

/**
 * Resultado da consulta ao autorizador externo.
 * TECHNICAL_FAILURE indica que nao foi possivel obter uma decisao (indisponibilidade, timeout,
 * resposta invalida): nao e uma negativa e nao deve ser tratada como tal.
 */
public enum AuthorizationResult {
	AUTHORIZED,
	DENIED,
	TECHNICAL_FAILURE
}
