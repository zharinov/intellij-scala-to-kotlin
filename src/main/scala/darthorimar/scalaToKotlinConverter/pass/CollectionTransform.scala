package darthorimar.scalaToKotlinConverter.pass

import darthorimar.scalaToKotlinConverter
import darthorimar.scalaToKotlinConverter.Exprs.listType
import darthorimar.scalaToKotlinConverter.{Exprs, Utils}
import darthorimar.scalaToKotlinConverter.ast._
import darthorimar.scalaToKotlinConverter.pass.Helpers.ApplyCall
import darthorimar.scalaToKotlinConverter.types.TypeUtils.{ListType, WithType}
import darthorimar.scalaToKotlinConverter.types.{KotlinTypes, StdTypes, TypeUtils}
import org.scalafmt.internal.SyntacticGroup.Term

class CollectionTransform extends Transform {

  override def transform[T](ast: AST): T = {
    //    if (ast.isInstanceOf[File])
    //      println(Utils.prettyPrint(ast))
    super.transform(ast)
  }

  override protected def action(ast: AST): Option[AST] = ast match {
    //Options

    // Some(x) --> x
    case ApplyCall(WithType(ScalaType("scala.Some$")), Seq(v)) =>
      Some(transform[Expr](v))

    // None --> null
    case RefExpr(ScalaType("scala.None$"), None, _, _, _) =>
      Some(Exprs.nullLit)

    // opt.map(f), opt.flatMap(f) --> opt?.let {f(it)}
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "map" | "flatMap", typeParams, true), Seq(p), paramsExpectedTypes)
      if referenceObject.exprType.isInstanceOf[NullableType] =>
      Some(CallExpr(
        transform[Type](exprType),
        RefExpr(transform[Type](refTy),
          Some(PostfixExpr(referenceObject.exprType, transform[Expr](referenceObject), "?")),
          "let",
          typeParams.map(transform[Type]), true),
        Seq(transform[Expr](p)),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    //     opt.getOrElse(x) --> opt :? x
    case CallExpr(_, RefExpr(refTy, Some(referenceObject@WithType(NullableType(_))), "getOrElse" | "orElse", _, true), Seq(p), paramsExpectedTypes)
      if referenceObject.exprType.isInstanceOf[NullableType] =>
      val param = p match {
        case LambdaExpr(_, _, expr, _) => expr
        case _ => p
      }
      Some(Exprs.simpleInfix(transform[Type](refTy), ":?", transform[Expr](referenceObject), transform[Expr](param)))

    //opt.get --> opt!!
    case CallExpr(_, RefExpr(refTy, Some(referenceObject), "get", _, true), _, paramsExpectedTypes)
      if referenceObject.exprType.isInstanceOf[NullableType] =>
      Some(PostfixExpr(transform[Type](refTy), transform[Expr](referenceObject), "!!"))

    //Seqs

    //Seq(1,2,3) --> listOf(1,2,3)
    case CallExpr(exprType, RefExpr(refTy, Some(RefExpr(_, None, "scala.Seq", typeParams, false)), "apply", _, _), params, paramsExpectedTypes) =>
      Some(CallExpr(
        transform[Type](exprType),
        RefExpr(transform[Type](refTy), None, "listOf", typeParams.map(transform[Type]), true),
        params.map(transform[Expr]),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    //Array(1,2,3) --> arrayOf(1,2,3)
    case CallExpr(exprType, RefExpr(refTy, Some(RefExpr(_, None, "Array", typeParams, false)), "apply", _, _), params, paramsExpectedTypes) =>
      Some(CallExpr(
        transform[Type](exprType),
        RefExpr(transform[Type](refTy), None, "arrayOf", typeParams.map(transform[Type]), true),
        params.map(transform[Expr]),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    //Seq.empty[T] --> emptyList<T>()
    case CallExpr(_, RefExpr(_, Some(RefExpr(_, None, "scala.Seq" | "scala.List", _, false)), "empty", typeParams, _), Seq(), paramsExpectedTypes) =>
      if (typeParams.isEmpty) Some(Exprs.emptyList)
      else Some(Exprs.emptyList(transform[Type](typeParams.head)))

    //Array.empty[T] --> emptyList<T>()
    case CallExpr(_, RefExpr(_, Some(RefExpr(ty, None, "Array", _, false)), "empty", typeParams, _), Seq(), paramsExpectedTypes) =>
      if (typeParams.isEmpty) Some(Exprs.simpleCall("emptyArray", ty, Seq.empty))
      else Some(
        CallExpr(
          listType(ty),
          RefExpr(ty, None, "emptyArray", Seq(ty), true),
          Seq.empty,
          Seq.empty))

    //Nil --> emptytList()
    case RefExpr(GenericType(KotlinTypes.LIST, _), None, "scala.Nil", _, false) =>
      Some(Exprs.emptyList)

    //     (1 :: seq, 1 +: seq)  --> listOf(1) + seq
    case CallExpr(exprType, RefExpr(refTy, Some(left), "::" | "+:", _, _), Seq(right), paramsExpectedTypes) =>
      //      if TypeUtils.isKotlinList(right.exprType) =>
      Some(Exprs.simpleInfix(
        Exprs.listType(transform[Type](exprType)),
        "+",
        CallExpr(
          transform[Type](exprType),
          RefExpr(transform[Type](exprType), None, "listOf", Seq.empty, true),
          Seq(transform[Expr](right)),
          paramsExpectedTypes.map(transform[CallParameterInfo])),
        transform[Expr](left)))


    // seq :+ 1  --> seq + 1
    case CallExpr(exprType, RefExpr(refTy, Some(left), ":+", _, true), Seq(right), paramsExpectedTypes) =>
      Some(
        Exprs.simpleInfix(Exprs.listType(transform[Type](exprType)),
          "+",
          transform[Expr](right),
          transform[Expr](right)))

    // seq.mkString(a,b,c) --> seq.joinToString(b,a,c)
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "mkString", typeParams, true), params, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      val newParams =
        if (params.length == 3) Seq(params(1), params(0), params(2))
        else params
      Some(CallExpr(exprType,
        RefExpr(refTy, Some(transform[Expr](referenceObject)),
          "joinToString",
          typeParams, true),
        newParams,
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    // seq.tail --> seq.drop(1)
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "tail", typeParams, true), _, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(CallExpr(exprType,
        RefExpr(refTy, Some(transform[Expr](referenceObject)), "drop", typeParams, true),
        Seq(LitExpr(StdTypes.INT, "1")),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    // seq.head --> seq.first
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "head", typeParams, true), _, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(CallExpr(exprType, RefExpr(refTy, Some(transform[Expr](referenceObject)), "first", typeParams, true), Seq.empty,
        paramsExpectedTypes.map(transform[CallParameterInfo])))


    // seq.init --> seq.dropLast(1)
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "init", typeParams, true), _, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(CallExpr(exprType, RefExpr(refTy, Some(transform[Expr](referenceObject)), "dropLast", typeParams, true), Seq(LitExpr(StdTypes.INT, "1")),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    //seq.foreach --> seq.forEach
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "foreach", typeParams, true), params, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(CallExpr(exprType, RefExpr(refTy, Some(transform[Expr](referenceObject)), "forEach", typeParams, true), params.map(transform[Expr]),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    //seq.forall --> seq.all
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "forall", typeParams, true), params, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(CallExpr(exprType, RefExpr(refTy, Some(transform[Expr](referenceObject)), "all", typeParams, true), params.map(transform[Expr]),
        paramsExpectedTypes.map(transform[CallParameterInfo])))


    //     str * i => str.repeat(i)
    case CallExpr(exprType, RefExpr(refTy, Some(left), "*", _, _), Seq(right), paramsExpectedTypes)
      if left.exprType == StdTypes.STRING && right.exprType == StdTypes.INT =>
      Some(CallExpr(exprType, RefExpr(exprType, Some(transform[Expr](left)), "repeat", Seq.empty, true), Seq(right),
        paramsExpectedTypes.map(transform[CallParameterInfo])))

    //seq(i) --> seq[i]
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "apply", typeParams, true), Seq(i), paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(BracketsExpr(exprType, transform[Expr](referenceObject), transform[Expr](i)))

    //seq1 ++ seq2  --> seq1+ seq2
    case CallExpr(exprType, RefExpr(refTy, Some(left), "++", typeParams, true), Seq(right), paramsExpectedTypes)
      if TypeUtils.isKotlinList(left.exprType) && TypeUtils.isKotlinList(right.exprType) =>
      Some(Exprs.simpleInfix(exprType, "+", transform[Expr](left), transform[Expr](right)))

    // seq.nonEmpty --> seq.isNotEmpty
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "nonEmpty", typeParams, _), _, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(CallExpr(exprType, RefExpr(refTy, Some(transform[Expr](referenceObject)), "isNotEmpty", typeParams, true), Seq.empty,
        paramsExpectedTypes.map(transform[CallParameterInfo])))


    // seq.size() --> seq.size
    case CallExpr(exprType, RefExpr(refTy, Some(referenceObject), "size", typeParams, _), _, paramsExpectedTypes)
      if TypeUtils.isKotlinList(referenceObject.exprType) =>
      Some(RefExpr(refTy, Some(transform[Expr](referenceObject)), "size", typeParams, true))

    // seqOfOptions.flatten --> seqOfOptions.filterNotNull()
    case CallExpr(callType, RefExpr(refTy, Some(referenceObject@WithType(ListType(NullableType(_)))), "flatten", typeParams, _), _, paramsExpectedTypes) =>
      Some(CallExpr(callType, RefExpr(refTy, Some(transform[Expr](referenceObject)), "filterNotNull", typeParams, true), Seq.empty,
        paramsExpectedTypes.map(transform[CallParameterInfo])))


    case ApplyCall(WithType(ScalaType("scala.Option$")), Seq(p)) =>
      Some(transform[Expr](p))
    //pairs

    //1 -> 2 --> 1 to 2
    case CallExpr(exprType, RefExpr(refTy, Some(left), "->", _, true), Seq(right), paramsExpectedTypes) =>
      Some(Exprs.simpleInfix(exprType, "to", transform[Expr](left), transform[Expr](right)))

    //p._1 --> p.first
    //p._2 --> p.second
    case RefExpr(refTy, Some(left@WithType(GenericType(KotlinTypes.PAIR, _))), index@("_1" | "_2"), _, false) =>
      Some(RefExpr(refTy, Some(transform[Expr](left)), if (index == "_1") "first" else "second", Seq.empty, false))

    // Some(x) --> x
    case a@ApplyCall(WithType(ScalaType("scala.util.Try$")), Seq(p)) =>
      Some(Exprs.simpleCall("runTry", a.exprType, Seq(transform[Expr](p))))

    case RefExpr(refTy, Some(referenceObject), "asInstanceOf", Seq(typeParam), false) =>
      Some(ParenthesesExpr(Exprs.asExpr(transform[Expr](referenceObject), typeParam)))

    case RefExpr(refTy, Some(referenceObject), "isInstanceOf", Seq(typeParam), false) =>
      Some(ParenthesesExpr(Exprs.isExpr(transform[Expr](referenceObject), typeParam)))

    case _ => None
  }

}