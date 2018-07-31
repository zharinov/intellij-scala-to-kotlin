package org.jetbrains.plugins.kotlinConverter.ast

trait AST


case class DefParameter(parameterType: Type, name: String) extends AST
case class MatchCaseClause(pattern: CasePattern, expr: Expr, guard: Option[Expr]) extends AST


sealed trait Constructor extends AST
case class ParamsConstructor(parameters: Seq[ConstructorParam]) extends Constructor
case object EmptyConstructor extends Constructor

case class ConstructorParam(kind: MemberKind, modifier: Attribute, name: String, parameterType: Type) extends AST

case class TypeParam(parameterType: Type) extends AST

case class FileDef(packageName: String, imports: Seq[ImportDef], definitions: Seq[DefExpr]) extends AST

sealed trait CasePattern extends AST {
  def name: String
}

case class CompositePattern(parts: Seq[CasePattern]) extends CasePattern {
  override def name: String = parts.mkString(" | ")
}
case class LitPattern(expr: Expr) extends CasePattern {
  override def name: String = expr match {
    case LitExpr(_, name) => name
    case RefExpr(_, _, ref, _, _) => ref
  }
}
case class ConstructorPattern(ref: String,
                              args: Seq[CasePattern],
                              label: Option[String],
                              representation: String)  extends CasePattern {
  override def name: String = representation
}
case class TypedPattern(referenceName: String, patternType: Type) extends CasePattern {
  override def name: String = s"$referenceName: $patternType"
}
case class ReferencePattern(referenceName: String) extends CasePattern {
  override def name: String = referenceName
}
case object WildcardPattern extends CasePattern {
  override def name: String = "_"
}

sealed trait WhenClause extends AST

case class ExprWhenClause(clause: Expr, expr: Expr) extends WhenClause
case class ElseWhenClause(expr: Expr) extends WhenClause

sealed trait ForEnumerator extends  AST
case class ForGenerator(pattern: CasePattern, expr: Expr) extends ForEnumerator
case class ForGuard(condition: Expr) extends ForEnumerator
case class ForVal(valDefExpr: Expr) extends ForEnumerator

case class SupersBlock(constructor: Option[SuperConstructor], supers: Seq[Type]) extends AST

case class SuperConstructor(constructorType: Type, exprs: Seq[Expr]) extends AST
