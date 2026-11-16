package com.picpay.digitalwallet.domain.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Os parametros devem chegar normalizados (documento somente com digitos, e-mail em minusculas),
 * pois e assim que a entidade User os armazena.
 */
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByDocument(String document);

	Optional<User> findByEmail(String email);

	boolean existsByDocument(String document);

	boolean existsByEmail(String email);
}
