package darthorimar.scalaToKotlinConverter.step

import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi._
import com.intellij.psi.impl.source.JavaDummyHolder
import darthorimar.scalaToKotlinConverter.ast._
import darthorimar.scalaToKotlinConverter.definition.TupleDefinition
import darthorimar.scalaToKotlinConverter.scopes.ScopedVal.scoped
import darthorimar.scalaToKotlinConverter.scopes.{ASTGeneratorState, ScopedVal}
import darthorimar.scalaToKotlinConverter.step.ConverterStep.Notifier
import darthorimar.scalaToKotlinConverter.types.{KotlinTypes, LibTypes, ScalaTypes}
import darthorimar.scalaToKotlinConverter.{Exprs, ast}
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base._
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns._
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScTypeArgs, ScTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScFunctionDefinition, _}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScClassParents, ScTemplateParents}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScNewTemplateDefinitionImpl
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScClassImpl
import org.jetbrains.plugins.scala.lang.psi.light.PsiClassWrapper
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.{DesignatorOwner, ScProjectionType, ScThisType}
import org.jetbrains.plugins.scala.lang.psi.types.api.{JavaArrayType, StdType, TypeParameterType}
import org.jetbrains.plugins.scala.lang.psi.types.nonvalue.{ScMethodType, ScTypePolymorphicType}
import org.jetbrains.plugins.scala.lang.psi.types.result.{TypeResult, Typeable}
import org.jetbrains.plugins.scala.lang.psi.types.{ScAbstractType, ScCompoundType, ScExistentialArgument, ScExistentialType, ScParameterizedType, ScType}
import org.jetbrains.plugins.scala.lang.psi.{ScalaPsiElement, ScalaPsiUtil}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.transformation.{bindTo, qualifiedNameOf}
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.JavaDummyHolder
import org.jetbrains.plugins.scala.extensions.{FirstChild, ImplicitConversion}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypedDefinition
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaCode._
import org.jetbrains.plugins.scala.lang.transformation.{AbstractTransformer, bindTo, qualifiedNameOf}
import org.jetbrains.plugins.scala.project.ProjectContext

import scala.annotation.tailrec
import scala.util.Try

class ASTGenerationStep extends ConverterStep[ScalaPsiElement, AST] {
  val stateVal     = new ScopedVal[ASTGeneratorState](ASTGeneratorState(Map.empty))
  val stepStateVal = new ScopedVal[ConverterStepState](new ConverterStepState)

  override def name: String = "Collecting code data"

  override def apply(from: ScalaPsiElement,
                     state: ConverterStepState,
                     index: Int,
                     notifier: Notifier): (AST, ConverterStepState) =
    scoped(
      stepStateVal.set(state)
    ) {
      notifier.notify(this, index)
      val ast = inReadAction {
        gen[AST](from)
      }
      (ast, stepStateVal)
    }

  private def genDefinitions(file: ScalaFile): Seq[PsiElement] =
    file.getChildren.filter {
      case _: ScFunction => true
      case _: ScVariable => true
      case _: ScValue    => true
      case _             => false
    } ++ file.typeDefinitions

  private def genFunctionBody(fun: ScFunction): Option[Expr] = fun match {
    case x: ScFunctionDefinition =>
      x.body.map(gen[Expr])
    case _: ScFunctionDeclaration =>
      None
  }

  private def genTypeArgs(typeArgs: Option[ScTypeArgs]): Seq[Type] = {
    typeArgs
      .map(_.typeArgs)
      .toSeq
      .flatten
      .map(z => genType(z.`type`()))
  }

  private def createTypeByName(typeName: String): Type =
    typeName.stripPrefix("_root_.") match {
      case name if name.startsWith("scala.") =>
        ScalaType(name)
      case name if name.startsWith("java.") =>
        JavaType(name)
      case name => ClassType(name)
    }

