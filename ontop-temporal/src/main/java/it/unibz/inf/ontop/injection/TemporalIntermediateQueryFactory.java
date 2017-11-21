package it.unibz.inf.ontop.injection;

import com.google.inject.assistedinject.Assisted;
import it.unibz.inf.ontop.evaluator.TermNullabilityEvaluator;
import it.unibz.inf.ontop.model.term.ImmutableExpression;
import it.unibz.inf.ontop.temporal.iq.node.*;
import it.unibz.inf.ontop.temporal.model.TemporalRange;

import java.time.Duration;
import java.util.Optional;

/**
 * Factory following the Guice AssistedInject pattern.
 *
 * See https://github.com/google/guice/wiki/AssistedInject.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public interface TemporalIntermediateQueryFactory extends IntermediateQueryFactory{

    TemporalJoinNode createTemporalJoinNode();
    TemporalJoinNode createTemporalJoinNode(ImmutableExpression joiningCondition);
    TemporalJoinNode createTemporalJoinNode(Optional<ImmutableExpression> joiningCondition);

    BoxMinusNode createBoxMinusNode(TemporalRange temporalRange);

    BoxPlusNode createBoxPlusNode(TemporalRange temporalRange);

    DiamondMinusNode createDiamondMinusNode(TemporalRange temporalRange);

    DiamondPlusNode createDiamondPlusNode(TemporalRange temporalRange);

    TemporalCoalesceNode createTemporalCoalesceNode();

    //TemporalRange createTemporalRange(Boolean beginInclusive, Boolean endInclusive, Duration begin, Duration end);
}