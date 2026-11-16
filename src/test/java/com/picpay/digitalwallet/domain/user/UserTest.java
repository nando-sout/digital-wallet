package com.picpay.digitalwallet.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserTest {

	private static final String FULL_NAME = "Ana Souza";
	private static final String DOCUMENT = "12345678909";
	private static final String EMAIL = "ana@mail.com";
	private static final String PASSWORD_HASH = "$2a$10$hashedPasswordValue";

	@Test
	void shouldStoreEmailInLowerCase() {
		User user = new User(FULL_NAME, DOCUMENT, "Ana.Souza@Mail.COM", PASSWORD_HASH, UserType.COMMON);

		assertThat(user.getEmail()).isEqualTo("ana.souza@mail.com");
	}

	@Test
	void shouldStripLeadingAndTrailingWhitespaceFromEmail() {
		User user = new User(FULL_NAME, DOCUMENT, "  ana@mail.com \t", PASSWORD_HASH, UserType.COMMON);

		assertThat(user.getEmail()).isEqualTo("ana@mail.com");
	}

	@Test
	void shouldStoreOnlyDigitsWhenCpfHasPunctuation() {
		User user = new User(FULL_NAME, "123.456.789-09", EMAIL, PASSWORD_HASH, UserType.COMMON);

		assertThat(user.getDocument()).isEqualTo("12345678909");
	}

	@Test
	void shouldStoreOnlyDigitsWhenCnpjHasPunctuation() {
		User user = new User(FULL_NAME, "12.345.678/0001-95", EMAIL, PASSWORD_HASH, UserType.MERCHANT);

		assertThat(user.getDocument()).isEqualTo("12345678000195");
	}

	@Test
	void shouldKeepAlreadyNormalizedDocumentUnchanged() {
		User user = new User(FULL_NAME, "12345678909", EMAIL, PASSWORD_HASH, UserType.COMMON);

		assertThat(user.getDocument()).isEqualTo("12345678909");
	}

	@Test
	void shouldPreserveOtherAttributes() {
		User user = new User(FULL_NAME, " 123.456.789-09 ", " Ana@Mail.com ", PASSWORD_HASH, UserType.MERCHANT);

		assertThat(user.getFullName()).isEqualTo(FULL_NAME);
		assertThat(user.getPasswordHash()).isEqualTo(PASSWORD_HASH);
		assertThat(user.getUserType()).isEqualTo(UserType.MERCHANT);
		assertThat(user.getId()).isNull();
	}

}
