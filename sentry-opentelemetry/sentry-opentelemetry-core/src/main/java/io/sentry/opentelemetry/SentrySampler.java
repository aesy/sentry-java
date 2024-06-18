package io.sentry.opentelemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.sentry.Baggage;
import io.sentry.IScopes;
import io.sentry.PropagationContext;
import io.sentry.SamplingContext;
import io.sentry.ScopesAdapter;
import io.sentry.SentryTraceHeader;
import io.sentry.SpanId;
import io.sentry.TracesSampler;
import io.sentry.TracesSamplingDecision;
import io.sentry.TransactionContext;
import io.sentry.protocol.SentryId;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SentrySampler implements Sampler {

  private final @NotNull SentryWeakSpanStorage spanStorage = SentryWeakSpanStorage.getInstance();
  private final @NotNull TracesSampler tracesSampler;

  public SentrySampler(final @NotNull IScopes scopes) {
    this.tracesSampler = new TracesSampler(scopes.getOptions());
  }

  public SentrySampler() {
    this(ScopesAdapter.getInstance());
  }

  @Override
  public SamplingResult shouldSample(
      final @NotNull Context parentContext,
      final @NotNull String traceId,
      final @NotNull String name,
      final @NotNull SpanKind spanKind,
      final @NotNull Attributes attributes,
      final @NotNull List<LinkData> parentLinks) {
    // note: parentLinks seems to usually be empty
    final @Nullable Span parentOtelSpan = Span.fromContextOrNull(parentContext);
    final @Nullable OtelSpanWrapper parentSentrySpan =
        parentOtelSpan != null ? spanStorage.getSentrySpan(parentOtelSpan.getSpanContext()) : null;

    if (parentSentrySpan != null) {
      return copyParentSentryDecision(parentSentrySpan);
    } else {
      final @Nullable TracesSamplingDecision samplingDecision =
          OtelSamplingUtil.extractSamplingDecision(attributes);
      if (samplingDecision != null) {
        return new SentrySamplingResult(samplingDecision);
      } else {
        return handleRootOtelSpan(traceId, parentContext);
      }
    }
  }

  private @NotNull SentrySamplingResult handleRootOtelSpan(
      final @NotNull String traceId, final @NotNull Context parentContext) {
    @Nullable Baggage baggage = null;
    @Nullable
    SentryTraceHeader sentryTraceHeader = parentContext.get(SentryOtelKeys.SENTRY_TRACE_KEY);
    @Nullable Baggage baggageFromContext = parentContext.get(SentryOtelKeys.SENTRY_BAGGAGE_KEY);
    if (sentryTraceHeader != null) {
      baggage = baggageFromContext;
    }

    // there's no way to get the span id here, so we just use a random id for sampling
    SpanId randomSpanId = new SpanId();
    final @NotNull PropagationContext propagationContext =
        sentryTraceHeader == null
            ? new PropagationContext(new SentryId(traceId), randomSpanId, null, baggage, null)
            : PropagationContext.fromHeaders(sentryTraceHeader, baggage, randomSpanId);

    final @NotNull TransactionContext transactionContext =
        TransactionContext.fromPropagationContext(propagationContext);
    final @NotNull TracesSamplingDecision sentryDecision =
        tracesSampler.sample(new SamplingContext(transactionContext, null));
    return new SentrySamplingResult(sentryDecision);
  }

  private @NotNull SentrySamplingResult copyParentSentryDecision(
      final @NotNull OtelSpanWrapper parentSentrySpan) {
    final @Nullable TracesSamplingDecision parentSamplingDecision =
        parentSentrySpan.getSamplingDecision();
    if (parentSamplingDecision != null) {
      return new SentrySamplingResult(parentSamplingDecision);
    } else {
      // this should never happen and only serve to calm the compiler
      // TODO [POTEL] log
      return new SentrySamplingResult(new TracesSamplingDecision(true));
    }
  }

  @Override
  public String getDescription() {
    return "SentrySampler";
  }
}
