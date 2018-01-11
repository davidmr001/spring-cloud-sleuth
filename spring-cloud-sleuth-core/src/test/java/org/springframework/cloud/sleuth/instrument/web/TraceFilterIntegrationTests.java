package org.springframework.cloud.sleuth.instrument.web;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import brave.Span;
import brave.Tracing;
import brave.sampler.Sampler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.sleuth.TraceKeys;
import org.springframework.cloud.sleuth.instrument.DefaultTestAutoConfiguration;
import org.springframework.cloud.sleuth.util.ArrayListSpanReporter;
import org.springframework.cloud.sleuth.util.SpanUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.filter.GenericFilterBean;

import static org.assertj.core.api.BDDAssertions.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TraceFilterIntegrationTests.Config.class)
public class TraceFilterIntegrationTests extends AbstractMvcIntegrationTest {
	static final String TRACE_ID_NAME = "X-B3-TraceId";
	static final String SPAN_ID_NAME = "X-B3-SpanId";
	static final String SAMPLED_NAME = "X-B3-Sampled";

	private static Log logger = LogFactory.getLog(
			TraceFilterIntegrationTests.class);

	@Autowired TraceFilter traceFilter;
	@Autowired MyFilter myFilter;
	@Autowired ArrayListSpanReporter reporter;

	private static Span span;

	@Before
	@After
	public void clearSpans() {
		this.reporter.clear();
	}

