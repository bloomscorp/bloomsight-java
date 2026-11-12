package com.bloomscorp.bloomsight.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code bloomsight.*} in {@code application.properties} or {@code application.yml}.
 * Only {@code bloomsight.api-key} is required; without it no client is created.
 */
@ConfigurationProperties(prefix = "bloomsight")
public class BloomsightProperties {

	/**
	 * A project API key with the bloomsight-email scope (blm_live_... or blm_test_...).
	 * Read it from the environment, e.g. ${BLOOMSIGHT_API_KEY}; never commit it.
	 */
	private String apiKey;

	/** The blooms.ai address. */
	private String baseUrl = "https://z.bloomscorp.com";

	/** How long one attempt may take, end to end. */
	private Duration timeout = Duration.ofSeconds(15);

	/** Retries after the first attempt, for network errors, timeouts, 429 and 5xx. */
	private int maxRetries = 3;

	/** The longest Retry-After the client waits out by itself; longer waits are thrown. */
	private Duration maxRetryWait = Duration.ofSeconds(60);

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Duration getMaxRetryWait() {
		return maxRetryWait;
	}

	public void setMaxRetryWait(Duration maxRetryWait) {
		this.maxRetryWait = maxRetryWait;
	}

	@Override
	public String toString() {
		// Never the key.
		return "BloomsightProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "set") + "]";
	}
}
