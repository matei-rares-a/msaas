package com.saas.notification.email;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Sends one email through the provider, retrying a failed delivery at most {@code maxRetries} times with an
 * exponential backoff (initial delay, then doubled each time) - so with 3 retries the provider is called up
 * to 4 times, waiting 1x, 2x and 4x the initial delay in between (REQ-NOTIF-08). When the retries run out
 * the outcome is "not delivered" (the caller records {@code FAILED}); it is never thrown, because a failed
 * email is a result to record, not a reason to redeliver the command.
 */
public class EmailSender {

    /** @param reason null when delivered; otherwise the provider's code for the last failure */
    public record Result(boolean delivered, int attempts, String reason) {
    }

    /** Pauses between attempts; replaced in tests so backoff does not take real time. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Pattern SAFE_REASON = Pattern.compile("^[A-Z0-9_]{1,50}$");

    private final EmailProvider provider;
    private final Sleeper sleeper;
    private final int maxRetries;
    private final Duration initialBackoff;

    public EmailSender(EmailProvider provider, Sleeper sleeper, int maxRetries, Duration initialBackoff) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.provider = provider;
        this.sleeper = sleeper;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
    }

    public Result send(String to, String subject, String body) {
        Duration delay = initialBackoff;
        int attempts = 0;
        while (true) {
            attempts++;
            String reason;
            try {
                provider.send(to, subject, body);
                return new Result(true, attempts, null);
            } catch (EmailDeliveryException e) {
                reason = SAFE_REASON.matcher(String.valueOf(e.reason())).matches() ? e.reason() : "PROVIDER_ERROR";
            } catch (RuntimeException e) {
                reason = "PROVIDER_ERROR";
            }
            if (attempts > maxRetries) {
                return new Result(false, attempts, reason);
            }
            pause(delay);
            delay = delay.multipliedBy(2);
        }
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry an email", e);
        }
    }
}
