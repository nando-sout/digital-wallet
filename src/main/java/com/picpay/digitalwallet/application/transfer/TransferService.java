package com.picpay.digitalwallet.application.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.picpay.digitalwallet.domain.transfer.Transfer;
import com.picpay.digitalwallet.domain.transfer.TransferRejectionReason;
import com.picpay.digitalwallet.domain.transfer.TransferRepository;
import com.picpay.digitalwallet.domain.transfer.TransferStatus;
import com.picpay.digitalwallet.domain.user.User;
import com.picpay.digitalwallet.domain.user.UserNotFoundException;
import com.picpay.digitalwallet.domain.user.UserRepository;
import com.picpay.digitalwallet.domain.user.UserType;
import com.picpay.digitalwallet.domain.wallet.Wallet;
import com.picpay.digitalwallet.domain.wallet.WalletNotFoundException;
import com.picpay.digitalwallet.domain.wallet.WalletRepository;

/**
 * Orquestra uma transferência: validacoes, regras de negocio, autorizador externo e encaminhamento ao
 * {@link TransferExecutor}. Intencionalmente NAO transacional: a chamada HTTP ao autorizador ocorre
 * sem transacao nem locks abertos. Cada leitura e cada registro de recusa usa a transacao curta do
 * proprio repositorio. Nao movimenta saldos: isso e responsabilidade exclusiva do executor
 * ({@link TransactionalTransferExecutor}).
 *
 * Notificacao do recebedor: somente quando o executor retorna uma transferencia COMPLETED. Como este servico
 * nao e transacional, o retorno do executor ja significa transacao confirmada: a notificacao ocorre depois
 * do commit e nenhuma falha dela pode desfazer a transferencia ou transformar o resultado em erro.
 */
@Service
public class TransferService {

	private static final Logger log = LoggerFactory.getLogger(TransferService.class);

	private static final int SCALE = 2;

	private final UserRepository userRepository;
	private final WalletRepository walletRepository;
	private final TransferRepository transferRepository;
	private final TransferAuthorizer transferAuthorizer;
	private final TransferExecutor transferExecutor;
	private final TransferNotifier transferNotifier;

	public TransferService(UserRepository userRepository, WalletRepository walletRepository,
			TransferRepository transferRepository, TransferAuthorizer transferAuthorizer,
			TransferExecutor transferExecutor, TransferNotifier transferNotifier) {
		this.userRepository = userRepository;
		this.walletRepository = walletRepository;
		this.transferRepository = transferRepository;
		this.transferAuthorizer = transferAuthorizer;
		this.transferExecutor = transferExecutor;
		this.transferNotifier = transferNotifier;
	}

	/**
	 * @return a transferência registrada: COMPLETED, ou REJECTED com o motivo da recusa
	 * @throws InvalidTransferException dados de entrada invalidos (IDs nulos, mesmo usuario, valor invalido)
	 * @throws UserNotFoundException pagador ou recebedor inexistente
	 * @throws WalletNotFoundException carteira inexistente para o pagador ou o recebedor
	 * @throws AuthorizerUnavailableException falha tecnica do autorizador (nada e registrado)
	 */
	public Transfer transfer(Long payerId, Long payeeId, BigDecimal value) {
		BigDecimal amount = validateAmount(value);
		validateParticipants(payerId, payeeId);

		User payer = findUser(payerId);
		findUser(payeeId);
		Wallet payerWallet = findWallet(payerId);
		Wallet payeeWallet = findWallet(payeeId);

		if (payer.getUserType() == UserType.MERCHANT) {
			return reject(payerWallet, payeeWallet, amount, TransferRejectionReason.MERCHANT_CANNOT_TRANSFER);
		}
		// Pre-checagem sem lock, apenas para evitar consultar o autorizador a toa.
		// A checagem definitiva ocorre no executor, com as carteiras travadas.
		if (payerWallet.getBalance().compareTo(amount) < 0) {
			return reject(payerWallet, payeeWallet, amount, TransferRejectionReason.INSUFFICIENT_BALANCE);
		}

		// Nenhuma transacao aberta neste ponto: este servico nao e transacional
		AuthorizationResult authorization = transferAuthorizer.authorize(payerId, payeeId, amount);
		return switch (authorization) {
			case AUTHORIZED -> notifyIfCompleted(transferExecutor.execute(payerWallet.getId(), payeeWallet.getId(), amount));
			case DENIED -> reject(payerWallet, payeeWallet, amount, TransferRejectionReason.NOT_AUTHORIZED);
			case TECHNICAL_FAILURE -> throw new AuthorizerUnavailableException();
		};
	}

	/**
	 * Chamado com a transacao do executor ja confirmada. Notifica apenas COMPLETED: o executor tambem pode
	 * devolver REJECTED (saldo insuficiente na revalidacao com lock). Retorna a mesma instancia recebida.
	 * Usa somente o ID da transferencia: nenhuma consulta extra nem acesso a relacionamentos lazy.
	 */
	private Transfer notifyIfCompleted(Transfer transfer) {
		if (transfer.getStatus() == TransferStatus.COMPLETED) {
			try {
				transferNotifier.notifyTransferCompleted(transfer.getId());
			} catch (RuntimeException e) {
				// Defesa adicional: o contrato do notificador ja proibe excecoes. Uma falha aqui nao pode virar
				// HTTP 500 para uma transferencia confirmada (o cliente poderia repeti-la e duplica-la)
				log.warn("Payee notifier failed unexpectedly for transfer {}: {}", transfer.getId(),
						e.getClass().getSimpleName());
			}
		}
		return transfer;
	}

	private static BigDecimal validateAmount(BigDecimal value) {
		if (value == null) {
			throw new InvalidTransferException("value must not be null");
		}
		if (value.compareTo(BigDecimal.ZERO) <= 0) {
			throw new InvalidTransferException("value must be positive: " + value.toPlainString());
		}
		try {
			// Mesma convencao de Wallet e Transfer: no maximo 2 casas, sem arredondamento silencioso
			return value.setScale(SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException e) {
			throw new InvalidTransferException(
					"value must have at most " + SCALE + " decimal places: " + value.toPlainString(), e);
		}
	}

	private static void validateParticipants(Long payerId, Long payeeId) {
		if (payerId == null || payeeId == null) {
			throw new InvalidTransferException("payer and payee must not be null");
		}
		if (payerId.equals(payeeId)) {
			throw new InvalidTransferException("payer and payee must be different users");
		}
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
	}

	private Wallet findWallet(Long userId) {
		return walletRepository.findByUserId(userId).orElseThrow(() -> new WalletNotFoundException(userId));
	}

	private Transfer reject(Wallet payerWallet, Wallet payeeWallet, BigDecimal amount, TransferRejectionReason reason) {
		// TransferRepository.save executa em transacao propria e curta (fora de qualquer chamada HTTP)
		return transferRepository.save(Transfer.rejected(payerWallet, payeeWallet, amount, reason.name()));
	}
}
