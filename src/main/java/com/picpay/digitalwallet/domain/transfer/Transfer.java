package com.picpay.digitalwallet.domain.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.picpay.digitalwallet.domain.wallet.Wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Registro imutavel de uma tentativa de transferencia entre carteiras.
 * Criado apenas pelas fabricas {@link #completed} e {@link #rejected}; nenhuma coluna e atualizavel.
 * Esta classe nao movimenta saldos.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

	private static final int SCALE = 2;
	static final int MAX_REJECTION_REASON_LENGTH = 255;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = SCALE)
	private BigDecimal amount;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payer_wallet_id", nullable = false, updatable = false)
	private Wallet payer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payee_wallet_id", nullable = false, updatable = false)
	private Wallet payee;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false, length = 20)
	private TransferStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "rejection_reason", updatable = false, length = MAX_REJECTION_REASON_LENGTH)
	private String rejectionReason;

	protected Transfer() {
		// exigido pelo JPA
	}

	private Transfer(Wallet payer, Wallet payee, BigDecimal amount, TransferStatus status, String rejectionReason) {
		this.payer = Objects.requireNonNull(payer, "payer must not be null");
		this.payee = Objects.requireNonNull(payee, "payee must not be null");
		if (isSameWallet(payer, payee)) {
			throw new IllegalArgumentException("payer and payee must be different wallets");
		}
		this.amount = validateAmount(amount);
		this.status = status;
		this.rejectionReason = rejectionReason;
		// TIMESTAMPTZ guarda microssegundos: truncar evita diferenca entre o valor em memoria e o lido do banco
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public static Transfer completed(Wallet payer, Wallet payee, BigDecimal amount) {
		return new Transfer(payer, payee, amount, TransferStatus.COMPLETED, null);
	}

	/**
	 * @param rejectionReason opcional; nulo ou em branco e armazenado como nulo
	 */
	public static Transfer rejected(Wallet payer, Wallet payee, BigDecimal amount, String rejectionReason) {
		return new Transfer(payer, payee, amount, TransferStatus.REJECTED, normalizeRejectionReason(rejectionReason));
	}

	private static boolean isSameWallet(Wallet payer, Wallet payee) {
		if (payer == payee) {
			return true;
		}
		return payer.getId() != null && payer.getId().equals(payee.getId());
	}

	private static BigDecimal validateAmount(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalArgumentException("amount must not be null");
		}
		if (amount.compareTo(BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("amount must be positive: " + amount.toPlainString());
		}
		try {
			// UNNECESSARY lança ArithmeticException se a conversão para 2 casas exigir arredondamento
			return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException(
					"amount must have at most " + SCALE + " decimal places: " + amount.toPlainString(), e);
		}
	}

	private static String normalizeRejectionReason(String rejectionReason) {
		if (rejectionReason == null || rejectionReason.isBlank()) {
			return null;
		}
		String reason = rejectionReason.strip();
		if (reason.length() > MAX_REJECTION_REASON_LENGTH) {
			throw new IllegalArgumentException(
					"rejectionReason must not exceed " + MAX_REJECTION_REASON_LENGTH + " characters");
		}
		return reason;
	}

	public Long getId() {
		return id;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Wallet getPayer() {
		return payer;
	}

	public Wallet getPayee() {
		return payee;
	}

	public TransferStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getRejectionReason() {
		return rejectionReason;
	}
}
