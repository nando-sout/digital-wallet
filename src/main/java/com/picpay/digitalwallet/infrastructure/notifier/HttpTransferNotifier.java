package com.picpay.digitalwallet.infrastructure.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.picpay.digitalwallet.application.transfer.TransferNotifier;

/**
 * Cliente do servico de notificacao do desafio: POST sem corpo. O enunciado documenta apenas a URL e o
 * verbo; nenhum payload e enviado e nenhum dado pessoal sai da aplicacao.
 * <ul>
 * <li>qualquer status 2xx -> sucesso;</li>
 * <li>qualquer outro status (inclusive 3xx, nao seguido), timeout, erro de conexao ou outra falha ->
 * log de aviso, sem propagar excecao.</li>
 * </ul>
 * O log contem apenas o ID da transferencia (correlacao) e o status ou o tipo da falha.
 */
public class HttpTransferNotifier implements TransferNotifier {

	private static final Logger log = LoggerFactory.getLogger(HttpTransferNotifier.class);

	private final RestClient restClient;

	public HttpTransferNotifier(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public void notifyTransferCompleted(Long transferId) {
		try {
			int status = restClient.post()
				.exchange((request, response) -> response.getStatusCode().value());
			if (status >= 200 && status < 300) {
				log.debug("Payee notification sent for transfer {} (HTTP {})", transferId, status);
			} else {
				log.warn("Payee notification failed for transfer {}: unexpected HTTP status {}", transferId, status);
			}
		} catch (RuntimeException e) {
			// Timeout, conexao recusada, erro de I/O etc.
			log.warn("Payee notification failed for transfer {}: {}", transferId, e.getClass().getSimpleName());
		}
	}
}
