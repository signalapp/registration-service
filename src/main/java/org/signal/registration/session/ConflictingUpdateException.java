/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import java.util.UUID;
import java.util.function.Function;

/**
 * Indicates that a call to {@link SessionRepository#updateSession(UUID, Function)} failed because multiple processes
 * attempted to update the session at the same time. Callers should generally retry conflicting updates.
 */
public class ConflictingUpdateException extends Exception {
}
