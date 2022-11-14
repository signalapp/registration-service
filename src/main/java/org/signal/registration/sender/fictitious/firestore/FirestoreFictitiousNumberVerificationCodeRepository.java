/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.fictitious.firestore;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeRepository;
import org.signal.registration.util.FirestoreUtil;

@Requires(bean = Firestore.class)
@Singleton
class FirestoreFictitiousNumberVerificationCodeRepository implements FictitiousNumberVerificationCodeRepository {

  private final Firestore firestore;
  private final Executor executor;
  private final FirestoreFictitiousNumberVerificationCodeRepositoryConfiguration configuration;
  private final Clock clock;

  @VisibleForTesting
  static final String VERIFICATION_CODE_KEY = "verification-code";

  public FirestoreFictitiousNumberVerificationCodeRepository(final Firestore firestore,
      @Named(TaskExecutors.IO) final Executor executor,
      final FirestoreFictitiousNumberVerificationCodeRepositoryConfiguration configuration,
      final Clock clock) {

    this.firestore = firestore;
    this.executor = executor;
    this.configuration = configuration;
    this.clock = clock;
  }

  @Override
  public CompletableFuture<Void> storeVerificationCode(final Phonenumber.PhoneNumber phoneNumber,
      final String verificationCode,
      final Duration ttl) {

    return FirestoreUtil.toCompletableFuture(firestore.collection(configuration.getCollectionName())
            .document(PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164))
            .set(Map.of(VERIFICATION_CODE_KEY, verificationCode,
                configuration.getExpirationFieldName(), FirestoreUtil.timestampFromInstant(clock.instant().plus(ttl)))),
            executor)
        .thenAccept(ignored -> {});
  }
}
