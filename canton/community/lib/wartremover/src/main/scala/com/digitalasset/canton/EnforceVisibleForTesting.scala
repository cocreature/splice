// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates.
// Proprietary code. All rights reserved.

package com.digitalasset.canton

import org.wartremover.{WartTraverser, WartUniverse}

import scala.annotation.tailrec

/** Wart such that we avoid using vals, defs, objects, classes, etc...
  * that are marked with [[com.google.common.annotations.VisibleForTesting]] in production code.
  *
  * Notice that usage of such definitions is allowed in the private or protected scope.
  */
object EnforceVisibleForTesting extends WartTraverser {

  val message =
    "A member with the annotation @VisibleForTesting MUST NOT be used in production code outside the private or protected scope."

  def apply(u: WartUniverse): u.Traverser = {
    import u.universe.*

    val visibleForTestingAnnotation = typeOf[com.google.common.annotations.VisibleForTesting]

    new Traverser {
      val currentlyVisitingDefitions = scala.collection.mutable.Set[String]()

      def isVisibleForTestingAnnotation(annotation: Annotation) =
        annotation.tree.tpe =:= visibleForTestingAnnotation
      def isDefinitionWithVisibleForTestingAnnotation(symbol: Symbol): Boolean =
        symbol.annotations.exists(isVisibleForTestingAnnotation) ||
          symbol.overrides.exists(isDefinitionWithVisibleForTestingAnnotation)

      def hasVisibleForTestingAnnotation(symbol: Symbol): Boolean =
        symbol.annotations.exists(isVisibleForTestingAnnotation) ||
          symbol.overrides.exists(isDefinitionWithVisibleForTestingAnnotation)

      @tailrec
      def currentlyVisitingParents(symbol: Symbol): Boolean =
        if (symbol == null || symbol == u.universe.NoSymbol || symbol.isPackage) false
        else
          (symbol.owner != null && currentlyVisitingDefitions.contains(
            symbol.owner.fullName
          )) || currentlyVisitingParents(symbol.owner)

      @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
      object AccessToMemberWithVisibleforTestingAnnotation {
        def unapply(t: Tree): Option[Tree] =
          t match {
            case select @ Select(receiver, name)
                if
                // we are not currently in the companion's body
                !currentlyVisitingDefitions.contains(
                  receiver.tpe.typeSymbol.companion.fullName
                ) /* and we are not currently in the body of the receiver (i.e. this) */ && !currentlyVisitingDefitions
                  .contains(
                    receiver.tpe.typeSymbol.fullName
                  ) /* and we are not currently in the enclosing scope of the receiver's type */ && !currentlyVisitingParents(
                  receiver.tpe.typeSymbol
                ) =>
              val member = receiver.tpe.member(name)
              Option.when(
                (
                  // check whether the method/val is annotatedwith @VisibleForTesting
                  hasVisibleForTestingAnnotation(member) ||
                    // this is needed to access the backing field of a generated/synthetic getter
                    (member.isMethod && member.asMethod.isGetter && hasVisibleForTestingAnnotation(
                      member.asMethod.accessed
                    )) ||
                    // check whether the type of the method call/constructor is annotated with @VisibleForTesting
                    hasVisibleForTestingAnnotation(receiver.tpe.typeSymbol) ||
                    // check whether the entire expression is of a type annotated with @VisibleForTesting
                    hasVisibleForTestingAnnotation(select.tpe.typeSymbol)
                )
              )(select)
            case _ => None
          }
      }

      override def traverse(tree: Tree): Unit =
        tree match {
          case t if hasWartAnnotation(u)(t) =>
          // Ignore trees marked by SuppressWarnings

          case c: ClassDef =>
            // record that we are within the definition of a class
            currentlyVisitingDefitions.addOne(c.symbol.fullName)
            super.traverse(tree)
            val _ = currentlyVisitingDefitions.remove(c.symbol.fullName)

          case m: ModuleDef =>
            // record that we are within the definition of an object
            currentlyVisitingDefitions.addOne(m.symbol.fullName)
            super.traverse(tree)
            val _ = currentlyVisitingDefitions.remove(m.symbol.fullName)

          case t: ValOrDefDef if isDefinitionWithVisibleForTestingAnnotation(t.symbol) =>
          // Do not look into definitions that are annotated with VisibleForTesting.
          // Basically, allow the usage of @VisibleForTesting annotated members within
          // the body of defs/vals defined with @VisibleForTesting

          case t: DefDef if isSynthetic(u)(t) =>
          // Do not look into synthetic definitions generated by the scala compiler

          case AccessToMemberWithVisibleforTestingAnnotation(_) =>
            error(u)(tree.pos, message)
            super.traverse(tree)

          case _ =>
            super.traverse(tree)
        }
    }
  }
}
