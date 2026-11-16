package com.picpay.digitalwallet.infrastructure.web.transfer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.picpay.digitalwallet.application.transfer.AuthorizerUnavailableException;
import com.picpay.digitalwallet.application.transfer.InvalidTransferException;
import com.picpay.digitalwallet.application.transfer.TransferService;
import com.picpay.digitalwallet.domain.transfer.Transfer;
import com.picpay.digitalwallet.domain.transfer.TransferRejectionReason;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserNotFoundException;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletNotFoundException;

/**
 * Camada HTTP isolada: apenas o controller, o ApiExceptionHandler e o MVC. O TransferService e simulado;
 * nao ha banco, Docker nem Testcontainers.
 */
@WebMvcTest(TransferController.class)
class TransferControllerTest {

	private static final Long PAYER = 4L;
	private static final Long PAYEE = 15L;
	private static final BigDecimal AMOUNT = new BigDecimal("100.00");
	private static final Instant CREATED_AT = Instant.parse("2026-11-13T12:00:00Z");
	private static final String VALID_BODY = "{\"value\": 100.0, \"payer\": 4, \"payee\": 15}";
	private static final String PROBLEM_JSON = "application/problem+json";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TransferService transferService;

	// --- Cenario ---

	private static Wallet wallet(Long walletId, Long userId, UserType type) {
		User user = new User("Nome " + userId, "1234567890" + userId, "u" + userId + "@mail.com", "$2a$10$hash", type);
		ReflectionTestUtils.setField(user, "id", userId);
		Wallet wallet = new Wallet(user);
		ReflectionTestUtils.setField(wallet, "id", walletId);
		return wallet;
	}

	private static Transfer persisted(Transfer transfer) {
		ReflectionTestUtils.setField(transfer, "id", 99L);
		ReflectionTestUtils.setField(transfer, "createdAt", CREATED_AT);
		return transfer;
	}

	private static Transfer completed() {
		return persisted(Transfer.completed(wallet(40L, PAYER, UserType.COMMON), wallet(150L, PAYEE, UserType.MERCHANT),
				AMOUNT));
	}

	private static Transfer rejected(TransferRejectionReason reason) {
		return persisted(Transfer.rejected(wallet(40L, PAYER, UserType.COMMON), wallet(150L, PAYEE, UserType.MERCHANT),
				AMOUNT, reason.name()));
	}

	private ResultActions postTransfer(String body) throws Exception {
		return mockMvc.perform(post("/transfer").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static void assertProblem(ResultActions result, int status, String detail) throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.title").isNotEmpty())
			.andExpect(jsonPath("$.detail").value(detail));
	}

	// --- Sucesso: 201 ---

	@Test
	void shouldReturn201WithCompletedTransfer() throws Exception {
		when(transferService.transfer(PAYER, PAYEE, new BigDecimal("100.0"))).thenReturn(completed());

		postTransfer(VALID_BODY)
			.andExpect(status().isCreated())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(99))
			.andExpect(jsonPath("$.value").value(100.00))
			.andExpect(jsonPath("$.payer").value(4))
			.andExpect(jsonPath("$.payee").value(15))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.rejectionReason").isEmpty())
			.andExpect(jsonPath("$.createdAt").value("2026-11-13T12:00:00Z"));
	}

	@Test
	void shouldDelegateUserIdsAndValueToServiceUnchanged() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenReturn(completed());

		postTransfer("{\"value\": 10.5, \"payer\": 4, \"payee\": 15}").andExpect(status().isCreated());

