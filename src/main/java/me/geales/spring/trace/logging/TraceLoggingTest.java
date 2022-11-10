package me.geales.spring.trace.logging;

import java.util.List;
import java.util.Set;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTagsContributor;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTagsProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.observation.reactive.DefaultServerRequestObservationConvention;
import org.springframework.http.observation.reactive.ServerHttpObservationDocumentation;
import org.springframework.http.observation.reactive.ServerRequestObservationContext;
import org.springframework.http.observation.reactive.ServerRequestObservationConvention;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.reactive.ServerHttpObservationFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;

@SpringBootApplication
@RestController
public class TraceLoggingTest {
    private final Logger logger = LoggerFactory.getLogger(TraceLoggingTest.class);

    public static void main(String[] args) {
        SpringApplication.run(TraceLoggingTest.class, args);
    }

    @GetMapping("/")
    public String getter() {
        var msg = "hello, world!";
        logger.info(msg);
        return msg;
    }

    @GetMapping("/exception")
    public String exception() {
        var msg = "exception!";
        logger.info(msg);
        throw new RuntimeException(msg);
    }

    @Bean
    ServerHttpObservationFilter serverHttpObservationFilter(
            MetricsProperties metricsProperties,
            ObservationProperties observationProperties,
            ObservationRegistry registry,
                                                            ObjectProvider<WebFluxTagsProvider> tagConfigurer,
                                                            ObjectProvider<WebFluxTagsContributor> contributorsProvider) {
        String observationName = observationProperties.getHttp().getServer().getRequests().getName();
        String metricName = metricsProperties.getWeb().getServer().getRequest().getMetricName();
        String name = (observationName != null) ? observationName : metricName;
        WebFluxTagsProvider tagsProvider = tagConfigurer.getIfAvailable();
        List<WebFluxTagsContributor> tagsContributors = contributorsProvider.orderedStream().toList();
        ServerRequestObservationConvention convention = new DefaultServerRequestObservationConvention(name);
//        if (tagsProvider != null) {
//            convention = new ServerRequestObservationConventionAdapter(name, tagsProvider);
//        }
//        else if (!tagsContributors.isEmpty()) {
//            convention = new ServerRequestObservationConventionAdapter(name, tagsContributors);
//        }
        return new MyServerHttpObservationFilter(registry, convention);
    }

    private static class MyServerHttpObservationFilter extends ServerHttpObservationFilter {
        private static final ServerRequestObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultServerRequestObservationConvention();
        private static final Set<String> DISCONNECTED_CLIENT_EXCEPTIONS = Set.of("AbortedException",
                "ClientAbortException", "EOFException", "EofException");

        private static final String SCOPE_ATTRIBUTE_NAME = "SCOPE";
        private final ObservationRegistry observationRegistry;
        private final ServerRequestObservationConvention observationConvention;

        public MyServerHttpObservationFilter(ObservationRegistry observationRegistry, ServerRequestObservationConvention observationConvention) {
            super(observationRegistry);
            this.observationRegistry = observationRegistry;
            this.observationConvention = observationConvention;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            ServerRequestObservationContext observationContext = new ServerRequestObservationContext(exchange);
            exchange.getAttributes().put(CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observationContext);
            return chain.filter(exchange).transformDeferred(call -> filter(exchange, observationContext, call));
        }

        private Publisher<Void> filter(ServerWebExchange exchange, ServerRequestObservationContext observationContext, Mono<Void> call) {
            Observation observation = ServerHttpObservationDocumentation.HTTP_REQUESTS.observation(this.observationConvention,
                    DEFAULT_OBSERVATION_CONVENTION, () -> observationContext, this.observationRegistry);
            observation.start();
            return call
                    .doFirst(() -> observationContext.getServerWebExchange().getAttributes().put(SCOPE_ATTRIBUTE_NAME, observation.openScope()))
                    .doOnEach(signal -> {
                        Throwable throwable = signal.getThrowable();
                        if (throwable != null) {
                            if (DISCONNECTED_CLIENT_EXCEPTIONS.contains(throwable.getClass().getSimpleName())) {
                                observationContext.setConnectionAborted(true);
                            }
                            observationContext.setError(throwable);
                        }
                        onTerminalSignal(observation, exchange);
                    })
                    .doOnCancel(() -> {
                        observationContext.setConnectionAborted(true);
                        observation.stop();
                    })
                    // observation.stop() doesn't seem to close the current scope ¯\_(ツ)_/¯
                    .doFinally(signalType -> {
                        var attributes = observationContext.getServerWebExchange().getAttributes();
                        if (attributes.get(SCOPE_ATTRIBUTE_NAME) instanceof Observation.Scope scope) {
                            scope.close();
                            attributes.remove(SCOPE_ATTRIBUTE_NAME);
                        }
                    });
        }

        private void onTerminalSignal(Observation observation, ServerWebExchange exchange) {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()) {
                observation.stop();
            }
            else {
                response.beforeCommit(() -> {
                    observation.stop();
                    return Mono.empty();
                });
            }
        }
    }
}
