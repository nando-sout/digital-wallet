package com.picpay.digitalwallet.application.user;

import java.nio.charset.StandardCharsets;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picpay.digitalwallet.domain.user.PasswordHasher;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserAlreadyExistsException;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.WalletService;

@Service
public class UserService {

	// Limite do BCrypt: bytes alem do 72o seriam ignorados; o BCryptPasswordEncoder rejeita a entrada
	static final int MAX_PASSWORD_BYTES = 72;

	static final String DOCUMENT_ALREADY_EXISTS = "A user with this document already exists";
	static final String EMAIL_ALREADY_EXISTS = "A user with this email already exists";

	// Nomes definidos em V1__create_users.sql
	private static final String DOCUMENT_CONSTRAINT = "uk_users_document";
	private static final String EMAIL_CONSTRAINT = "uk_users_email_lower";

	private final UserRepository userRepository;
	private final PasswordHasher passwordHasher;
	private final WalletService walletService;

	public UserService(UserRepository userRepository, PasswordHasher passwordHasher, WalletService walletService) {
		this.userRepository = userRepository;
		this.passwordHasher = passwordHasher;
		this.walletService = walletService;
	}

	/**
	 * Cadastra o usuario e cria sua carteira na mesma transacao: se a carteira falhar,
	 * a excecao sobe e o cadastro do usuario tambem e desfeito.
	 */
	@Transactional
	public User register(String fullName, String document, String email, String rawPassword, UserType userType) {
		validatePasswordLength(rawPassword);

		// O construtor exige o hash; ele tambem normaliza documento e e-mail
		User user = new User(fullName, document, email, passwordHasher.hash(rawPassword), userType);

		if (userRepository.existsByDocument(user.getDocument())) {
			throw new UserAlreadyExistsException(DOCUMENT_ALREADY_EXISTS);
		}
		if (userRepository.existsByEmail(user.getEmail())) {
			throw new UserAlreadyExistsException(EMAIL_ALREADY_EXISTS);
		}

		User persistedUser;
		try {
			// flush imediato: um conflito de unicidade por cadastro simultaneo surge aqui, e nao no commit
			persistedUser = userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			throw translateUniqueViolation(e);
		}

		// Fora do try: falhas da carteira nao sao traduzidas como duplicidade de usuario.
		// Chamada via proxy do bean WalletService (propagacao REQUIRED): participa desta transacao.
		walletService.createFor(persistedUser);

		return persistedUser;
	}

	private static void validatePasswordLength(String rawPassword) {
		if (rawPassword != null && rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new IllegalArgumentException("password must not exceed " + MAX_PASSWORD_BYTES + " bytes (UTF-8)");
		}
	}

	private static RuntimeException translateUniqueViolation(DataIntegrityViolationException e) {
		String constraint = violatedConstraintName(e);
		if (DOCUMENT_CONSTRAINT.equalsIgnoreCase(constraint)) {
			return new UserAlreadyExistsException(DOCUMENT_ALREADY_EXISTS, e);
		}
		if (EMAIL_CONSTRAINT.equalsIgnoreCase(constraint)) {
			return new UserAlreadyExistsException(EMAIL_ALREADY_EXISTS, e);
		}
		return e;
	}

	private static String violatedConstraintName(Throwable e) {
		for (Throwable current = e; current != null; current = current.getCause()) {
			if (current instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}
}
