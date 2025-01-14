/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.operator.project.MergingPageOutput;
import io.prestosql.operator.project.PageProcessor;
import io.prestosql.snapshot.SingleInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.type.Type;

import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"processor", "snapshotState"})
public class FilterAndProjectOperator
        implements Operator
{
    private final OperatorContext operatorContext;
    private final LocalMemoryContext pageProcessorMemoryContext;
    private final LocalMemoryContext outputMemoryContext;

    private final PageProcessor processor;
    private final MergingPageOutput mergingOutput;
    private boolean finishing;

    private final SingleInputSnapshotState snapshotState;

    public FilterAndProjectOperator(
            OperatorContext operatorContext,
            PageProcessor processor,
            MergingPageOutput mergingOutput)
    {
        this.processor = requireNonNull(processor, "processor is null");
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.pageProcessorMemoryContext = newSimpleAggregatedMemoryContext().newLocalMemoryContext(ScanFilterAndProjectOperator.class.getSimpleName());
        this.outputMemoryContext = operatorContext.newLocalSystemMemoryContext(FilterAndProjectOperator.class.getSimpleName());
        this.mergingOutput = requireNonNull(mergingOutput, "mergingOutput is null");
        this.snapshotState = operatorContext.isSnapshotEnabled() ? SingleInputSnapshotState.forOperator(this, operatorContext) : null;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public final void finish()
    {
        mergingOutput.finish();
        finishing = true;
    }

    @Override
    public final boolean isFinished()
    {
        if (snapshotState != null && snapshotState.hasMarker()) {
            // Snapshot: there are pending markers. Need to send them out before finishing this operator.
            return false;
        }

        boolean finished = finishing && mergingOutput.isFinished();
        if (finished) {
            outputMemoryContext.setBytes(mergingOutput.getRetainedSizeInBytes());
        }
        return finished;
    }

    @Override
    public final boolean needsInput()
    {
        return !finishing && mergingOutput.needsInput();
    }

    @Override
    public final void addInput(Page page)
    {
        if (snapshotState != null) {
            if (snapshotState.processPage(page)) {
                return;
            }
        }

        checkState(!finishing, "Operator is already finishing");
        requireNonNull(page, "page is null");
        checkState(mergingOutput.needsInput(), "Page buffer is full");

        mergingOutput.addInput(processor.process(
                operatorContext.getSession().toConnectorSession(),
                operatorContext.getDriverContext().getYieldSignal(),
                pageProcessorMemoryContext,
                page));
        outputMemoryContext.setBytes(mergingOutput.getRetainedSizeInBytes() + pageProcessorMemoryContext.getBytes());
    }

    @Override
    public final Page getOutput()
    {
        if (snapshotState != null) {
            Page marker = snapshotState.nextMarker();
            if (marker != null) {
                return marker;
            }
        }

        return mergingOutput.getOutput();
    }

    @Override
    public Page pollMarker()
    {
        return snapshotState.nextMarker();
    }

    @Override
    public void close()
    {
        if (snapshotState != null) {
            snapshotState.close();
        }
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        FilterAndProjectOperatorState myState = new FilterAndProjectOperatorState();
        myState.operatorContext = operatorContext.capture(serdeProvider);
        myState.pageProcessorMemoryContext = pageProcessorMemoryContext.getBytes();
        myState.outputMemoryContext = outputMemoryContext.getBytes();
        myState.mergingOutput = mergingOutput.capture(serdeProvider);
        myState.finishing = finishing;
        return myState;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        FilterAndProjectOperatorState myState = (FilterAndProjectOperatorState) state;
        this.operatorContext.restore(myState.operatorContext, serdeProvider);
        this.pageProcessorMemoryContext.setBytes(myState.pageProcessorMemoryContext);
        this.outputMemoryContext.setBytes(myState.outputMemoryContext);
        this.mergingOutput.restore(myState.mergingOutput, serdeProvider);
        this.finishing = myState.finishing;
    }

    private static class FilterAndProjectOperatorState
            implements Serializable
    {
        private Object operatorContext;
        private long pageProcessorMemoryContext;
        private long outputMemoryContext;
        private Object mergingOutput;
        private boolean finishing;
    }

    public static class FilterAndProjectOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final Supplier<PageProcessor> processor;
        private final List<Type> types;
        private final DataSize minOutputPageSize;
        private final int minOutputPageRowCount;
        private boolean closed;

        public FilterAndProjectOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                Supplier<PageProcessor> processor,
                List<Type> types,
                DataSize minOutputPageSize,
                int minOutputPageRowCount)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.processor = requireNonNull(processor, "processor is null");
            this.types = ImmutableList.copyOf(requireNonNull(types, "types is null"));
            this.minOutputPageSize = requireNonNull(minOutputPageSize, "minOutputPageSize is null");
            this.minOutputPageRowCount = minOutputPageRowCount;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, FilterAndProjectOperator.class.getSimpleName());
            return new FilterAndProjectOperator(
                    operatorContext,
                    processor.get(),
                    new MergingPageOutput(types, minOutputPageSize.toBytes(), minOutputPageRowCount));
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new FilterAndProjectOperatorFactory(operatorId, planNodeId, processor, types, minOutputPageSize, minOutputPageRowCount);
        }
    }
}
