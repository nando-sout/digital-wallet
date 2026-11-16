package com.picpay.digitalwallet.domain.user;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	// Qualquer caractere fora de 0-9 (\D casa apenas digitos ASCII por padrao)
	private static final Pattern NON_DIGIT = Pattern.compile("\\D");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "full_name", nullable = false, length = 255)
	private String fullName;

	@Column(name = "document", nullable = false, length = 14)
	private String document;

	// Unicidade case-insensitive garantida pelo indice unico em lower(email) (migration V1)
	@Column(name = "email", nullable = false, length = 255)
	private String email;

	// Armazena apenas o hash da senha, nunca o texto puro
	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "user_type", nullable = false, length = 20)
	private UserType userType;

	protected User() {
		// exigido pelo JPA
	}

	public User(String fullName, String document, String email, String passwordHash, UserType userType) {
		this.fullName = Objects.requireNonNull(fullName, "fullName must not be null");
		this.document = normalizeDocument(Objects.requireNonNull(document, "document must not be null"));
		this.email = normalizeEmail(Objects.requireNonNull(email, "email must not be null"));
		this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
		this.userType = Objects.requireNonNull(userType, "userType must not be null");
	}

	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static String normalizeDocument(String document) {
		return NON_DIGIT.matcher(document).replaceAll("");
	}

	public Long getId() {
		return id;
	}

	public String getFullName() {
		return fullName;
	}

	public String getDocument() {
		return document;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public UserType getUserType() {
		return userType;
	}
}
