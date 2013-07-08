/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.qp.operator;

import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.explain.CompoundExplainer;
import com.akiban.server.explain.ExplainContext;
import com.akiban.server.explain.std.SortOperatorExplainer;
import com.akiban.util.ArgumentValidation;
import com.akiban.util.tap.InOutTap;
import com.akiban.qp.persistitadapter.Sorter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 <h1>Overview</h1>

 Sort_General generates an output stream containing all the rows of the input stream, sorted according to an
 ordering specification. The "General" in the name refers to the flexible implementation which is provided by
 the underlying {@link StoreAdapter}.

 <h1>Arguments</h1>

 <li><b>Operator inputOperator:</b> Operator providing the input stream.
 <li><b>RowType sortType:</b> Type of rows to be sorted.
 <li><b>API.Ordering ordering:</b> Specification of ordering, comprising a list of expressions and ascending/descending
 specifications.
 <li><b>API.SortOption sortOption:</b> Specifies whether duplicates should be kept (PRESERVE_DUPLICATES) or eliminated
 (SUPPRESS_DUPLICATES)

 <h1>Behavior</h1>

 Refer to specific implementations of {@link Sorter} for details.

 <h1>Output</h1>

 The rows of the input stream, sorted according to the ordering specification. Duplicates are eliminated if
 and only if the sortOption is SUPPRESS_DUPLICATES.

 <h1>Assumptions</h1>

 None.

 <h1>Performance</h1>

 Refer to specific implementations of {@link Sorter} for details.

 <h1>Memory Requirements</h1>

 Refer to specific implementations of {@link Sorter} for details.

 */
class Sort_General extends Operator
{
    // Object interface

    @Override
    public String toString()
    {
        if (sortOption == API.SortOption.PRESERVE_DUPLICATES)
            return String.format("%s(%s)", getClass().getSimpleName(), sortType);
        else
            return String.format("%s(%s, %s)", getClass().getSimpleName(), sortType, sortOption.name());
    }

    // Operator interface

    @Override
    public List<Operator> getInputOperators()
    {
        return Collections.singletonList(inputOperator);
    }

    @Override
    protected Cursor cursor(QueryContext context, QueryBindingsCursor bindingsCursor)
    {
        return new Execution(context, inputOperator.cursor(context, bindingsCursor));
    }

    @Override
    public RowType rowType()
    {
        return sortType;
    }

    @Override
    public void findDerivedTypes(Set<RowType> derivedTypes)
    {
        inputOperator.findDerivedTypes(derivedTypes);
        derivedTypes.add(sortType);
    }

    @Override
    public String describePlan()
    {
        return describePlan(inputOperator);
    }

    // Sort_General interface

    public Sort_General(Operator inputOperator,
                        RowType sortType,
                        API.Ordering ordering,
                        API.SortOption sortOption)
    {
        ArgumentValidation.notNull("sortType", sortType);
        ArgumentValidation.isGT("ordering.columns()", ordering.sortColumns(), 0);
        this.inputOperator = inputOperator;
        this.sortType = sortType;
        this.ordering = ordering;
        this.sortOption = sortOption;
    }
    
    // Class state

    private static final InOutTap TAP_OPEN = OPERATOR_TAP.createSubsidiaryTap("operator: Sort_General open");
    private static final InOutTap TAP_NEXT = OPERATOR_TAP.createSubsidiaryTap("operator: Sort_General next");
    private static final InOutTap TAP_LOAD = OPERATOR_TAP.createSubsidiaryTap("operator: Sort_General load");
    private static final Logger LOG = LoggerFactory.getLogger(Sort_General.class);

    // Object state

    private final Operator inputOperator;
    private final RowType sortType;
    private final API.Ordering ordering;
    private final API.SortOption sortOption;

    @Override
    public CompoundExplainer getExplainer(ExplainContext context)
    {
        return new SortOperatorExplainer(getName(), sortOption, sortType, inputOperator, ordering, context);
    }

    // Inner classes

    private class Execution extends OperatorExecutionBase implements Cursor
    {
        // Cursor interface

        @Override
        public void open()
        {
            TAP_OPEN.in();
            try {
                CursorLifecycle.checkIdle(this);
                input.open();
                output = new SorterToCursorAdapter(adapter(), context, bindings, input, sortType, ordering, sortOption, TAP_LOAD);
                output.open();
            } finally {
                TAP_OPEN.out();
            }
        }

        @Override
        public Row next()
        {
            Row row = null;
            if (TAP_NEXT_ENABLED) {
                TAP_NEXT.in();
            }
            try {
                if (CURSOR_LIFECYCLE_ENABLED) {
                    CursorLifecycle.checkIdleOrActive(this);
                }
                checkQueryCancelation();
                if (!input.isActive()) {
                    row = output.next();
                    if (row == null) {
                        close();
                    }
                }
            } finally {
                if (TAP_NEXT_ENABLED) {
                    TAP_NEXT.out();
                }
            }
            if (LOG_EXECUTION) {
                LOG.debug("Sort_General: yield {}", row);
            }
            return row;
        }

        @Override
        public void close()
        {
            CursorLifecycle.checkIdleOrActive(this);
            if (output != null) {
                input.close();
                output.close();
                output = null;
            }
        }

        @Override
        public void destroy()
        {
            close();
            input.destroy();
            if (output != null) {
                output.destroy();
                output = null;
            }
            destroyed = true;
        }

        @Override
        public boolean isIdle()
        {
            return !destroyed && output == null;
        }

        @Override
        public boolean isActive()
        {
            return !destroyed && output != null;
        }

        @Override
        public boolean isDestroyed()
        {
            return destroyed;
        }

        @Override
        public void openBindings() {
            input.openBindings();
        }

        @Override
        public QueryBindings nextBindings() {
            bindings = input.nextBindings();
            return bindings;
        }

        @Override
        public void closeBindings() {
            input.closeBindings();
        }

        // Execution interface

        Execution(QueryContext context, Cursor input)
        {
            super(context);
            this.input = input;
        }

        // Object state

        private final Cursor input;
        private Cursor output;
        private boolean destroyed = false;
        private QueryBindings bindings;
    }
}
