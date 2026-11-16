package com.picpay.digitalwallet.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordHasherTest {

	// $2a$ ou $2b$ ou $2y$, custo de 2 digitos, 22 caracteres de salt + 31 de hash
	private static final String BCRYPT_PATTERN = "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$";

	private static final String RAW_PASSWORD = "S3nh@Segura!";

	private final PasswordHasher passwordHasher = new PasswordHasher();

	@Test
	void shouldGenerateHashDifferentFromRawPassword() {
		String hash = passwordHasher.hash(RAW_PASSWORD);

		assertThat(hash).isNotBlank().doesNotContain(RAW_PASSWORD);
	}

	@Test
	void shouldGenerateBcryptCompatibleHash() {
		String hash = passwordHasher.hash(RAW_PASSWORD);

		assertThat(hash).matches(BCRYPT_PATTERN).hasSize(60);
	}

	@Test
	void shouldMatchOriginalPassword() {
		String hash = passwordHasher.hash(RAW_PASSWORD);

		assertThat(passwordHasher.matches(RAW_PASSWORD, hash)).isTrue();
	}

	@Test
	void shouldNotMatchDifferentPassword() {
		String hash = passwordHasher.hash(RAW_PASSWORD);

		assertThat(passwordHasher.matches("s3nh@segura!", hash)).isFalse();
		assertThat(passwordHasher.matches("outraSenha", hash)).isFalse();
	}

	@Test
	void shouldGenerateDistinctHashesForSamePasswordThatBothMatch() {
		String firstHash = passwordHasher.hash(RAW_PASSWORD);
		String secondHash = passwordHasher.hash(RAW_PASSWORD);

		// Salt aleatorio por chamada: hashes distintos, ambos validos
		assertThat(firstHash).isNotEqualTo(secondHash);
		assertThat(passwordHasher.matches(RAW_PASSWORD, firstHash)).isTrue();
		assertThat(passwordHasher.matches(RAW_PASSWORD, secondHash)).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void shouldRejectNullOrBlankPassword(String rawPassword) {
		assertThatThrownBy(() -> passwordHasher.hash(rawPassword)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldNotMatchWhenHashOrPasswordIsNull() {
		String hash = passwordHasher.hash(RAW_PASSWORD);

		assertThat(passwordHasher.matches(null, hash)).isFalse();
		assertThat(passwordHasher.matches(RAW_PASSWORD, null)).isFalse();
	}

}
