/*
 *
 * Copyright 2019 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.spark.sql.catalyst.expressions

import com.pingcap.tikv.expression.visitor.{ColumnMatcher, MetaResolver, SupportedExpressionValidator}
import com.pingcap.tikv.expression.{AggregateFunction, ByItem, ColumnRef, ExpressionBlacklist}
import com.pingcap.tikv.meta.{TiColumnInfo, TiDAGRequest, TiTableInfo}
import com.pingcap.tikv.region.RegionStoreClient.RequestTypes
import com.pingcap.tispark.TiDBRelation
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Average, Count, DeclarativeAggregate, First, Max, Min, PromotedSum, Sum, SumNotNullable}
import org.apache.spark.sql.execution.TiConverter.fromSparkType

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable

object ExprUtils {
  def transformGroupingToTiGrouping(expr: Expression,
                                    meta: TiTableInfo,
                                    dagRequest: TiDAGRequest): Unit =
    expr match {
      case BasicExpression(keyExpr) =>
        MetaResolver.resolve(keyExpr, meta)
        dagRequest.addGroupByItem(ByItem.create(keyExpr, false))
        // We need to add a `First` function in DAGRequest along with group by
        dagRequest.getFields.asScala
          .filter(ColumnMatcher.`match`(_, keyExpr))
          .foreach(
            (ref: ColumnRef) =>
              dagRequest
                .addAggregate(
                  AggregateFunction
                    .newCall(
                      AggregateFunction.FunctionType.First,
                      ref,
                      meta.getColumn(ref.getName).getType
                    )
              )
          )
      case _ =>
    }

  def transformAggExprToTiAgg(expr: Expression, meta: TiTableInfo, dagRequest: TiDAGRequest): Any =
    expr match {
      case _: Average =>
        throw new IllegalArgumentException("Should never be here")

      case f @ Sum(BasicExpression(arg)) =>
        addingSumAggToDAgReq(meta, dagRequest, f, arg)

      case f @ PromotedSum(BasicExpression(arg)) =>
        addingSumAggToDAgReq(meta, dagRequest, f, arg)

      case f @ Count(args) if args.length == 1 =>
        val tiArg = if (args.head.isInstanceOf[Literal]) {
          val firstColRef = if (meta.hasPrimaryKey) {
            val col = meta.getColumns.asScala.filter(col => col.isPrimaryKey).head
            ColumnRef.create(col.getName, meta)
          } else {
            val firstCol = meta.getColumns.get(0)
            ColumnRef.create(firstCol.getName, meta)
          }

          dagRequest.addRequiredColumn(firstColRef)
          firstColRef
        } else {
          args.flatMap(BasicExpression.convertToTiExpr).head
        }
        dagRequest.addAggregate(
          AggregateFunction
            .newCall(AggregateFunction.FunctionType.Count, tiArg, fromSparkType(f.dataType))
        )

      case f @ Min(BasicExpression(arg)) =>
        MetaResolver.resolve(arg, meta)
        dagRequest
          .addAggregate(
            AggregateFunction
              .newCall(AggregateFunction.FunctionType.Min, arg, fromSparkType(f.dataType))
          )

      case f @ Max(BasicExpression(arg)) =>
        MetaResolver.resolve(arg, meta)
        dagRequest
          .addAggregate(
            AggregateFunction
              .newCall(AggregateFunction.FunctionType.Max, arg, fromSparkType(f.dataType))
          )

      case f @ First(BasicExpression(arg), _) =>
        MetaResolver.resolve(arg, meta)
        dagRequest
          .addAggregate(
            AggregateFunction
              .newCall(AggregateFunction.FunctionType.First, arg, fromSparkType(f.dataType))
          )

      case _ =>
    }

  private def addingSumAggToDAgReq(meta: TiTableInfo,
                                   dagRequest: TiDAGRequest,
                                   f: DeclarativeAggregate,
                                   arg: TiExpression) = {
    MetaResolver.resolve(arg, meta)
    dagRequest
      .addAggregate(
        AggregateFunction
          .newCall(AggregateFunction.FunctionType.Sum, arg, fromSparkType(f.dataType))
      )
  }

  def transformFilter(expr: Expression,
                      meta: TiTableInfo,
                      dagRequest: TiDAGRequest): TiExpression = {
    expr match {
      case BasicExpression(arg) =>
        MetaResolver.resolve(arg, meta)
        arg
    }
  }

  def transformSortOrderToTiOrderBy(request: TiDAGRequest,
                                    sortOrder: Seq[SortOrder],
                                    meta: TiTableInfo): Unit = {
    val byItems = sortOrder.map { order =>
      {
        val expr = order.child
        val tiExpr = expr match {
          case BasicExpression(arg) => arg
        }
        MetaResolver.resolve(tiExpr, meta)
        ByItem.create(
          tiExpr,
          order.direction.sql.equalsIgnoreCase("DESC")
        )
      }
    }
    byItems.foreach(request.addOrderByItem)
  }

  def transformAttrToColRef(attr: Attribute, meta: TiTableInfo): TiExpression = {
    attr match {
      case BasicExpression(expr) =>
        MetaResolver.resolve(expr, meta)
        expr
    }
  }
  type TiDataType = com.pingcap.tikv.types.DataType
  type TiExpression = com.pingcap.tikv.expression.Expression

  def isSupportedAggregate(aggExpr: AggregateExpression,
                           tiDBRelation: TiDBRelation,
                           blacklist: ExpressionBlacklist): Boolean =
    aggExpr.aggregateFunction match {
      case Average(_) | Sum(_) | SumNotNullable(_) | PromotedSum(_) | Count(_) | Min(_) | Max(_) =>
        !aggExpr.isDistinct &&
          aggExpr.aggregateFunction.children
            .forall(isSupportedBasicExpression(_, tiDBRelation, blacklist))
      case _ => false
    }

  def isSupportedBasicExpression(expr: Expression,
                                 tiDBRelation: TiDBRelation,
                                 blacklist: ExpressionBlacklist): Boolean = {
    if (!BasicExpression.isSupportedExpression(expr, RequestTypes.REQ_TYPE_DAG)) return false

    BasicExpression.convertToTiExpr(expr).fold(false) { expr: TiExpression =>
      MetaResolver.resolve(expr, tiDBRelation.table)
      return SupportedExpressionValidator.isSupportedExpression(expr, blacklist)
    }
  }

  /**
   * Is expression allowed to be pushed down
   *
   * @param expr the expression to examine
   * @return whether expression can be pushed down
   */
  def isPushDownSupported(expr: Expression, source: TiDBRelation): Boolean = {
    val nameTypeMap = mutable.HashMap[String, com.pingcap.tikv.types.DataType]()
    source.table.getColumns
      .foreach((info: TiColumnInfo) => nameTypeMap(info.getName) = info.getType)

    if (expr.children.isEmpty) {
      expr match {
        // bit, set and enum type is not allowed to be pushed down
        case attr: AttributeReference if nameTypeMap.contains(attr.name) =>
          return nameTypeMap.get(attr.name).head.isPushDownSupported
        // TODO: Currently we do not support literal null type push down
        // when Constant is ready to support literal null or we have other
        // options, remove this.
        case constant: Literal =>
          return constant.value != null
        case _ => return true
      }
    } else {
      for (expr <- expr.children) {
        if (!isPushDownSupported(expr, source)) {
          return false
        }
      }
    }

    true
  }

  def isSupportedOrderBy(expr: Expression,
                         source: TiDBRelation,
                         blacklist: ExpressionBlacklist): Boolean =
    isSupportedBasicExpression(expr, source, blacklist) && isPushDownSupported(expr, source)

  def isSupportedFilter(expr: Expression,
                        source: TiDBRelation,
                        blacklist: ExpressionBlacklist): Boolean =
    isSupportedBasicExpression(expr, source, blacklist) && isPushDownSupported(expr, source)

  // if contains UDF / functions that cannot be folded
  def isSupportedGroupingExpr(expr: NamedExpression,
                              source: TiDBRelation,
                              blacklist: ExpressionBlacklist): Boolean =
    isSupportedBasicExpression(expr, source, blacklist) && isPushDownSupported(expr, source)
}
