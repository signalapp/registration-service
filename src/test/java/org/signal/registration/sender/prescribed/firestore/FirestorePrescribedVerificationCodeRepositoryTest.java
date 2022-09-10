/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.prescribed.firestore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.cloud.NoCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FirestorePrescribedVerificationCodeRepositoryTest {

  private Firestore firestore;
  private FirestorePrescribedVerificationCodeRepository repository;


  private static final String FIRESTORE_EMULATOR_IMAGE_NAME = "gcr.io/google.com/cloudsdktool/cloud-sdk:" +
      System.getProperty("firestore.emulator.version", "emulators");

  @Container
  private static final FirestoreEmulatorContainer CONTAINER = new FirestoreEmulatorContainer(
      DockerImageName.parse(FIRESTORE_EMULATOR_IMAGE_NAME));

  private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

  private static final String COLLECTION_NAME = "prescribed-verification-codes";

  @BeforeEach
  void setUp() {
    final FirestoreConfiguration configuration = new FirestoreConfiguration();
    configuration.setCollectionName(COLLECTION_NAME);

    firestore = FirestoreOptions.getDefaultInstance().toBuilder()
        .setHost(CONTAINER.getEmulatorEndpoint())
        .setCredentials(NoCredentials.getInstance())
        .setProjectId("firestore-prescribed-verification-codes-test")
        .build()
        .getService();

    repository =
        new FirestorePrescribedVerificationCodeRepository(firestore, MoreExecutors.directExecutor(), configuration);
  }

  @Test
  void getVerificationCodes() throws ExecutionException, InterruptedException {
    final Map<Phonenumber.PhoneNumber, String> expectedVerificationCodes = Map.of(
        PHONE_NUMBER_UTIL.getExampleNumber("US"), "123456",
        PHONE_NUMBER_UTIL.getExampleNumber("MX"), "987654");

    for (final Map.Entry<Phonenumber.PhoneNumber, String> entry : expectedVerificationCodes.entrySet()) {
      firestore.collection(COLLECTION_NAME)
          .document(PHONE_NUMBER_UTIL.format(entry.getKey(), PhoneNumberUtil.PhoneNumberFormat.E164))
          .set(Map.of(FirestorePrescribedVerificationCodeRepository.VERIFICATION_CODE_KEY, entry.getValue()))
          .get();
    }

    firestore.collection(COLLECTION_NAME)
        .document("not-a-phone-number")
        .set(Map.of(FirestorePrescribedVerificationCodeRepository.VERIFICATION_CODE_KEY, "987654"))
        .get();

    firestore.collection(COLLECTION_NAME)
        .document(PHONE_NUMBER_UTIL.format(PHONE_NUMBER_UTIL.getExampleNumber("CA"), PhoneNumberUtil.PhoneNumberFormat.E164))
        .set(Map.of(FirestorePrescribedVerificationCodeRepository.VERIFICATION_CODE_KEY + "-incorrect", "987654"))
        .get();

    assertEquals(expectedVerificationCodes, repository.getVerificationCodes().join());
  }
}
