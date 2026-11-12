package com.bloomscorp.bloomsight.spring;

import com.bloomscorp.bloomsight.Bloomsight;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers one {@link Bloomsight} client when {@code bloomsight.api-key} has a value.
 *
 * <ul>
 *   <li>No key (or an empty one, e.g. {@code ${BLOOMSIGHT_API_KEY:}} in a dev profile):
 *       no client, and the application still starts.</li>
 *   <li>A key that is not a Bloomsight key: startup fails with
 *       {@code INVALID_API_KEY}, so a wrong key is found at deploy, not at the first
 *       order confirmation.</li>
 *   <li>The application's own {@code Bloomsight} bean always wins.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(Bloomsight.class)
@EnableConfigurationProperties(BloomsightProperties.class)
public class BloomsightAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	@Conditional(OnApiKey.class)
	public Bloomsight bloomsight(BloomsightProperties properties) {
		return Bloomsight.builder()
			.apiKey(properties.getApiKey())
			.baseUrl(properties.getBaseUrl())
			.timeout(properties.getTimeout())
			.maxRetries(properties.getMaxRetries())
			.maxRetryWait(properties.getMaxRetryWait())
			.build();
	}

	/** True when bloomsight.api-key is set to something other than blank. */
	static class OnApiKey implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String key = context.getEnvironment().getProperty("bloomsight.api-key");
			return key != null && !key.isBlank();
		}
	}
}