  private def genType(ty: ScType): Type =
    ty match {
      case x: StdType =>
        ast.StdType(x.name)
      case x: ScParameterizedType if x.designator.canonicalText.startsWith(ScalaTypes.FUNCTION_PREFFIX) =>
        if (x.typeArguments.init.length == 1)
          FunctionType(genType(x.typeArguments.head), genType(x.typeArguments.last))
        else
          FunctionType(ProductType(x.typeArguments.init.map(genType)), genType(x.typeArguments.last))
      case x: ScParameterizedType =>
        GenericType(genType(x.designator), x.typeArguments.map(genType))
      case x: ScTypePolymorphicType =>
        genType(x.internalType)
      case x: ScMethodType =>
        FunctionType(ProductType(x.params.map(t => genType(t.paramType))), genType(x.returnType))
      case x: DesignatorOwner =>
        x.extractClass.map {
          case c: ScClassImpl => createTypeByName(c.qualifiedName)
          case c: PsiClass    => createTypeByName(c.getQualifiedName)
        } orElse {
          x.extractDesignatorSingleton.map(genType)
        } getOrElse createTypeByName(x.canonicalText)
      case x: ScProjectionType =>
        genType(x.projected)
      case x: ScThisType =>
        genType(x.element.`type`())
      case x: ScExistentialType =>
        genType(x.quantified)
      case x: TypeParameterType =>
        TypeParamType(gen[TypeParam](x.typeParameter.psiTypeParameter))
      case x: ScExistentialArgument =>
        SimpleType("*")
      case x: JavaArrayType =>
        GenericType(KotlinTypes.ARRAY, Seq(genType(x.argument)))
      case x: ScAbstractType =>
        genType(x.inferValueType)
      case t: ScCompoundType =>
        genType(t.components.find { c =>
          c.canonicalText != "Product" && c.canonicalText != "Serializable"
        }.get)
    }

  private def genType(t: Option[ScTypeElement]): Type =
    t.flatMap(_.`type`().toOption)
      .map(genType)
      .getOrElse(NoType)

  private def genType(t: TypeResult): Type =
    t.map(genType).getOrElse(NoType)

  private def blockOrNone(exprs: Seq[Expr]): Option[BlockExpr] =
    if (exprs.nonEmpty) Some(BlockExpr(exprs))
    else None

  private def genAttributes(member: ScMember): Seq[Attribute] = {
    def addAttribute(add: Boolean, attribute: Attribute) =
      if (add) Some(attribute) else None

    val memberAttrs = (addAttribute(member.isPrivate, PrivateAttribute) ::
      addAttribute(member.isPublic, PublicAttribute) ::
      addAttribute(member.isProtected, ProtectedAttribute) ::
      addAttribute(member.hasFinalModifier, FinalAttribute) ::
      addAttribute(member.hasAbstractModifier, AbstractAttribute) ::
      addAttribute(member.getModifierList().has(ScalaTokenTypes.kIMPLICIT), ImplicitAttribute) ::
      Nil).flatten
    val extraAttrs = member match {
      case y: ScFunction =>
        addAttribute(y.superMethod.isDefined, OverrideAttribute).toSeq
      case y: ScTypeDefinition =>
        (addAttribute(y.isCase, CaseAttribute) ::
          Nil).flatten

      case _ => Seq.empty
    }
    memberAttrs ++ extraAttrs
  }

  private def gen[T](psi: PsiElement): T =
    stateVal.precalculated
      .get(psi.getTextRange)
      .map(_.asInstanceOf[T])
      .getOrElse(recover[T](psi))

  private def findUnderscores(expr: PsiElement): Seq[ScUnderscoreSection] = {
    if (expr.getText.indexOf('_') == -1) Seq.empty
    else inner(expr)

    def inner(innerExpr: PsiElement): Seq[ScUnderscoreSection] = {
      innerExpr match {
        case under: ScUnderscoreSection =>
          Seq(under)
        case _ =>
          innerExpr.getChildren.flatMap(inner)
      }
    }

    inner(expr)
  }

