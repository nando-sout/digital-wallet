package com.picpay.digitalwallet.domain.wallet;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

/**
 * Busca por ID da carteira: findById(Long), herdado de JpaRepository.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

	Optional<Wallet> findByUserId(Long userId);

	boolean existsByUserId(Long userId);

	/**
	 * Busca a carteira com lock pessimista de escrita (SELECT ... FOR UPDATE no PostgreSQL).
	 * O lock dura ate o fim da transacao do chamador; por isso o metodo exige uma transacao ativa
	 * (MANDATORY) e falha se chamado fora dela, em vez de liberar o lock imediatamente.
	 * Para travar duas carteiras, chame uma vez por carteira, sempre em ordem crescente de ID,
	 * evitando deadlocks entre transferencias opostas.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Transactional(propagation = Propagation.MANDATORY)
	@Query("select w from Wallet w where w.id = :id")
	Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
