package com.saas.testsupport.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/** Guards REQ-EVT-02: Kafka/RabbitMQ event payloads carry identifiers only, never personal data. */
public final class EventArchRules {

    private static final String EVENT_PAYLOAD = "com.saas.shared.events.EventPayload";
    private static final Pattern PERSONAL_DATA_FIELD =
            Pattern.compile("(?i)^(name|email|phone|telefon|firstname|lastname|fullname|displayname)$");

    private EventArchRules() {
    }

    public static ArchRule payloadsCarryNoPersonalData() {
        return classes()
                .that().implement(EVENT_PAYLOAD)
                .should(new ArchCondition<JavaClass>("declare no field that looks like personal data (name/email/phone)") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaField field : item.getFields()) {
                            if (PERSONAL_DATA_FIELD.matcher(field.getName()).matches()) {
                                events.add(SimpleConditionEvent.violated(field,
                                        field.getFullName() + " looks like personal data (REQ-EVT-02); events carry identifiers only"));
                            }
                        }
                    }
                });
    }
}