  private def canonicalName(p: PsiElement, clazz: PsiClass): String =
    Option(p) map {
      case clazz: ScObject if clazz.isStatic => clazz.qualifiedName
      case c: ScClass                        => c.qualifiedName
      case c: PsiClass                       => c.getQualifiedName
      case m: PsiMember =>
        Option(m.getContainingClass)
          .filter(_ != clazz && m.hasModifier(JvmModifier.STATIC))
          .map(canonicalName(_, clazz) + ".")
          .getOrElse("") + m.getName

      case p: ScParameter => p.getName
      case f: ScFieldId => f.name
    } getOrElse ""

  def recover[T](psi: PsiElement): T =
    Try(transform[T](psi))
//      .recoverWith { case _ => Try(ErrorExpr(psi.getText).asInstanceOf[T]) }
//      .recoverWith { case _ => Try(ErrorCasePattern(psi.getText).asInstanceOf[T]) }
//      .recoverWith { case _ => Try(ErrorType(psi.getText).asInstanceOf[T]) }
//      .recoverWith { case _ => Try(ErrorForEnumerator(psi.getText).asInstanceOf[T]) }
//      .recoverWith { case _ => Try(ErrorWhenClause(psi.getText).asInstanceOf[T]) }
      .get

  private def transform[T](psi: PsiElement): T =
    (psi match {
      case psi: ScalaFile => //todo x --> sth else
        val underscores =
          findUnderscores(psi)
            .flatMap(_.overExpr)
            .map { over =>
              val expr       = gen[Expr](over)
              val lambdaExpr = LambdaExpr(expr.exprType, Seq.empty, expr, needBraces = false)
              over.getTextRange -> lambdaExpr
            }
            .toMap
        scoped(
          stateVal.set(ASTGeneratorState(underscores))
        ) {
          File(
            psi.getPackageName,
            genDefinitions(psi)
              .filter {
                case _: PsiClassWrapper                 => false
                case y: ScObject if y.isSyntheticObject => false
                case _                                  => true
              }
              .map(gen[DefExpr])
              .filter {
                case Defn(_, ObjDefn, _, _, _, _, _, Some(_)) => false
                case _                                        => true
              }
          )
        }

      case typeDef: ScTypeDefinition =>
        val construct = typeDef match {
          case cls: ScClass => Some(cls.constructor.map(gen[Constructor]).getOrElse(EmptyConstructor))
          case _            => None
        }

        val overrideConstuctParamsDefs =
          typeDef match {
            case cls: ScClass =>
              cls.constructor.toSeq
                .collect {
                  case constructor: ScPrimaryConstructor =>
                    constructor.parameters
                      .filter(p => ScalaPsiUtil.superValsSignatures(p).nonEmpty)
                }
                .flatten
                .map { p: ScClassParameter =>
                  DefnDef(
                    Seq(PublicAttribute, OverrideAttribute),
                    None,
                    p.name,
                    Seq.empty,
                    Seq.empty,
                    genType(p.`type`()),
                    Some(RefExpr(genType(p.`type`()), None, p.name, Seq.empty, isFunctionRef = false))
                  )
                }
            case _ => Seq.empty
          }

        val defnType =
          typeDef match {
            case _: ScClass  => ClassDefn
            case _: ScTrait  => TraitDefn
            case _: ScObject => ObjDefn
          }

        val companionDefn = typeDef.baseCompanionModule.map {
          case _ if defnType == ObjDefn => ObjectCompanion
          case c                        => ClassCompanion(gen[Defn](c))
        }

        Defn(
          genAttributes(typeDef),
          defnType,
          typeDef.name,
          typeDef.typeParameters.map(gen[TypeParam]),
          construct,
          typeDef.extendsBlock.templateParents.map(gen[SupersBlock]),
          blockOrNone(overrideConstuctParamsDefs ++ typeDef.extendsBlock.members.map(gen[DefExpr])),
          companionDefn
        )

      case x: ScTemplateParents =>
        val constructor = x match {
          case y: ScClassParents =>
            y.constructor.map { c =>
              val needBrackets =
                x.typeElements.headOption
                  .flatMap(_.`type`().toOption)
                  .collect {
                    case d: DesignatorOwner => d.extractClass
                  }
                  .flatten
                  .exists(!_.isInstanceOf[ScTrait])
              SuperConstructor(genType(c.typeElement.`type`()),
                               c.args.toSeq.flatMap(_.exprs).map(gen[Expr]),
                               needBrackets)
            }
          case _ => None
        }
        SupersBlock(constructor, x.typeElementsWithoutConstructor.map(_.`type`()).map(genType))

      case x: ScFunction =>
        DefnDef(genAttributes(x),
                None,
                x.name,
                x.typeParameters.map(gen[TypeParam]),
                x.parameters.map(gen[DefParameter]),
                genType(x.returnType),
                genFunctionBody(x))

      case x: ScBlockExpr if x.isAnonymousFunction =>
        LambdaExpr(
          genType(x.`type`()),
          Seq.empty,
          MatchExpr(genType(x.`type`()),
                    UnderscoreExpr(NoType),
                    x.caseClauses.get.caseClauses.map(gen[MatchCaseClause])),
          needBraces = false
        )

      case x: ScBlockExpr if x.isInCatchBlock =>
        ScalaCatch(x.caseClauses.get.caseClauses.map(gen[MatchCaseClause]))

      case x: ScBlock =>
        BlockExpr(x.statements.map(gen[Expr]))

      case psi: ScTuple =>
        val arity = psi.exprs.length
        val exprs = psi.exprs.map(gen[Expr])
        if (arity == 2) {
          NewExpr(GenericType(KotlinTypes.PAIR, exprs.map(_.exprType)), exprs)
        } else {
          stepStateVal.addDefinition(new TupleDefinition(arity))
          NewExpr(GenericType(LibTypes.tupleType(arity), exprs.map(_.exprType)), exprs)
        }

      case x: ScInterpolatedStringLiteral =>
        InterpolatedStringExpr(x.getStringParts, x.getInjections.map(gen[Expr]))
      case x: ScLiteral =>
        LitExpr(genType(x.`type`()), x.getText)
      case x: ScUnderscoreSection =>
        UnderscoreExpr(genType(x.`type`()))
      case x: ScParenthesisedExpr =>
        ParenthesesExpr(gen[Expr](x.innerElement.get))

      case x: ScReferenceExpression =>
        @tailrec
        def containingClass(p: PsiElement): Option[PsiClass] = p match {
          case c: PsiClass => Some(c)
          case _: PsiFile  => None
          case _           => containingClass(p.getParent)
        }

        val ty     = genType(x.`type`())
        val binded = x.bind().get
        val isFunc = {
          val sourceElement = x.getReference.resolve()
          sourceElement.isInstanceOf[ScFunction] || sourceElement.isInstanceOf[PsiMethod]
        }

        val refName =
          if (x.smartQualifier.isDefined) x.refName
          else canonicalName(binded.getActualElement, containingClass(x).orNull)

        val referencedObject =
          RefExpr(ty, x.smartQualifier.map(gen[Expr]), refName, Seq.empty, isFunc)

        binded.innerResolveResult.getOrElse(binded).element match {
          case target: PsiNamedElement if x.refName != target.name && target.name == "apply" =>
            val refType = binded.getActualElement match {
              case typeable: Typeable => genType(typeable.`type`())
              case _                  => ty
            }
            RefExpr(ty,
                    Some(referencedObject.copy(isFunctionRef = false, exprType = refType)),
                    "apply",
                    Seq.empty,
                    isFunctionRef = true)
          case _ => referencedObject
        }

      case psi: MethodInvocation =>
        val paramsInfo =
          psi.matchedParameters.map {
            case (_, p) => CallParameterInfo(genType(p.expectedType), p.isByName)
          }

        val args = psi match {
          case _: ScInfixExpr =>
            Seq(psi.argsElement)
          case _ => psi.argumentExpressions
        }

        CallExpr(genType(psi.`type`()), gen[Expr](psi.getInvokedExpr), args.map(gen[Expr]), paramsInfo)

      case x: ScGenericCall =>
        gen[RefExpr](x.referencedExpr).copy(typeParams = genTypeArgs(x.typeArgs))

      case psi: ScTypedStmt if psi.isSequenceArg =>
        PrefixExpr(genType(psi.`type`()), gen[Expr](psi.expr), "*")

      case psi: ScTypedStmt =>
        Exprs.asExpr(gen[Expr](psi.expr), genType(psi.typeElement))

      case x: ScIfStmt =>
        IfExpr(genType(x.`type`()),
               gen[Expr](x.condition.get),
               gen[Expr](x.thenBranch.get),
               x.elseBranch.map(gen[Expr]))

      case x: ScMatchStmt =>
        MatchExpr(genType(x.`type`()), gen[Expr](x.expr.get), x.caseClauses.map(gen[MatchCaseClause]))
      case x: ScFunctionExpr =>
        LambdaExpr(genType(x.`type`()),
                   x.parameters.map(gen[DefParameter]),
                   gen[Expr](x.result.get),
                   needBraces = false)

      case x: ScCaseClause =>
        MatchCaseClause(gen[CasePattern](x.pattern.get),
                        x.expr.map(gen[Expr]).get, //todo fix
                        x.guard.flatMap(_.expr).map(gen[Expr]))

      case x: ScTuplePattern =>
        val arity = x.patternList.toSeq.flatMap(_.patterns).size
        val constructorType =
          if (arity == 2) ClassType("Pair")
          else {
            ClassType(s"Tuple$arity")
          }

        if (arity != 2) stepStateVal.addDefinition(new TupleDefinition(arity))
        ConstructorPattern(CaseClassConstructorRef(constructorType),
                           x.patternList.toSeq.flatMap(_.patterns.map(gen[CasePattern])),
                           None,
                           x.getText)

      case x: ScCompositePattern =>
        CompositePattern(x.subpatterns.map(gen[CasePattern]), None)
      case x: ScLiteralPattern =>
        LitPattern(gen[LitExpr](x.getLiteral), None)
      case x: ScNamingPattern =>
        gen[ConstructorPattern](x.named).copy(label = Some(x.name))
      case x: ScConstructorPattern =>
        val bindResult = x.ref.bind()
        val obj = bindResult.flatMap(_.getActualElement match {
          case o: ScObject => Some(o)
          case _           => None
        })
        val unapplyRef = bindResult.flatMap(_.element match {
          case f: ScFunction => Some(f)
          case _             => None
        })
        val isCaseClass =
          obj.flatMap(_.baseCompanionModule).exists(_.isCase)
        val constuctorRef = (obj, unapplyRef) match {
          case (Some(o), Some(r)) if !isCaseClass =>
            UnapplyCallConstuctorRef(o.name, genType(r.returnType))
          case _ =>
            val className = canonicalName(x.ref.resolve(), null).stripSuffix(".unapply")
            CaseClassConstructorRef(createTypeByName(className))
        }
        ConstructorPattern(constuctorRef, x.args.patterns.map(gen[CasePattern]), None, x.getText)

      case x: ScTypedPattern =>
        TypedPattern(x.name, genType(x.typePattern.map(_.typeElement)), None)
      case x: ScReferencePattern =>
        x.expectedType.map(genType) match {
          case Some(t) => TypedPattern(x.name, t, None)
          case _       => ReferencePattern(x.name, None)
        }

      case x: ScReferenceElement =>
        ReferencePattern(x.refName, None)
      case x: ScStableReferenceElementPattern =>
        LitPattern(gen[Expr](x.getReferenceExpression.get), None)
      case _: ScWildcardPattern =>
        WildcardPattern(None)

      case x: ScPatternDefinition if x.isSimple =>
        SimpleValOrVarDef(
          genAttributes(x),
          isVal = true,
          x.pList.patterns.head.getText, //todo add support of sth like `val a,b = "nya"`
          Some(genType(x.pList.patterns.head.`type`())),
          x.expr.map(gen[Expr])
        )
      case x: ScPatternDefinition =>
        ScalaValOrVarDef(
          genAttributes(x),
          isVal = true,
          gen[ConstructorPattern](x.pList.patterns.head), //todo add support of sth like `val a,b = "nya"`
          x.expr.map(gen[Expr]).get)

      case x: ScVariableDefinition if x.isSimple =>
        SimpleValOrVarDef(
          genAttributes(x),
          isVal = false,
          x.pList.patterns.head.getText,
          Some(genType(x.pList.patterns.head.`type`())),
          x.expr.map(gen[Expr])
        )

      case x: ScValueDeclaration =>
        SimpleValOrVarDef(genAttributes(x), true, x.declaredElements.head.name, x.declaredType.map(genType), None)

      case x: ScVariableDeclaration =>
        SimpleValOrVarDef(genAttributes(x), false, x.declaredElements.head.name, x.declaredType.map(genType), None)

      case x: ScAssignStmt =>
        AssignExpr(gen[Expr](x.getLExpression), gen[Expr](x.getRExpression.get))

      case x: ScNewTemplateDefinitionImpl =>
        NewExpr(genType(Some(x.constructor.get.typeElement)),
                x.constructor.get.args.toSeq.flatMap(_.exprs).map(gen[Expr]))

      case x: ScPrimaryConstructor =>
        ParamsConstructor(x.parameters.map(gen[ConstructorParam]))

      case x: ScClassParameter =>
        val kind =
          if (x.isVal) ValKind
          else if (x.isVar) VarKind
          else NoMemberKind

        val modifier = kind match {
          case NoMemberKind => NoAttribute
          case _ =>
            if (x.isPrivate) PrivateAttribute
            else if (x.isProtected) ProtectedAttribute
            else if (x.hasModifierProperty("public")) PublicAttribute
            else NoAttribute
        }

        ConstructorParam(kind, modifier, x.name, genType(x.typeElement))

      case x: ScParameter =>
        DefParameter(genType(x.typeElement), x.name, x.isVarArgs, x.isCallByNameParameter)

      case x: ScTryStmt =>
        ScalaTryExpr(genType(x.`type`()),
                     gen[Expr](x.tryBlock),
                     x.catchBlock.flatMap(_.expression).map(gen[ScalaCatch]),
                     x.finallyBlock.flatMap(_.expression).map(gen[Expr]))

      case x: ScForStatement =>
        ForExpr(genType(x.`type`()),
                x.enumerators.toSeq.flatMap(_.getChildren).map(gen[ForEnumerator]),
                x.isYield,
                gen[Expr](x.body.get))

      case x: ScGenerator =>
        ForGenerator(gen[CasePattern](x.pattern), gen[Expr](x.rvalue))

      case x: ScGuard =>
        ForGuard(gen[Expr](x.expr.get))

      case x: ScEnumerator =>
        ForVal(ast.SimpleValOrVarDef(Seq.empty, isVal = true, x.pattern.getText, None, Some(gen[Expr](x.rvalue))))

      case x: ScTypeParam =>
        import org.jetbrains.plugins.scala.lang.psi.types.api._

        val variance = x.variance match {
          case Invariant     => InvariantTypeParam
          case Covariant     => CovariantTypeParam
          case Contravariant => ContravariantTypeParam
        }
        TypeParam(x.name,
                  variance,
                  x.upperBound.toOption.map(genType).filter(_ != ast.StdType("Any")),
                  x.lowerBound.toOption.map(genType).filter(_ != ast.StdType("Nothing")))

      case x: ScPostfixExpr =>
        PostfixExpr(genType(x.`type`()), gen[Expr](x.operand), x.operation.refName)

      case x: ScThisReference =>
        ThisExpr(genType(x.`type`()))

      case x: ScReturnStmt =>
        ReturnExpr(None, x.expr.map(gen[Expr]))

      case x: ScInfixExpr =>
        InfixExpr(genType(x.`type`()), gen[RefExpr](x.operation), gen[Expr](x.left), gen[Expr](x.right), x.isLeftAssoc)

    }).asInstanceOf[T]

}
