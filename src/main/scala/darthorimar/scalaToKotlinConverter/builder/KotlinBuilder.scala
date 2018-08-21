package darthorimar.scalaToKotlinConverter.builder

import darthorimar.scalaToKotlinConverter.ast._
import darthorimar.scalaToKotlinConverter.scopes.ScopedVal.scoped
import darthorimar.scalaToKotlinConverter.scopes.{BuilderState, ScopedVal}

class KotlinBuilder extends BuilderBase {
  val stateVal: ScopedVal[BuilderState] = new ScopedVal[BuilderState](BuilderState())

  def gen(ast: AST): Unit =
    ast match {
      case e: ErrorAst =>
        str(s"/* ERROR converting `${e.text}`*/")

      case File(pckg, imports, defns, _) =>
        if (pckg.trim.nonEmpty) {
          str("package ")
          str(pckg)
          nl()
        }
        if (imports.nonEmpty) {
          nl()
          nl()
          repNl(imports.toSeq.sortBy(_.ref))(gen)
          nl()
          nl()
        }
        repNl(defns)(gen)

      case Defn(attrs, t, name, typeParams, consruct, supersBlock, block, companionDefn) =>
        rep(attrs, " ")(gen)
        if (attrs.nonEmpty) str(" ")
        genKeyword(t)
        str(" ")
        str(name)
        if (typeParams.nonEmpty) {
          str("<")
          rep(typeParams, ", ")(gen)
          str(">")
        }
        opt(consruct)(gen)
        opt(supersBlock) { case SupersBlock(constuctor, supers) =>
          str(" : ")
          opt(constuctor) { case SuperConstructor(exprType, exprs, needBrackets) =>
            genType(exprType, false)
            if (needBrackets || exprs.nonEmpty) str("(")
            rep(exprs, ", ")(gen)
            if (needBrackets || exprs.nonEmpty) str(")")
          }
          if (constuctor.isDefined && supers.nonEmpty) {
            str(", ")
          }
          rep(supers, ", ")(genType(_, false))
        }
        str(" ")
        opt(block)(genAsBlock)


      case EmptyConstructor =>

      case ParamsConstructor(params) =>
        str("(")
        rep(params, ", ")(gen)
        str(")")

      case ConstructorParam(parType, mod, name, exprType) =>
        gen(mod)
        str(" ")
        gen(parType)
        str(" ")
        str(name)
        genType(exprType)

      case x@SimpleValOrVarDef(attributes, isVal, name, valType, expr) =>
        rep(attributes, " ")(gen)
        str(" ")
        str(x.keyword)
        str(" ")
        str(name)
        opt(valType)(genType(_))
        opt(expr) { e =>
          str(" = ")
          gen(e)
        }

      case x@ValOrVarDef(attributes, isVal, patterns, expr) =>
        rep(attributes, " ")(gen)
        str(" ")
        str(x.keyword)
        str(" (")
        rep(patterns, ", ")(gen)
        str(")")
        opt(expr) { e =>
          str(" = ")
          gen(e)
        }

      case LazyValDef(name, ty, expr) =>
        str("val ")
        str(name)
        str(" by lazy ")
        genBlockOrExpr(expr)


      case ReturnExpr(label, expr) =>
        str("return")
        opt(label) { l =>
          str("@")
          str(l)
        }
        str(" ")
        opt(expr)(gen)

      case p: CasePattern =>
        str(p.representation)

      case DefnDef(attrs, name, typeParams, args, retType, body) =>
        rep(attrs, " ")(gen)
        if (attrs.nonEmpty) str(" ")
        str("fun")
        if (typeParams.nonEmpty) {
          str("<")
          rep(typeParams, ", ")(gen)
          str(">")
        }
        str(" ")
        str(name)
        str("(")
        rep(args, ", ") { case DefParameter(parameterType, name, isVarArg, _) =>
          if (isVarArg) str("vararg ")
          str(name)
          genType(parameterType)
        }
        str(")")
        genType(retType)
        str(" ")
        opt(body) { b =>
          if (!b.isInstanceOf[BlockExpr]) {
            str("=")
            gen(b)
          } else genAsBlock(b)
        }

      case KotlinTryExpr(exprType, tryBlock, catches, finallyBlock) =>
        str("try ")
        genAsBlock(tryBlock)
        rep(catches, " ") { case KotlinCatchCase(name, valueType, expr) =>
          str("catch (")
          str(name)
          genType(valueType)
          str(") ")
          genAsBlock(expr)
        }
        opt(finallyBlock) { f =>
          str(" finally ")
          genAsBlock(f)
        }

      case Import(reference) =>
        str("import ")
        str(reference)

      case InfixExpr(exprType, op, left, right, isLeftAssoc) =>
        gen(left)
        str(" ")
        gen(op)
        str(" ")
        gen(right)
      case ParenthesesExpr(inner) =>
        str("(")
        gen(inner)
        str(")")
      case LambdaExpr(exprType, params, expr, needBraces) =>
        str("{ ")
        if (needBraces) str("(")
        rep(params, ", ") { case DefParameter(_, name, _, _) =>
          str(name)
        }
        if (needBraces) str(")")
        if (params.nonEmpty) str(" -> ")
        expr match {
          case b: BlockExpr =>
            repNl(b.exprs)(gen)
          case _ =>
            gen(expr)
        }
        str(" }")

      case AssignExpr(left, right) =>
        gen(left)
        str(" = ")
        gen(right)

      case TypeExpr(exprType) =>
        genType(exprType, false)

      case CallExpr(exprType, ref, params, paramsExpectedTypes) =>
        gen(ref)
        if (params.size == 1 && params.head.isInstanceOf[LambdaExpr]) {
          str(" ")
          gen(params.head)
        } else {
          str("(")
          rep(params, ", ")(gen)
          str(")")
        }

      case RefExpr(exprType, obj, ref, typeParams, isFunc) =>
        opt(obj) { x => gen(x); str(".") }
        str(ref)
        if (typeParams.nonEmpty) {
          str("<")
          rep(typeParams, ", ")(genType(_, false))
          str(">")
        }

      case IfExpr(exprType, cond, trueB, falseB) =>
        str("if (")
        gen(cond)
        str(") ")
        genBlockOrExpr(trueB)
        opt(falseB) { b =>
          str(" else ")
          genBlockOrExpr(b)
        }

      case PostfixExpr(exprType, obj, op) =>
        gen(obj)
        str(op)

      case PrefixExpr(exprType, obj, op) =>
        str(op)
        gen(obj)


      case LitExpr(exprType, name) =>
        str(name)
      case UnderscoreExpr(exprType) =>
        str("it")

      case WhenExpr(exprType, expr, clauses) =>
        str("when")
        opt(expr) { e =>
          str(" (")
          gen(e)
          str(")")
        }
        str(" {")
        indented {
          repNl(clauses) {
            case ExprWhenClause(clause, expr) =>
              gen(clause)
              str(" -> ")
              genAsBlock(expr)
            case ElseWhenClause(expr) =>
              str("else -> ")
              genBlockOrExpr(expr)
          }
        }
        str("}")

      case NewExpr(instanceType, args) =>
        genType(instanceType, false)
        str("(")
        rep(args, ", ")(gen)
        str(")")

      case e: BlockExpr =>
        genRunBlock(e)

      case ForInExpr(exprType, ref, range, body) =>
        str("for (")
        gen(ref)
        str(" in ")
        gen(range)
        str(") ")
        genBlockOrExpr(body)

      case InterpolatedStringExpr(parts, injected) =>
        scoped(
          stateVal.updated(_.copy(inInterpolatedString = true))
        ) {
          str("\"")
          rep(parts.zip(injected), "") { case (p, i) =>
            str(p)
            str("$")
            gen(i)
          }
          str(parts.last)
          str("\"")
        }

      case BracketsExpr(exprType, expr, inBrackets) =>
        gen(expr)
        str("[")
        gen(inBrackets)
        str("]")
      case ThisExpr(exprType) =>
        str("this")

      case TypeParam(name) =>
        str(name)

      case ThrowExpr(expr) =>
        str("throw ")
        gen(expr)

      case EmptyDefExpr =>

      case x: Keyword =>
        genKeyword(x)

    }

  def genAsBlock(e: Expr): Unit = e match {
    case BlockExpr(exprType, exprs) =>
      str("{")
      indentedIf(!stateVal.inInterpolatedString) {
        repNl(exprs)(gen)
      }
      str("}")
    case _ =>
      genAsBlock(BlockExpr(NoType, Seq(e)))
  }

  def genBlockOrExpr(expr: Expr): Unit = expr match {
    case b: BlockExpr => genAsBlock(b)
    case e => gen(e)
  }

  def genRunBlock(blockExpr: BlockExpr): Unit = {
    if (!stateVal.inInterpolatedString) str("run ")
    str("{")
    indentedIf(!stateVal.inInterpolatedString) {
      repNl(blockExpr.exprs)(gen)
    }
    str("}")
  }

  def genKeyword(k: Keyword): Unit =
    str(k.name)

  def genType(t: Type, pref: Boolean = true): Unit = {
    if (pref) str(": ")
    str(t.asKotlin)
  }
}
