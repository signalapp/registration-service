/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.prescribed.firestore;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeRepository;
import org.signal.registration.util.FirestoreUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Firestore prescribed verification code repository reads prescribed verification codes from a
 * <a href="https://firebase.google.com/docs/firestore">Cloud Firestore</a> collection. Prescribed verification codes
 * are generally managed externally.
 */
@Requires(bean = Firestore.class)
@Singleton
class FirestorePrescribedVerificationCodeRepository implements PrescribedVerificationCodeRepository {

  private final Firestore firestore;
  private final Executor executor;
  private final FirestorePrescribedVerificationCodeRepositoryConfiguration configuration;

  @VisibleForTesting
  static final String VERIFICATION_CODE_KEY = "verification-code";

  private static final Logger logger = LoggerFactory.getLogger(FirestorePrescribedVerificationCodeRepository.class);

  public FirestorePrescribedVerificationCodeRepository(final Firestore firestore,
      @Named(TaskExecutors.IO) final Executor executor,
      final FirestorePrescribedVerificationCodeRepositoryConfiguration configuration) {

    this.firestore = firestore;
    this.executor = executor;
    this.configuration = configuration;
  }

  @Override
  public CompletableFuture<Map<Phonenumber.PhoneNumber, String>> getVerificationCodes() {
    return FirestoreUtil.toCompletableFuture(firestore.collection(configuration.getCollectionName()).get(), executor)
        .thenApply(querySnapshot -> {
          final Map<Phonenumber.PhoneNumber, String> verificationCodes =
              new HashMap<>(querySnapshot.getDocuments().size());

          for (final QueryDocumentSnapshot documentSnapshot : querySnapshot.getDocuments()) {
            try {
              final String e164 = documentSnapshot.getId().startsWith("+") ?
                  documentSnapshot.getId() : "+" + documentSnapshot.getId();

              final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse(e164, null);
              final String verificationCode = documentSnapshot.getString(VERIFICATION_CODE_KEY);

              if (StringUtils.isNotBlank(verificationCode)) {
                verificationCodes.put(phoneNumber, verificationCode);
              } else {
                logger.warn("No verification code found for {}",
                    PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164));
              }
            } catch (final NumberParseException e) {
              logger.warn("Failed to parse document ID as phone number: {}", documentSnapshot.getId());
            }
          }

          return verificationCodes;
        });
  }
}
