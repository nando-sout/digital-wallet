package com.picpay.digitalwallet.infrastructure.web.error;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.picpay.digitalwallet.application.transfer.AuthorizerUnavailableException;
import com.picpay.digitalwallet.application.transfer.InvalidTransferException;
import com.picpay.digitalwallet.domain.user.UserNotFoundException;
import com.picpay.digitalwallet.domain.wallet.WalletNotFoundException;

/**
 * Tratamento global de erros HTTP no formato ProblemDetail (RFC 9457, application/problem+json).
 * As excecoes do Spring MVC (JSON malformado, validacao, metodo/midia nao suportados etc.) sao tratadas
 * pela superclasse; aqui ficam as excecoes da aplicacao e o fallback 500, que nunca expoe detalhes internos.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	static final String UNEXPECTED_ERROR_DETAIL = "An unexpected error occurred.";
	static final String INVALID_FIELDS_DETAIL = "One or more fields are invalid.";

	/** 400 para Bean Validation, com a lista de campos invalidos (sem o valor rejeitado). */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setDetail(INVALID_FIELDS_DETAIL);
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.sorted(Comparator.comparing(FieldError::getField).thenComparing(this::messageOf))
			.map(error -> Map.of("field", error.getField(), "message", messageOf(error)))
			.toList();
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	private String messageOf(FieldError error) {
		return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
	}

	@ExceptionHandler({ UserNotFoundException.class, WalletNotFoundException.class })
	ProblemDetail handleNotFound(RuntimeException ex) {
		// Mensagens de dominio, apenas com o ID informado pelo proprio cliente
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(AuthorizerUnavailableException.class)
	ProblemDetail handleAuthorizerUnavailable(AuthorizerUnavailableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The transfer authorizer is unavailable. Try again later.");
	}

	/**
	 * 400 exclusivamente para entradas invalidas declaradas pelo TransferService. A mensagem e de validacao
	 * (escrita para o cliente) e por isso e exibida.
	 */
	@ExceptionHandler(InvalidTransferException.class)
	ProblemDetail handleInvalidTransfer(InvalidTransferException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	/**
	 * Fallback 500. Inclui qualquer IllegalArgumentException, que nao e convertida em 400: no fluxo da
	 * aplicacao ela indica erro interno (ex.: invariante de entidade violada), nunca entrada do cliente.
	 */
	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		// Detalhes (stack trace, SQL, mensagens internas) ficam apenas no log do servidor
		log.error("Unexpected error while processing request", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_DETAIL);
	}
}