		verify(transferService).transfer(PAYER, PAYEE, new BigDecimal("10.5"));
	}

	@Test
	void shouldSerializeValueWithTwoDecimalPlaces() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenReturn(completed());

		postTransfer("{\"value\": 100, \"payer\": 4, \"payee\": 15}")
			.andExpect(status().isCreated())
			.andExpect(content().string(containsString("\"value\":100.00")));
	}

	// --- Recusas de negocio: 422 com o corpo da transferencia ---

	@Test
	void shouldReturn422WhenBalanceIsInsufficient() throws Exception {
		when(transferService.transfer(any(), any(), any()))
			.thenReturn(rejected(TransferRejectionReason.INSUFFICIENT_BALANCE));

		assertRejected(postTransfer(VALID_BODY), "INSUFFICIENT_BALANCE");
	}

	@Test
	void shouldReturn422WhenPayerIsMerchant() throws Exception {
		when(transferService.transfer(any(), any(), any()))
			.thenReturn(rejected(TransferRejectionReason.MERCHANT_CANNOT_TRANSFER));

		assertRejected(postTransfer(VALID_BODY), "MERCHANT_CANNOT_TRANSFER");
	}

	@Test
	void shouldReturn422WhenAuthorizerDenies() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenReturn(rejected(TransferRejectionReason.NOT_AUTHORIZED));

		assertRejected(postTransfer(VALID_BODY), "NOT_AUTHORIZED");
	}

	private static void assertRejected(ResultActions result, String reason) throws Exception {
		result.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(99))
			.andExpect(jsonPath("$.value").value(100.00))
			.andExpect(jsonPath("$.payer").value(4))
			.andExpect(jsonPath("$.payee").value(15))
			.andExpect(jsonPath("$.status").value("REJECTED"))
			.andExpect(jsonPath("$.rejectionReason").value(reason))
			.andExpect(jsonPath("$.createdAt").value("2026-11-13T12:00:00Z"));
	}

	// --- Entrada invalida: 400 ---

	@ParameterizedTest
	@ValueSource(strings = { "{\"value\": 100.0, \"payer\": 4,", "not json", "{\"value\": \"abc\", \"payer\": 4, \"payee\": 15}",
			"{\"value\": 100.0, \"payer\": \"x\", \"payee\": 15}" })
	void shouldReturn400ForMalformedJson(String body) throws Exception {
		assertProblem(postTransfer(body), 400, "Failed to read request");
		verifyNoInteractions(transferService);
	}

	@Test
	void shouldReturn400ForEmptyBody() throws Exception {
		assertProblem(postTransfer(""), 400, "Failed to read request");
		verifyNoInteractions(transferService);
	}

	@Test
	void shouldReturn400ListingAllMissingFields() throws Exception {
		ResultActions result = postTransfer("{}");

		assertProblem(result, 400, "One or more fields are invalid.");
		result.andExpect(jsonPath("$.errors.length()").value(3))
			.andExpect(jsonPath("$.errors[0].field").value("payee"))
			.andExpect(jsonPath("$.errors[0].message").value("must not be null"))
			.andExpect(jsonPath("$.errors[1].field").value("payer"))
			.andExpect(jsonPath("$.errors[2].field").value("value"))
			.andExpect(jsonPath("$.errors[2].message").value("must not be null"));
		verifyNoInteractions(transferService);
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.00", "-1", "-0.01" })
	void shouldReturn400ForZeroOrNegativeValue(String value) throws Exception {
		ResultActions result = postTransfer("{\"value\": " + value + ", \"payer\": 4, \"payee\": 15}");

		assertProblem(result, 400, "One or more fields are invalid.");
		result.andExpect(jsonPath("$.errors.length()").value(1))
			.andExpect(jsonPath("$.errors[0].field").value("value"))
			.andExpect(jsonPath("$.errors[0].message").value("must be greater than 0"));
		verifyNoInteractions(transferService);
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.001", "0.009", "100000000000000000.00" })
	void shouldReturn400ForTooManyDigits(String value) throws Exception {
		ResultActions result = postTransfer("{\"value\": " + value + ", \"payer\": 4, \"payee\": 15}");

		assertProblem(result, 400, "One or more fields are invalid.");
		result.andExpect(jsonPath("$.errors[0].field").value("value"))
			.andExpect(jsonPath("$.errors[0].message")
				.value("must have at most 17 integer digits and 2 decimal places"));
		verifyNoInteractions(transferService);
	}

	@Test
	void shouldNotEchoRejectedValueInValidationErrors() throws Exception {
		postTransfer("{\"value\": -987654.32, \"payer\": 4, \"payee\": 15}")
			.andExpect(status().isBadRequest())
			.andExpect(content().string(not(containsString("987654"))));
	}

	@Test
	void shouldReturn400WhenServiceRejectsInput() throws Exception {
		when(transferService.transfer(any(), any(), any()))
			.thenThrow(new InvalidTransferException("payer and payee must be different users"));

		assertProblem(postTransfer("{\"value\": 100.0, \"payer\": 4, \"payee\": 4}"), 400,
				"payer and payee must be different users");
	}

	@Test
	void shouldReturn500ForUnexpectedIllegalArgumentExceptionInTransferFlow() throws Exception {
		// Ex.: invariante de entidade violada em camada inferior: e erro interno, nao entrada invalida
		when(transferService.transfer(any(), any(), any()))
			.thenThrow(new IllegalArgumentException("payer and payee must be different wallets [internal id 40]"));

		ResultActions result = postTransfer(VALID_BODY);

		assertProblem(result, 500, "An unexpected error occurred.");
		result.andExpect(content().string(not(containsString("wallets"))))
			.andExpect(content().string(not(containsString("internal id"))))
			.andExpect(content().string(not(containsString("IllegalArgumentException"))));
	}

	@Test
	void shouldReturn500ForUnexpectedIllegalArgumentSubclass() throws Exception {
		when(transferService.transfer(any(), any(), any()))
			.thenThrow(new NumberFormatException("For input string: \"secret-token\""));

		ResultActions result = postTransfer(VALID_BODY);

		assertProblem(result, 500, "An unexpected error occurred.");
		result.andExpect(content().string(not(containsString("secret-token"))));
	}

	@Test
	void shouldReturn415ForNonJsonContentType() throws Exception {
		mockMvc.perform(post("/transfer").contentType(MediaType.TEXT_PLAIN).content(VALID_BODY))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
		verifyNoInteractions(transferService);
	}

	// --- Recursos inexistentes: 404 ---

	@Test
	void shouldReturn404WhenUserDoesNotExist() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenThrow(new UserNotFoundException(15L));

		assertProblem(postTransfer(VALID_BODY), 404, "User not found: 15");
	}

	@Test
	void shouldReturn404WhenWalletDoesNotExist() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenThrow(new WalletNotFoundException(4L));

		assertProblem(postTransfer(VALID_BODY), 404, "Wallet not found for user: 4");
	}

	// --- Autorizador indisponivel: 503 ---

	@Test
	void shouldReturn503WhenAuthorizerIsUnavailable() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenThrow(new AuthorizerUnavailableException());

		assertProblem(postTransfer(VALID_BODY), 503, "The transfer authorizer is unavailable. Try again later.");
	}

	// --- Erro inesperado: 500 sem detalhes internos ---

	@Test
	void shouldReturn500WithoutInternalDetails() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenThrow(new IllegalStateException(
				"could not execute statement [SELECT * FROM wallets WHERE password_hash = 'secret']"));

		ResultActions result = postTransfer(VALID_BODY);

		assertProblem(result, 500, "An unexpected error occurred.");
		result.andExpect(content().string(not(containsString("SELECT"))))
			.andExpect(content().string(not(containsString("secret"))))
			.andExpect(content().string(not(containsString("IllegalStateException"))))
			.andExpect(content().string(not(containsString("at com.picpay"))));
	}

	@Test
	void shouldReturn500ForUncheckedErrorFromDeeperLayers() throws Exception {
		when(transferService.transfer(any(), any(), any())).thenThrow(new RuntimeException("connection refused"));

		ResultActions result = postTransfer(VALID_BODY);

		assertProblem(result, 500, "An unexpected error occurred.");
		result.andExpect(content().string(not(containsString("connection refused"))));
	}
}
