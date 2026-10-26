package com.fincore.platform;

import com.fincore.platform.audit.FincoreAuditConfiguration;
import com.fincore.platform.kafka.FincoreKafkaConfiguration;
import com.fincore.platform.outbox.FincoreOutboxConfiguration;
import com.fincore.platform.security.FincoreSecurityConfiguration;
import com.fincore.platform.web.FincoreWebConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opt-in to the shared technical infrastructure. Explicit @Import avoids auto-configuration ordering surprises. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import({FincoreSecurityConfiguration.class, FincoreWebConfiguration.class, FincoreKafkaConfiguration.class,
        FincoreOutboxConfiguration.class, FincoreAuditConfiguration.class})
public @interface EnableFincorePlatform {
}
