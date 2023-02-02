// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer.operator.physical;

import com.google.common.base.Objects;
import com.starrocks.catalog.TableFunction;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.List;

public class PhysicalTableFunctionOperator extends PhysicalOperator {
    private final TableFunction fn;
    //ColumnRefSet represent output by table function
    private final ColumnRefSet fnResultColumnRefSet;
    //External column ref of the join logic generated by the table function
    private final ColumnRefSet outerColumnRefSet;
    //table function input parameters
    private final List<ColumnRefOperator> fnParamColumnRef;

    public PhysicalTableFunctionOperator(ColumnRefSet fnResultColumnRefSet, TableFunction fn,
                                         List<ColumnRefOperator> fnParamColumnRef,
                                         ColumnRefSet outerColumnRefSet,
                                         long limit,
                                         ScalarOperator predicate,
                                         Projection projection) {
        super(OperatorType.PHYSICAL_TABLE_FUNCTION);
        this.fnResultColumnRefSet = fnResultColumnRefSet;
        this.fn = fn;
        this.fnParamColumnRef = fnParamColumnRef;
        this.outerColumnRefSet = outerColumnRefSet;
        this.limit = limit;
        this.predicate = predicate;
        this.projection = projection;
    }

    public ColumnRefSet getOutputColumns() {
        ColumnRefSet outputColumns = (ColumnRefSet) outerColumnRefSet.clone();
        outputColumns.union(fnResultColumnRefSet);
        return outputColumns;
    }

    public ColumnRefSet getFnResultColumnRefSet() {
        return fnResultColumnRefSet;
    }

    public TableFunction getFn() {
        return fn;
    }

    public List<ColumnRefOperator> getFnParamColumnRef() {
        return fnParamColumnRef;
    }

    public ColumnRefSet getOuterColumnRefSet() {
        return outerColumnRefSet;
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C context) {
        return visitor.visitPhysicalTableFunction(this, context);
    }

    @Override
    public <R, C> R accept(OptExpressionVisitor<R, C> visitor, OptExpression optExpression, C context) {
        return visitor.visitPhysicalTableFunction(optExpression, context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!super.equals(o)) {
            return false;
        }

        PhysicalTableFunctionOperator that = (PhysicalTableFunctionOperator) o;
        return Objects.equal(fn, that.fn) && Objects.equal(fnResultColumnRefSet, that.fnResultColumnRefSet) &&
                Objects.equal(outerColumnRefSet, that.outerColumnRefSet) &&
                Objects.equal(fnParamColumnRef, that.fnParamColumnRef);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(fn, fnResultColumnRefSet);
    }
}