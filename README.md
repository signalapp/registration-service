# Signal registration service

This is a prototype of a multi-provider phone number verification service for use with Signal.

When Signal users first create an account, they do so by associating that account with a phone number. Signal verifies that users actually control that phone number by sending a verification code to that number via SMS or via a phone call. This service manages the process of sending verification codes and checking codes provided by clients.

## Major components

External callers interact with this service by sending [gRPC](https://grpc.io/) requests. The gRPC interface is defined in [`registration_service.proto`](./src/main/proto/registration_service.proto). gRPC requests are handled by [`RegistrationServiceGrpcEndpoint`](./src/main/java/org/signal/registration/rpc/RegistrationServiceGrpcEndpoint.java), which sanitizes client input and dispatches requests to [`RegistrationService`](./src/main/java/org/signal/registration/RegistrationService.java), which orchestrates the major business logic for the entire service.

`RegistrationService` uses a [`SenderSelectionStrategy`](./src/main/java/org/signal/registration/sender/SenderSelectionStrategy.java) to choose a concrete [`VerificationCodeSender`](./src/main/java/org/signal/registration/sender/VerificationCodeSender.java) implementation to send a verification code to a client. `VerificationCodeSenders` are responsible for sending verification codes via a specific transport (i.e. SMS or voice) and service provider and later for verifying codes provided by clients. A [`SessionRepository`](./src/main/java/org/signal/registration/session/SessionRepository.java) stores session data (i.e. verification codes or references to external verification sessions) for `VerificationCodeSenders`.

## Configuration

At a minimum, the registration service needs at least one `VerificationCodeSender`, a `SenderSelectionStrategy`, and a `SessionRepository`. No beans of those types will be instantiated unless they're configured, and so some configuration properties must be provided. The following table describes the currently-supported (and required, in production environments) configuration properties.

| Property                                        | Description                                                                                                                                                                                                                                                              |
|-------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `redis-session-repository.uri`                  | The [`RedisURI`](https://lettuce.io/core/release/api/io/lettuce/core/RedisURI.html) of the Redis server acting as a session repository                                                                                                                                   |
| `twilio.account-sid`                            | The SID of the Twilio account to use to send verification codes via Twilio's [Programmable Messaging](https://www.twilio.com/messaging/programmable-messaging-api), [Programmable Voice](https://www.twilio.com/voice), and [Verify](https://www.twilio.com/verify) APIs |
| `twilio.auth-token`                             | The authentication token for the configured Twilio account                                                                                                                                                                                                               |
| `twilio.messaging.nanpa-messaging-service-sid`  | The SID of the Twilio messaging service to be used to send SMS messages to [NANPA](https://nationalnanpa.com/) phone numbers                                                                                                                                             |
| `twilio.messaging.global-messaging-service-sid` | The SID of the Twilio messaging service to be used to send SMS messages to phone numbers outside of NANPA                                                                                                                                                                |
| `twilio.messaging.android-app-hash`             | The app hash to include in SMS messages for Android devices that support [Automatic SMS Verification](https://developers.google.com/identity/sms-retriever/overview)                                                                                                     |
| `twilio.messaging.supported-languages`          | A list of [BCP 47](https://www.rfc-editor.org/rfc/rfc4646.txt) language tags for which translations of a verification SMS message sent via the Twilio Programmable Messaging API are available                                                                           |
| `twilio.messaging.session-ttl`                  | The maximum lifetime of a registration started by sending a verification code via the Twilio Programmable Messaging API (optional)                                                                                                                                       |
| `twilio.voice.phone-numbers`                    | A list of [E.164](https://www.twilio.com/docs/glossary/what-e164)-formatted phone numbers from which Twilio voice calls can originate                                                                                                                                    |
| `twilio.voice.cdn-uri`                          | The base URI from which voice messages translated to various languages may be retrieved                                                                                                                                                                                  |
| `twilio.voice.supported-languages`              | A list of BCP 47 language tags for which translations of spoken messages delivered via the Twilio Programmable Voice API are available                                                                                                                                   |
| `twilio.voice.session-ttl`                      | The maximum lifetime of a registration started by sending a verification code via the Twilio Programmable Voice API (optional)                                                                                                                                           |
| `twilio.verify.service-sid`                     | The SID of a Twilio Verify service to be used to send verification codes                                                                                                                                                                                                 |
| `twilio.verify.service-friendly-name`           | A "friendly" name for the Twilio Verify service, which may appear in verification messages (optional)                                                                                                                                                                    |
| `twilio.verify.android-app-hash`                | The app hash to include in SMS messages sent by Twilio Verify for Android devices that support [Automatic SMS Verification](https://developers.google.com/identity/sms-retriever/overview)                                                                               |
| `twilio.verify.supported-languages`             | A list of BCP 47 language tags supported by Twilio Verify                                                                                                                                                                                                                |

### Running in development mode

For local testing, this service can be run in the `dev` [Micronaut environment](https://docs.micronaut.io/latest/guide/#environments). In the `dev` environment, the following components are provided (assuming no others of have been configured):

- A trivial verification code sender that always uses the last six digits of a phone number as a verification code
- A trivial sender selection strategy that always chooses the last-six-digits "sender"
- An in-memory session store

These components are, obviously, not suitable for production use and are intended only to facilitate local development and testing.
