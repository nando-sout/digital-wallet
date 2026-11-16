package com.picpay.digitalwallet.infrastructure.web.transfer;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.picpay.digitalwallet.application.transfer.TransferService;
import com.picpay.digitalwallet.domain.transfer.Transfer;

import jakarta.validation.Valid;

/**
 * Adaptador HTTP de entrada da transferencia. Nao contem regras de negocio: delega ao TransferService e
 * traduz o resultado em status HTTP. Erros sao tratados pelo ApiExceptionHandler.
 */
@RestController
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	/**
	 * @return 201 com a transferencia concluida, ou 422 com a transferencia recusada (status e motivo)
	 */
	@PostMapping(path = "/transfer", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
		Transfer transfer = transferService.transfer(request.payer(), request.payee(), request.value());
		HttpStatus status = switch (transfer.getStatus()) {
			case COMPLETED -> HttpStatus.CREATED;
			case REJECTED -> HttpStatus.UNPROCESSABLE_CONTENT;
		};
		return ResponseEntity.status(status).body(TransferResponse.of(transfer, request));
	}
}