	@Test
	public void should_create_a_trace() throws Exception {
		whenSentPingWithoutTracingData();

		then(this.reporter.getSpans()).hasSize(1);
		zipkin2.Span span = this.reporter.getSpans().get(0);
		then(span.tags())
				.containsKey(new TraceKeys().getMvc().getControllerClass())
				.containsKey(new TraceKeys().getMvc().getControllerMethod());
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void should_ignore_sampling_the_span_if_uri_matches_management_properties_context_path()
			throws Exception {
		MvcResult mvcResult = whenSentInfoWithTraceId(new Random().nextLong());

		// https://github.com/spring-cloud/spring-cloud-sleuth/issues/327
		// we don't want to respond with any tracing data
		then(notSampledHeaderIsPresent(mvcResult)).isEqualTo(false);
		then(this.reporter.getSpans()).isEmpty();
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void when_traceId_is_sent_should_not_create_a_new_one_but_return_the_existing_one_instead()
			throws Exception {
		Long expectedTraceId = new Random().nextLong();

		whenSentPingWithTraceId(expectedTraceId);

		then(this.reporter.getSpans()).hasSize(1);
		then(this.tracing.tracer().currentSpan()).isNull();

	}

	@Test
	public void when_message_is_sent_should_eventually_clear_mdc() throws Exception {
		Long expectedTraceId = new Random().nextLong();

		whenSentPingWithTraceId(expectedTraceId);

		then(MDC.getCopyOfContextMap()).isEmpty();
		then(this.reporter.getSpans()).hasSize(1);
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void when_traceId_is_sent_to_async_endpoint_span_is_joined() throws Exception {
		Long expectedTraceId = new Random().nextLong();

		MvcResult mvcResult = whenSentFutureWithTraceId(expectedTraceId);
		this.mockMvc.perform(asyncDispatch(mvcResult))
				.andExpect(status().isOk()).andReturn();

		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void should_add_a_custom_tag_to_the_span_created_in_controller() throws Exception {
		Long expectedTraceId = new Random().nextLong();

		MvcResult mvcResult = whenSentDeferredWithTraceId(expectedTraceId);
		this.mockMvc.perform(asyncDispatch(mvcResult))
				.andExpect(status().isOk()).andReturn();

		Optional<zipkin2.Span> taggedSpan = this.reporter.getSpans().stream()
				.filter(span -> span.tags().containsKey("tag")).findFirst();
		then(taggedSpan.isPresent()).isTrue();
		then(taggedSpan.get().tags())
				.containsEntry("tag", "value")
				.containsEntry("mvc.controller.method", "deferredMethod")
				.containsEntry("mvc.controller.class", "TestController");
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void should_log_tracing_information_when_exception_was_thrown() throws Exception {
		Long expectedTraceId = new Random().nextLong();

		whenSentToNonExistentEndpointWithTraceId(expectedTraceId);

		then(this.reporter.getSpans()).hasSize(1);
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void should_assume_that_a_request_without_span_and_with_trace_is_a_root_span() throws Exception {
		Long expectedTraceId = new Random().nextLong();

		whenSentRequestWithTraceIdAndNoSpanId(expectedTraceId);
		whenSentRequestWithTraceIdAndNoSpanId(expectedTraceId);

		then(this.reporter.getSpans().stream().filter(span ->
				span.id().equals(span.traceId()))
				.findAny().isPresent()).as("a root span exists").isTrue();
		then(this.tracing.tracer().currentSpan()).isNull();
	}

	@Test
	public void should_return_custom_response_headers_when_custom_trace_filter_gets_registered() throws Exception {
		Long expectedTraceId = new Random().nextLong();

		MvcResult mvcResult = whenSentPingWithTraceId(expectedTraceId);

		then(mvcResult.getResponse().getHeader("ZIPKIN-TRACE-ID"))
				.isEqualTo(SpanUtil.idToHex(expectedTraceId));
		then(this.reporter.getSpans()).hasSize(1);
		then(this.reporter.getSpans().get(0).tags()).containsEntry("custom", "tag");
	}

	@Override
	protected void configureMockMvcBuilder(DefaultMockMvcBuilder mockMvcBuilder) {
		mockMvcBuilder.addFilters(this.traceFilter, this.myFilter);
	}

	private MvcResult whenSentPingWithoutTracingData() throws Exception {
		return this.mockMvc
				.perform(MockMvcRequestBuilders.get("/ping").accept(MediaType.TEXT_PLAIN))
				.andReturn();
	}

	private MvcResult whenSentPingWithTraceId(Long passedTraceId) throws Exception {
		return sendPingWithTraceId(TRACE_ID_NAME, passedTraceId);
	}

	private MvcResult whenSentInfoWithTraceId(Long passedTraceId) throws Exception {
		return sendRequestWithTraceId("/additionalContextPath/info", TRACE_ID_NAME,
				passedTraceId);
	}

	private MvcResult whenSentFutureWithTraceId(Long passedTraceId) throws Exception {
		return sendRequestWithTraceId("/future", TRACE_ID_NAME, passedTraceId);
	}

	private MvcResult whenSentDeferredWithTraceId(Long passedTraceId) throws Exception {
		return sendDeferredWithTraceId(TRACE_ID_NAME, passedTraceId);
	}

	private MvcResult whenSentToNonExistentEndpointWithTraceId(Long passedTraceId) throws Exception {
		return sendRequestWithTraceId("/exception/nonExistent", TRACE_ID_NAME, passedTraceId, HttpStatus.NOT_FOUND);
	}

	private MvcResult sendPingWithTraceId(String headerName, Long traceId)
			throws Exception {
		return sendRequestWithTraceId("/ping", headerName, traceId);
	}

	private MvcResult sendDeferredWithTraceId(String headerName, Long traceId)
			throws Exception {
		return sendRequestWithTraceId("/deferred", headerName, traceId);
	}

	private MvcResult sendRequestWithTraceId(String path, String headerName, Long traceId)
			throws Exception {
		return this.mockMvc
				.perform(MockMvcRequestBuilders.get(path).accept(MediaType.TEXT_PLAIN)
						.header(headerName, SpanUtil.idToHex(traceId))
						.header(SPAN_ID_NAME, SpanUtil.idToHex(new Random().nextLong())))
				.andReturn();
	}

	private MvcResult whenSentRequestWithTraceIdAndNoSpanId(Long traceId)
			throws Exception {
		return this.mockMvc
				.perform(MockMvcRequestBuilders.get("/ping").accept(MediaType.TEXT_PLAIN)
						.header(TRACE_ID_NAME, SpanUtil.idToHex(traceId)))
				.andReturn();
	}

	private MvcResult sendRequestWithTraceId(String path, String headerName, Long traceId, HttpStatus status)
			throws Exception {
		return this.mockMvc
				.perform(MockMvcRequestBuilders.get(path).accept(MediaType.TEXT_PLAIN)
						.header(headerName, SpanUtil.idToHex(traceId))
						.header(SPAN_ID_NAME, SpanUtil.idToHex(new Random().nextLong())))
				.andExpect(status().is(status.value()))
				.andReturn();
	}

	private boolean notSampledHeaderIsPresent(MvcResult mvcResult) {
		return "0".equals(mvcResult.getResponse().getHeader(SAMPLED_NAME));
	}

	@DefaultTestAutoConfiguration
	@Configuration
	protected static class Config {

		@RestController
		public static class TestController {
			@Autowired
			private Tracing tracing;

			@RequestMapping("/ping")
			public String ping() {
				logger.info("ping");
				span = this.tracing.tracer().currentSpan();
				return "ping";
			}

			@RequestMapping("/throwsException")
			public void throwsException() {
				throw new RuntimeException();
			}

			@RequestMapping("/deferred")
			public DeferredResult<String> deferredMethod() {
				logger.info("deferred");
				span = this.tracing.tracer().currentSpan();
				span.tag("tag", "value");
				DeferredResult<String> result = new DeferredResult<>();
				result.setResult("deferred");
				return result;
			}

			@RequestMapping("/future")
			public CompletableFuture<String> future() {
				logger.info("future");
				return CompletableFuture.completedFuture("ping");
			}
		}

		@Configuration
		static class ManagementServer {
			@Bean
			@Primary
			ManagementServerProperties managementServerProperties() {
				ManagementServerProperties managementServerProperties = new ManagementServerProperties();
				managementServerProperties.setContextPath("/additionalContextPath");
				return managementServerProperties;
			}
		}

		@Bean
		public ArrayListSpanReporter testSpanReporter() {
			return new ArrayListSpanReporter();
		}

		@Bean Sampler alwaysSampler() {
			return Sampler.ALWAYS_SAMPLE;
		}

		@Bean
		@Order(TraceFilter.ORDER + 1)
		Filter myTraceFilter(final Tracing tracing) {
			return new MyFilter(tracing);
		}
	}
}

//tag::response_headers[]
@Component
@Order(TraceFilter.ORDER + 1)
class MyFilter extends GenericFilterBean {

	private final Tracing tracing;

	MyFilter(Tracing tracing) {
		this.tracing = tracing;
	}

	@Override public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		Span currentSpan = this.tracing.tracer().currentSpan();
		then(currentSpan).isNotNull();
		// for readability we're returning trace id in a hex form
		((HttpServletResponse) response)
				.addHeader("ZIPKIN-TRACE-ID", currentSpan.context().traceIdString());
		// we can also add some custom tags
		currentSpan.tag("custom", "tag");
		chain.doFilter(request, response);
	}
}
//end::response_headers[]