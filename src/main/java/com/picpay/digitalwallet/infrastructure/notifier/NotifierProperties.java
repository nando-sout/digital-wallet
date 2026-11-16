package com.picpay.digitalwallet.infrastructure.notifier;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.notifier")
public record NotifierProperties(@NotNull URI url, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
}
