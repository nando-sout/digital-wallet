package com.picpay.digitalwallet.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.picpay.digitalwallet.TestcontainersConfiguration;

import jakarta.persistence.PersistenceException;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestEntityManager entityManager;

	private User newUser(String document, String email) {
		return new User("Ana Souza", document, email, "$2a$10$hashedPasswordValue", UserType.COMMON);
	}

	@Test
	void shouldFindUserByDocument() {
		User saved = userRepository.saveAndFlush(newUser("123.456.789-09", "ana@mail.com"));
		entityManager.clear();

		assertThat(userRepository.findByDocument("12345678909"))
			.hasValueSatisfying(found -> {
				assertThat(found.getId()).isEqualTo(saved.getId());
				assertThat(found.getDocument()).isEqualTo("12345678909");
			});
	}

	@Test
	void shouldFindUserByEmail() {
		User saved = userRepository.saveAndFlush(newUser("12345678909", "  Ana@Mail.COM "));
		entityManager.clear();

		assertThat(userRepository.findByEmail("ana@mail.com"))
			.hasValueSatisfying(found -> {
				assertThat(found.getId()).isEqualTo(saved.getId());
				assertThat(found.getEmail()).isEqualTo("ana@mail.com");
			});
	}

	@Test
	void shouldReturnEmptyWhenDocumentDoesNotExist() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		assertThat(userRepository.findByDocument("98765432100")).isEmpty();
	}

	@Test
	void shouldReturnEmptyWhenEmailDoesNotExist() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		assertThat(userRepository.findByEmail("other@mail.com")).isEmpty();
	}

	@Test
	void shouldCheckExistenceByDocument() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		assertThat(userRepository.existsByDocument("12345678909")).isTrue();
		assertThat(userRepository.existsByDocument("98765432100")).isFalse();
	}

	@Test
	void shouldCheckExistenceByEmail() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		assertThat(userRepository.existsByEmail("ana@mail.com")).isTrue();
		assertThat(userRepository.existsByEmail("other@mail.com")).isFalse();
	}

	@Test
	void shouldRejectDuplicateDocument() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		// Mesmo documento com pontuacao: normalizado para os mesmos digitos
		assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("123.456.789-09", "bia@mail.com")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause()
			.hasMessageContaining("uk_users_document");
	}

	@Test
	void shouldRejectDuplicateEmailIgnoringCase() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("98765432100", "ANA@Mail.com")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause()
			.hasMessageContaining("uk_users_email_lower");
	}

	@Test
	void shouldRejectDuplicateEmailIgnoringCaseAtDatabaseLevel() {
		userRepository.saveAndFlush(newUser("12345678909", "ana@mail.com"));

		// SQL nativo contorna a normalizacao da entidade: comprova o indice unico em lower(email)
		assertThatThrownBy(() -> entityManager.getEntityManager()
			.createNativeQuery("INSERT INTO users (full_name, document, email, password_hash, user_type) "
					+ "VALUES ('Bia', '98765432100', 'Ana@MAIL.com', 'hash', 'COMMON')")
			.executeUpdate())
			.isInstanceOf(PersistenceException.class)
			.rootCause()
			.hasMessageContaining("uk_users_email_lower");
	}

}
