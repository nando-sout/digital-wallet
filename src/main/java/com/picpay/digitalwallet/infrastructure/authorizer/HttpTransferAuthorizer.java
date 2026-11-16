package com.picpay.digitalwallet.infrastructure.authorizer;

import java.io.IOException;
import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import com.picpay.digitalwallet.application.transfer.AuthorizationResult;
import com.picpay.digitalwallet.application.transfer.TransferAuthorizer;

/**
 * Cliente do autorizador do desafio (GET, sem corpo). Mapeamento:
 * <ul>
 * <li>200 + authorization=true  -> AUTHORIZED</li>
 * <li>200 + authorization=false -> DENIED</li>
 * <li>403 + authorization=false -> DENIED</li>
 * <li>qualquer outro caso (outro status, 403 + true, timeout, erro de conexao, corpo invalido,
 * campo ausente ou nao booleano) -> TECHNICAL_FAILURE</li>
 * </ul>
 * O mock do desafio ignora os dados da transferencia; eles nao sao enviados nem registrados em log.
 */
public class HttpTransferAuthorizer implements TransferAuthorizer {

	private static final Logger log = LoggerFactory.getLogger(HttpTransferAuthorizer.class);

	private static final int OK = 200;
	private static final int FORBIDDEN = 403;

	private final RestClient restClient;

	public HttpTransferAuthorizer(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public AuthorizationResult authorize(Long payerId, Long payeeId, BigDecimal amount) {
		try {
			return restClient.get()
				.accept(MediaType.APPLICATION_JSON)
				.exchange((request, response) -> interpret(response));
		} catch (RuntimeException e) {
			// Timeout, conexao recusada, corpo ilegivel, content-type inesperado etc.
			log.warn("Authorizer request failed: {}", e.getClass().getSimpleName());
			return AuthorizationResult.TECHNICAL_FAILURE;
		}
	}

	private static AuthorizationResult interpret(ConvertibleClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		if (status != OK && status != FORBIDDEN) {
			return technicalFailure("unexpected HTTP status " + status);
		}

		AuthorizerResponse body = response.bodyTo(AuthorizerResponse.class);
		Object authorization = (body == null || body.data() == null) ? null : body.data().authorization();
		// Exige um booleano JSON real: "true" (texto), 1 ou null nao sao interpretados como decisao
		if (!(authorization instanceof Boolean granted)) {
			return technicalFailure("missing or non-boolean 'authorization' field (HTTP " + status + ")");
		}

		if (status == OK) {
			return granted ? AuthorizationResult.AUTHORIZED : AuthorizationResult.DENIED;
		}
		// 403 contraditorio (authorization=true) nao pode ser interpretado com seguranca
		return granted ? technicalFailure("HTTP 403 with authorization=true") : AuthorizationResult.DENIED;
	}

	private static AuthorizationResult technicalFailure(String reason) {
		log.warn("Authorizer returned an uninterpretable response: {}", reason);
		return AuthorizationResult.TECHNICAL_FAILURE;
	}

	record AuthorizerResponse(String status, Data data) {

		record Data(Object authorization) {
		}
	}
}
