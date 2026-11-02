package com.saas.notification.email;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The retry policy of REQ-NOTIF-08: at most 3 retries, with an exponential backoff, then "not delivered". */
@Tag("REQ-NOTIF-08")
class EmailSenderTest {

    private final List<Duration> pauses = new ArrayList<>();

    private EmailSender sender(EmailProvider provider, int maxRetries) {
        return new EmailSender(provider, pauses::add, maxRetries, Duration.ofSeconds(1));
    }

    private static EmailProvider failing(AtomicInteger calls, int failures) {
        return (to, subject, body) -> {
            if (calls.incrementAndGet() <= failures) {
                throw new EmailDeliveryException("SMTP_DOWN");
            }
        };
    }

    @Test
    void aDeliveryThatWorksIsMadeOnceWithNoWaiting() {
        AtomicInteger calls = new AtomicInteger();

        EmailSender.Result result = sender(failing(calls, 0), 3).send("a@example.com", "s", "b");

        assertThat(result).isEqualTo(new EmailSender.Result(true, 1, null));
        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void aProviderThatKeepsFailingIsCalledOnceAndRetriedThreeTimesWithDoublingWaits() {
        AtomicInteger calls = new AtomicInteger();

        EmailSender.Result result = sender(failing(calls, Integer.MAX_VALUE), 3).send("a@example.com", "s", "b");

        assertThat(result).isEqualTo(new EmailSender.Result(false, 4, "SMTP_DOWN"));
        assertThat(calls).hasValue(4);
        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void aProviderThatRecoversOnTheLastRetryStillDelivers() {
        AtomicInteger calls = new AtomicInteger();

        EmailSender.Result result = sender(failing(calls, 3), 3).send("a@example.com", "s", "b");

        assertThat(result).isEqualTo(new EmailSender.Result(true, 4, null));
        assertThat(pauses).hasSize(3);
    }

    @Test
    void withNoRetriesAllowedAFailureIsFinalAtOnce() {
        AtomicInteger calls = new AtomicInteger();

        EmailSender.Result result = sender(failing(calls, Integer.MAX_VALUE), 0).send("a@example.com", "s", "b");

        assertThat(result.delivered()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void anUnexpectedProviderErrorCountsAsAFailedAttemptAndItsMessageIsNotKept() {
        EmailProvider broken = (to, subject, body) -> {
            throw new IllegalStateException("connection to smtp.example.com refused for " + to);
        };

        EmailSender.Result result = sender(broken, 3).send("someone@example.com", "s", "b");

        assertThat(result.delivered()).isFalse();
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(result.reason()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void aReasonThatIsNotAShortCodeIsReplacedSoNothingFreeFormIsStored() {
        EmailProvider chatty = (to, subject, body) -> {
            throw new EmailDeliveryException("550 mailbox someone@example.com not found");
        };

        EmailSender.Result result = sender(chatty, 0).send("someone@example.com", "s", "b");

        assertThat(result.reason()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void beingInterruptedWhileWaitingStopsTheRetriesAndKeepsTheInterruptFlag() {
        EmailSender interrupted = new EmailSender(failing(new AtomicInteger(), Integer.MAX_VALUE), duration -> {
            throw new InterruptedException();
        }, 3, Duration.ofSeconds(1));

        try {
            assertThatThrownBy(() -> interrupted.send("a@example.com", "s", "b")).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aNegativeRetryCountIsRefused() {
        assertThatThrownBy(() -> new EmailSender((to, subject, body) -> { }, pauses::add, -1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
