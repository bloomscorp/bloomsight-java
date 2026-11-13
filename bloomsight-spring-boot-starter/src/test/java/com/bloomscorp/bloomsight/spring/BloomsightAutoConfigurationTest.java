package com.bloomscorp.bloomsight.spring;

import com.bloomscorp.bloomsight.Attachment;
import com.bloomscorp.bloomsight.Bloomsight;
import com.bloomscorp.bloomsight.BloomsightException;
import com.bloomscorp.bloomsight.SendMailRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the auto-configuration in a real Spring context; no network. */
class BloomsightAutoConfigurationTest {

	static final String KEY = "blm_test_" + "c".repeat(64);

	final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(BloomsightAutoConfiguration.class));

	@Test
	void aKeyGivesOneClient() {
		runner.withPropertyValues("bloomsight.api-key=" + KEY).run(ctx -> {
			assertThat(ctx).hasSingleBean(Bloomsight.class);
			assertThat(ctx.getBean(Bloomsight.class).isTestKey()).isTrue();
			assertThat(ctx.getBean(Bloomsight.class).toString()).doesNotContain(KEY).contains("https://z.bloomscorp.com");
		});
	}

	@Test
	void noKeyMeansNoClientAndTheAppStillStarts() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).doesNotHaveBean(Bloomsight.class);
		});
	}

	@Test
	void anEmptyKeyFromAnUnsetEnvironmentVariableAlsoMeansNoClient() {
		runner.withPropertyValues("bloomsight.api-key=").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).doesNotHaveBean(Bloomsight.class);
		});
	}

	@Test
	void aWrongKeyFailsStartupWithInvalidApiKeyAndDoesNotLeakIt() {
		runner.withPropertyValues("bloomsight.api-key=sk_live_not-ours").run(ctx -> {
			assertThat(ctx).hasFailed();
			Throwable root = ctx.getStartupFailure();
			while (root.getCause() != null && !(root instanceof BloomsightException)) root = root.getCause();
			assertThat(root).isInstanceOf(BloomsightException.class);
			assertThat(((BloomsightException) root).getCode()).isEqualTo("INVALID_API_KEY");
			assertThat(root.getMessage()).doesNotContain("not-ours");
		});
	}

	@Test
	void settingsAreBound() {
		runner.withPropertyValues(
			"bloomsight.api-key=" + KEY,
			"bloomsight.base-url=https://example.test",
			"bloomsight.timeout=5s",
			"bloomsight.max-retries=1",
			"bloomsight.max-retry-wait=2m",
			"bloomsight.attachment-timeout=5m"
		).run(ctx -> {
			BloomsightProperties p = ctx.getBean(BloomsightProperties.class);
			assertThat(p.getTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(p.getAttachmentTimeout()).isEqualTo(Duration.ofMinutes(5));
			assertThat(p.getMaxRetries()).isEqualTo(1);
			assertThat(p.getMaxRetryWait()).isEqualTo(Duration.ofMinutes(2));
			assertThat(p.toString()).doesNotContain(KEY);
			assertThat(ctx.getBean(Bloomsight.class).toString()).contains("https://example.test");
		});
	}

	@Test
	void theClientUsesTheBoundSettings() throws Exception {
		// A local server that never answers: the client must give up after the configured
		// timeout, without retries, at the configured address. Nothing leaves the machine.
		AtomicInteger hits = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", ex -> {
			hits.incrementAndGet();
			try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			ex.close();
		});
		server.start();
		try {
			runner.withPropertyValues(
				"bloomsight.api-key=" + KEY,
				"bloomsight.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
				"bloomsight.timeout=300ms",
				"bloomsight.max-retries=0"
			).run(ctx -> {
				long start = System.nanoTime();
				Throwable thrown = null;
				try {
					ctx.getBean(Bloomsight.class).sendMail(SendMailRequest.builder().templateId("t").engineId("e").build());
				} catch (BloomsightException e) {
					thrown = e;
				}
				long ms = (System.nanoTime() - start) / 1_000_000;
				assertThat(thrown).isInstanceOf(BloomsightException.class);
				assertThat(((BloomsightException) thrown).getCode()).isEqualTo("TIMEOUT");
				assertThat(ms).isBetween(250L, 3000L);
				assertThat(hits.get()).isEqualTo(1);
			});
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	@Test
	void theAttachmentTimeoutDefaultsToTwoMinutes() {
		assertThat(new BloomsightProperties().getAttachmentTimeout()).isEqualTo(Duration.ofMinutes(2));
	}

	@Test
	void theClientUsesTheBoundAttachmentTimeoutForSendsWithFiles() throws Exception {
		// A local server that never answers. The plain timeout is long, the attachment
		// timeout short: a send with a file must give up after the attachment timeout, so
		// it fails here if the property is not passed to the client (or the plain timeout
		// is passed in its place). Nothing leaves the machine.
		AtomicInteger hits = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", ex -> {
			hits.incrementAndGet();
			try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			ex.close();
		});
		server.start();
		try {
			runner.withPropertyValues(
				"bloomsight.api-key=" + KEY,
				"bloomsight.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
				"bloomsight.timeout=20s",
				"bloomsight.attachment-timeout=300ms",
				"bloomsight.max-retries=0"
			).run(ctx -> {
				long start = System.nanoTime();
				Throwable thrown = null;
				try {
					ctx.getBean(Bloomsight.class).sendMail(SendMailRequest.builder().templateId("t").engineId("e")
						.attachment(Attachment.of("a.pdf", new byte[]{1})).build());
				} catch (BloomsightException e) {
					thrown = e;
				}
				long ms = (System.nanoTime() - start) / 1_000_000;
				assertThat(thrown).isInstanceOf(BloomsightException.class);
				assertThat(((BloomsightException) thrown).getCode()).isEqualTo("TIMEOUT");
				assertThat(ms).isBetween(250L, 3000L);
				assertThat(hits.get()).isEqualTo(1);
			});
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	@Test
	void theApplicationsOwnClientWins() {
		runner.withPropertyValues("bloomsight.api-key=" + KEY)
			.withUserConfiguration(OwnClient.class)
			.run(ctx -> {
				assertThat(ctx).hasSingleBean(Bloomsight.class);
				assertThat(ctx.getBean(Bloomsight.class)).isSameAs(OwnClient.CLIENT);
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnClient {
		static final Bloomsight CLIENT = Bloomsight.builder().apiKey("blm_live_" + "d".repeat(64)).build();

		@Bean
		Bloomsight myBloomsight() {
			return CLIENT;
		}
	}
}
