package com.picpay.digitalwallet.domain.user;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Gera e verifica hashes BCrypt de senhas. Hash e unidirecional: nao ha como recuperar a senha original.
 * A senha em texto original nunca e armazenada nem registrada em logs ou mensagens de erro.
 */
@Component
public class PasswordHasher {

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	public String hash(String rawPassword) {
		if (rawPassword == null || rawPassword.isBlank()) {
			throw new IllegalArgumentException("password must not be blank");
		}
		return encoder.encode(rawPassword);
	}

	public boolean matches(String rawPassword, String passwordHash) {
		if (rawPassword == null || passwordHash == null) {
			return false;
		}
		return encoder.matches(rawPassword, passwordHash);
	}
}
