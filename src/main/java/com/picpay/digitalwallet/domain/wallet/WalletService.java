package com.picpay.digitalwallet.domain.wallet;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picpay.digitalwallet.domain.user.User;

@Service
public class WalletService {

	static final String WALLET_ALREADY_EXISTS = "User already has a wallet";

	// Nome definido em V2__create_wallets.sql
	private static final String USER_WALLET_CONSTRAINT = "uk_wallets_user_id";

	private final WalletRepository walletRepository;

	public WalletService(WalletRepository walletRepository) {
		this.walletRepository = walletRepository;
	}

	@Transactional
	public Wallet createFor(User user) {
		if (user == null) {
			throw new IllegalArgumentException("user must not be null");
		}
		if (user.getId() == null) {
			throw new IllegalArgumentException("user must be persisted before creating a wallet");
		}
		if (walletRepository.existsByUserId(user.getId())) {
			throw new WalletAlreadyExistsException(WALLET_ALREADY_EXISTS);
		}

		try {
			// flush imediato: um conflito por criacao simultanea surge aqui, e nao no commit
			return walletRepository.saveAndFlush(new Wallet(user));
		} catch (DataIntegrityViolationException e) {
			if (USER_WALLET_CONSTRAINT.equalsIgnoreCase(violatedConstraintName(e))) {
				throw new WalletAlreadyExistsException(WALLET_ALREADY_EXISTS, e);
			}
			throw e;
		}
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
