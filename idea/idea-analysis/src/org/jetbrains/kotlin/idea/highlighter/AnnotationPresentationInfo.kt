/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.highlighter

import com.intellij.codeInsight.intention.EmptyIntentionAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.util.containers.MultiMap
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.idea.inspections.KotlinUniversalQuickFix
import org.jetbrains.kotlin.idea.util.application.isApplicationInternalMode
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode

class AnnotationPresentationInfo(
    val ranges: List<TextRange>,
    val nonDefaultMessage: String? = null,
    val highlightType: ProblemHighlightType? = null,
    val textAttributes: TextAttributesKey? = null
) {

    fun processDiagnostics(
        holder: AnnotationHolder,
        diagnostics: Collection<Diagnostic>,
        annotationBuilderByDiagnostic: MutableMap<Diagnostic, AnnotationBuilder>? = null,
        fixesMap: MultiMap<Diagnostic, IntentionAction>?
    ) {
        for (range in ranges) {
            for (diagnostic in diagnostics) {
                create(diagnostic, range, holder) { annotation ->
                    annotationBuilderByDiagnostic?.put(diagnostic, annotation)
                    fixesMap?.let { applyFixes(it, diagnostic, annotation) }
                }
            }
        }
    }

    internal fun applyFixes(
        fixesMap: MultiMap<Diagnostic, IntentionAction>,
        diagnostic: Diagnostic,
        annotation: AnnotationBuilder
    ) {
        val fixes = fixesMap[diagnostic]
        fixes.forEach {
            when (it) {
                is KotlinUniversalQuickFix -> annotation.newFix(it).universal().registerFix()
                is IntentionAction -> annotation.newFix(it).registerFix()
            }
        }

        if (diagnostic.severity == Severity.WARNING) {
            if (fixes.isEmpty()) {
                // if there are no quick fixes we need to register an EmptyIntentionAction to enable 'suppress' actions
                annotation.newFix(EmptyIntentionAction(diagnostic.factory.name)).registerFix()
            }
        }
    }

    private fun create(diagnostic: Diagnostic, range: TextRange, holder: AnnotationHolder, consumer: (AnnotationBuilder) -> Unit) {
        val severity = when (diagnostic.severity) {
            Severity.ERROR -> HighlightSeverity.ERROR
            Severity.WARNING -> if (highlightType == ProblemHighlightType.WEAK_WARNING) {
                HighlightSeverity.WEAK_WARNING
            } else HighlightSeverity.WARNING
            Severity.INFO -> HighlightSeverity.WEAK_WARNING
        }


        val message = nonDefaultMessage ?: getDefaultMessage(diagnostic)
        holder.newAnnotation(severity, message)
            .range(range)
            .tooltip(getMessage(diagnostic))
            .also { builder -> highlightType?.let { builder.highlightType(it) } }
            .also { builder -> textAttributes?.let { builder.textAttributes(it) } }
            .also {
                if (diagnostic.severity == Severity.WARNING) {
                    it.problemGroup(KotlinSuppressableWarningProblemGroup(diagnostic.factory))
                }
            }
            .also { consumer(it) }

            .create()
    }

    private fun getMessage(diagnostic: Diagnostic): String {
        var message = IdeErrorMessages.render(diagnostic)
        if (isApplicationInternalMode() || isUnitTestMode()) {
            val factoryName = diagnostic.factory.name
            message = if (message.startsWith("<html>")) {
                "<html>[$factoryName] ${message.substring("<html>".length)}"
            } else {
                "[$factoryName] $message"
            }
        }
        if (!message.startsWith("<html>")) {
            message = "<html><body>${XmlStringUtil.escapeString(message)}</body></html>"
        }
        return message
    }

    private fun getDefaultMessage(diagnostic: Diagnostic): String {
        val message = DefaultErrorMessages.render(diagnostic)
        return if (isApplicationInternalMode() || isUnitTestMode()) {
            "[${diagnostic.factory.name}] $message"
        } else {
            message
        }
    }

}
