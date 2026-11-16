package com.picpay.digitalwallet.infrastructure.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.picpay.digitalwallet.application.transfer.AuthorizerUnavailableException;
import com.picpay.digitalwallet.application.transfer.InvalidTransferException;
import com.picpay.digitalwallet.domain.user.UserNotFoundException;
import com.picpay.digitalwallet.domain.wallet.WalletNotFoundException;

/** Regras do handler que nao dependem do MVC: quais excecoes viram 400 e o que cada resposta expoe. */
class ApiExceptionHandlerTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void shouldMapInvalidTransferTo400WithValidationMessage() {
		ProblemDetail problem = handler.handleInvalidTransfer(
				new InvalidTransferException("payer and payee must be different users"));

		assertThat(problem.getStatus()).isEqualTo(400);
		assertThat(problem.getTitle()).isEqualTo("Bad Request");
		assertThat(problem.getDetail()).isEqualTo("payer and payee must be different users");
	}

	@Test
	void shouldMapIllegalArgumentTo500WithGenericMessage() {
		ProblemDetail problem = handler.handleUnexpected(new IllegalArgumentException("internal invariant: wallet 40"));

		assertThat(problem.getStatus()).isEqualTo(500);
		assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
		assertThat(problem.getDetail()).isEqualTo(ApiExceptionHandler.UNEXPECTED_ERROR_DETAIL);
	}

	@Test
	void shouldNotDeclareAnyHandlerForIllegalArgumentException() {
		// Garante que IllegalArgumentException so cai no fallback 500 (nenhum handler dedicado a converte em 400)
		for (var method : ApiExceptionHandler.class.getDeclaredMethods()) {
			ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
			if (annotation != null) {
				assertThat(annotation.value()).as(method.getName()).doesNotContain(IllegalArgumentException.class);
			}
		}
	}

	@Test
	void shouldKeepNotFoundAndUnavailableMappings() {
		assertThat(handler.handleNotFound(new UserNotFoundException(15L)).getStatus()).isEqualTo(404);
		assertThat(handler.handleNotFound(new WalletNotFoundException(4L)).getStatus()).isEqualTo(404);

		ProblemDetail unavailable = handler.handleAuthorizerUnavailable(new AuthorizerUnavailableException());
		assertThat(unavailable.getStatus()).isEqualTo(503);
		assertThat(unavailable.getDetail()).isEqualTo("The transfer authorizer is unavailable. Try again later.");
	}
}
